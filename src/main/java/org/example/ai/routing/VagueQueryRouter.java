package org.example.ai.routing;

import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.config.DynamicKeywordBuilder;
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
 * L1（#13）+ L2（#14）已实现；L3 留桩后续 ticket 补。
 */
@Component
public class VagueQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(VagueQueryRouter.class);

    /** L1 父块相似度阈值：EntityResolver 命中后需通过此验证才算 EXACT */
    private static final double L1_CONFIDENCE = 0.7;

    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final AskCountTracker askCountTracker;
    private final VectorStore vectorStore;

    public VagueQueryRouter(EntityResolver entityResolver, DynamicKeywordBuilder keywordBuilder,
                            AskCountTracker askCountTracker, VectorStore vectorStore) {
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.askCountTracker = askCountTracker;
        this.vectorStore = vectorStore;
    }

    /**
     * 路由入口。根据用户消息和对话历史决定匹配类型。
     *
     * @param userMessage 用户当前消息
     * @param historyText 最近 N 轮对话文本（L3 使用，L1/L2 忽略）
     * @return MatchResult 或 null（无匹配，走现有检索回退路径）
     */
    public MatchResult route(String userMessage, String historyText) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // L1：精确车系匹配
        MatchResult l1 = tryExactMatch(userMessage);
        if (l1 != null) return l1;

        // L2：品牌级匹配（#14 ticket）
        MatchResult l2 = tryBrandMatch(userMessage);
        if (l2 != null) return l2;

        // L3：级联兜底（留桩，后续 ticket 实现）
        // MatchResult l3 = tryVagueMatch(userMessage, historyText);
        // if (l3 != null) return l3;

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
}