package org.example.ai.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.location.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模糊语义路由器 —— 三层匹配策略（决策9/10/11）。
 *
 * 在 CarSalesAgent.chat() 中同步调用，位于 ChatClient 链之前。
 * L1（#13）+ L2（#14）+ L3（#15）已全部实现。
 */
@Component
public class VagueQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(VagueQueryRouter.class);

    /** L1 父块相似度阈值：EntityResolver 命中后需通过此验证才算 EXACT */
    private static final double L1_CONFIDENCE = 0.7;

    /**
     * 门店/地址/联系方式查询关键词。
     * 命中时表示用户意图是找门店而非咨询车系，应返回 null 让 LLM 调用 getStoreInfo() 工具处理。
     */
    private static final Set<String> LOCATION_QUERY_WORDS = Set.of(
            "门店", "地址", "电话", "联系方式", "在哪里", "在哪", "怎么去", "怎么走",
            "营业时间", "工作时间", "位置", "定位", "导航", "路线", "到店", "线下",
            "试驾", "试车", "预约", "体验店", "展厅", "实体店"
    );

    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final AskCountTracker askCountTracker;
    private final VectorStore vectorStore;
    private final GeoLocator geoLocator;
    private final StoreLocator storeLocator;
    private final HotCarRepository hotCarRepo;

    public VagueQueryRouter(EntityResolver entityResolver, DynamicKeywordBuilder keywordBuilder,
                            AskCountTracker askCountTracker, VectorStore vectorStore,
                            GeoLocator geoLocator, StoreLocator storeLocator,
                            HotCarRepository hotCarRepo) {
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.askCountTracker = askCountTracker;
        this.vectorStore = vectorStore;
        this.geoLocator = geoLocator;
        this.storeLocator = storeLocator;
        this.hotCarRepo = hotCarRepo;
    }

    /**
     * 路由入口。根据用户消息决定匹配类型。
     *
     * 当前仅启用 L1（精确车系匹配）+ 门店查询检测。
     * L2（品牌级匹配）和 L3（级联兜底）已回退——固定回答策略会劫持正常查询，
     * 改为依赖 RAG 向量检索 + LLM 语义理解处理模糊/品牌级问题。
     *
     * @param userMessage 用户当前消息
     * @param historyText 最近 N 轮对话文本（当前未使用）
     * @param userIp      客户端 IP（当前仅门店查询检测使用）
     * @return MatchResult 或 null（无匹配，走 RAG + LLM 回退路径）
     */
    public MatchResult route(String userMessage, String historyText, String userIp) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // ---- 门店/地址/试驾查询：不进入车系路由，返回 null 交给 LLM + getStoreInfo() 工具 ----
        if (isLocationQuery(userMessage)) {
            log.info("门店/地址查询检测，跳过路由交由 LLM 处理: {}", userMessage);
            return null;
        }

        // L1：精确车系匹配（EntityResolver 命中 + 父块相似度验证）
        MatchResult l1 = tryExactMatch(userMessage);
        if (l1 != null) return l1;

        // L2/L3 回退：品牌级匹配和级联兜底的固定回答策略已禁用。
        // 模糊/品牌级问题走 RAG 向量检索 + LLM 语义理解路径，不再使用模板追问。
        return null;
    }

    /**
     * L1：精确车系匹配。
     * EntityResolver 命中 → 取 top-1 → 父块相似度验证（阈值 0.7）。
     */
    MatchResult tryExactMatch(String userMessage) {
        List<ResolvedEntity> entities = entityResolver.resolve(userMessage);
        if (entities.isEmpty()) {
            log.debug("L1: EntityResolver 无命中");
            return null;
        }

        ResolvedEntity top = entities.get(0);
        String seriesKey = top.seriesKey(); // "比亚迪-宋PLUS DM-i"

        // 父块相似度验证
        Filter.Expression filter = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("type", "车源"),
                new FilterExpressionBuilder().and(
                        new FilterExpressionBuilder().eq("level", "parent"),
                        new FilterExpressionBuilder().eq("series_id", seriesKey))
        ).build();

        List<Document> parentDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(1)
                        .similarityThreshold(L1_CONFIDENCE)
                        .filterExpression(filter)
                        .build());

        if (parentDocs.isEmpty()) {
            log.debug("L1: EntityResolver 命中 {}，但父块相似度不足 0.7", seriesKey);
            return null;
        }

        log.info("L1 EXACT: {} (entityId={})", seriesKey, top.entityId());
        return new MatchResult(
                MatchResult.MatchType.EXACT,
                seriesKey,
                null,
                null,
                false,
                false,
                null
        );
    }

    /**
     * L2：品牌级匹配（#14 ticket）。
     *
     * 检测条件：DynamicKeywordBuilder 提取到品牌级关键词（对应 >= 2 个车系）。
     * 行为：按热度降序列出该品牌在售车系，追问缩小范围。追问文本由代码模板生成，不调 LLM。
     */
    MatchResult tryBrandMatch(String userMessage) {
        // 1. 提取消息中的关键词
        List<String> keywords = keywordBuilder.extractKeywords(userMessage);
        if (keywords.isEmpty()) return null;

        // 2. 找品牌级关键词（去重后 >= 2 个不同车系）
        for (String kw : keywords) {
            Set<String> uniqueSeries = new LinkedHashSet<>(keywordBuilder.getSeriesKeys(kw));
            if (uniqueSeries.size() < 2) continue; // 单系列或重复，不是品牌级

            // 3. 提取品牌名（取第一个 seriesKey 的 brand 部分）
            String brand = extractBrand(uniqueSeries.iterator().next());
            if (brand.isEmpty()) continue;

            // 4. 按热度排序
            List<String> sorted = uniqueSeries.stream()
                    .sorted((a, b) -> Double.compare(
                            askCountTracker.getWeightedHeat(b),
                            askCountTracker.getWeightedHeat(a)))
                    .collect(Collectors.toList());

            // 5. 生成追问文本
            List<String> hotNames = sorted.stream()
                    .limit(3)
                    .map(sk -> sk.contains("-") ? sk.substring(sk.indexOf('-') + 1) : sk)
                    .collect(Collectors.toList());

            String followUp = String.format(
                    "我们%s在售的有%s等%d款。您想看轿车还是SUV？预算大概多少？",
                    brand, String.join("、", hotNames), sorted.size());

            log.info("L2 BRAND: {} ({} 个车系)", brand, sorted.size());
            return new MatchResult(
                    MatchResult.MatchType.BRAND,
                    null,
                    brand,
                    sorted,
                    true,   // needsConfirm: 需要等用户回应后才进入检索
                    false,
                    followUp
            );
        }

        return null;
    }

    /** 从 "比亚迪-宋PLUS DM-i" 提取品牌名 "比亚迪" */
    private static String extractBrand(String seriesKey) {
        if (seriesKey == null) return "";
        int idx = seriesKey.indexOf('-');
        return idx > 0 ? seriesKey.substring(0, idx) : seriesKey;
    }

    /**
     * L3：级联兜底三步（#15 ticket，决策10）。
     *
     * Step 1: 对话历史提取（最近 10 轮中有车系引用 → 追问确认）
     * Step 2: 门店热度 top3 引导
     * Step 3: 通用引导追问
     *
     * @param historyText 最近 N 轮对话纯文本
     * @param userIp      客户端 IP（Step 2 门店定位）
     */
    MatchResult tryVagueMatch(String userMessage, String historyText, String userIp) {
        // ---- Step 1：对话历史提取 ----
        if (historyText != null && !historyText.isBlank()) {
            List<String> histKeywords = keywordBuilder.extractKeywords(historyText);
            if (!histKeywords.isEmpty()) {
                // 取第一个命中关键词的系列名
                List<String> seriesKeys = keywordBuilder.getSeriesKeys(histKeywords.get(0));
                if (!seriesKeys.isEmpty()) {
                    String seriesName = seriesKeys.get(0);
                    if (seriesName.contains("-")) {
                        seriesName = seriesName.substring(seriesName.indexOf('-') + 1);
                    }
                    String followUp = String.format(
                            "您之前聊过%s，是在关心这款车吗？我可以帮您详细介绍～",
                            seriesName);
                    log.info("L3 Step1: 历史命中 {} → 确认追问", seriesName);
                    return new MatchResult(MatchResult.MatchType.VAGUE, null, null, null,
                            true, false, followUp);
                }
            }
        }

        // ---- Step 2：门店热度 top3 引导（注入 LLM 上下文，不绕过检索） ----
        int storeId = resolveStoreId(userIp);
        if (storeId > 0) {
            List<HotCar> hot = hotCarRepo.getHotCars(storeId, 3);
            if (!hot.isEmpty()) {
                List<String> names = hot.stream()
                        .map(HotCar::seriesName)
                        .limit(3).toList();
                String followUp = String.format(
                        "门店最近热销车型：%s。如果用户预算或需求匹配可以优先推荐这些车型。",
                        String.join("、", names));
                log.info("L3 Step2: 门店{}热度 top3={}（注入 LLM 上下文）", storeId, names);
                return new MatchResult(MatchResult.MatchType.VAGUE, null, null,
                        names, false, true, followUp);
            }
        }

        // ---- Step 3：通用引导追问 ----
        String followUp = "您大概预算多少？主要通勤还是家用？喜欢轿车还是SUV？我帮您精准推荐～";
        log.info("L3 Step3: 通用引导");
        return new MatchResult(MatchResult.MatchType.VAGUE, null, null, null,
                false, true, followUp);
    }

    /** 检测用户消息是否为门店/地址/联系方式查询，命中时应返回 null 让 LLM 处理 */
    private boolean isLocationQuery(String userMessage) {
        String lower = userMessage.toLowerCase();
        for (String kw : LOCATION_QUERY_WORDS) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    /** 通过 IP 定位最近门店 ID，失败返回 -1 */
    private int resolveStoreId(String userIp) {
        try {
            GeoLocation loc = geoLocator.locate(userIp);
            if (loc == null) return -1;
            StoreInfo nearest = storeLocator.findNearest(loc.lat(), loc.lng());
            return nearest != null ? nearest.id() : -1;
        } catch (Exception e) {
            log.debug("门店定位失败: {}", e.getMessage());
            return -1;
        }
    }
}