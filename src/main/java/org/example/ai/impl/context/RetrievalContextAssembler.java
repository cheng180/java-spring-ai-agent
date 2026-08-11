package org.example.ai.impl.context;

import org.example.ai.impl.routing.QueryClassification;
import org.example.ai.config.observability.ObservationSupport;
import io.micrometer.observation.ObservationRegistry;
import org.example.ai.impl.routing.QueryLevel;
import org.example.ai.impl.routing.QueryLevelClassifier;
import org.example.ai.impl.search.HybridRetriever;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.facts.SeriesParentBuilder;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索上下文组装器 —— 从 CarSalesAgent 迁出（#23 ticket，预重构），
 * 分层检索分支（#24/#25 ticket，#21 spec）。
 *
 * <p>先由 {@link QueryLevelClassifier} 判定查询粒度，再按级别注入不同详细程度的上下文：</p>
 * <ul>
 *   <li>BRAND — 品牌级：只注入在售车系数量 + 热度 top1 推荐 + 级别指令（不查向量库）</li>
 *   <li>SERIES — 车系级：只注入该车系完整父块 + 级别指令（零子块调用）</li>
 *   <li>FAMILY — 后续 ticket 接入</li>
 *   <li>UNRESTRICTED — 现状：主路径全量展开 / 回退泛检索</li>
 * </ul>
 *
 * <p>未受限路径（#4 ticket，2026-07-29）：
 * 主路径（命中车系）= 阶段一父块相似度召回（阈值 0.6，topK=3）+ 阶段二子块全量展开（topK=20），
 * 不注入百科/话术；回退路径（未命中）= 子块泛检索（topK=5）+ 百科/话术（topK=3）。</p>
 */
@Component
public class RetrievalContextAssembler {

    private static final int RAG_TOPK = 5;
    private static final double RAG_THRESHOLD = 0.5;
    private static final double SERIES_CONFIDENCE = 0.6;
    /** 多车系细节查询的整轮子块预算，避免按车系 topK 叠加放大上下文。 */
    private static final int CHILD_DOCS_TOTAL_LIMIT = 30;

    /** BRAND 级级别指令：只报车系名、只推一款、反问收尾、禁止列清单、本轮禁调库存工具 */
    static final String BRAND_INSTRUCTION =
            "用户只问了品牌。告知该品牌有多个车系在售，只推荐上面\"近期最热门\"的那一个车系，"
            + "然后用一个问题反问用户偏好（如心仪的款式、预算或用途）收尾；"
            + "禁止列出车系/车型清单，禁止展开具体车源；本轮不要调用 searchInventory/getAllCars 工具。";

    /**
     * 披露约束子句（#32 ticket）——披露边界在回复层，不在上下文层。
     *
     * <p>上下文保持全量注入（价格等信息是客服的知识，必须在场）；
     * 本轮是否把价格数字告诉客户，由本约束裁决：未问价不报价。</p>
     */
    static final String PRICE_DISCLOSURE_CLAUSE =
            "注意：上面资料里即使有价格、库存等具体数字，用户没明确问价就不要报——"
            + "本轮只做介绍，不报价。";

    /** FAMILY 级级别指令：只讲命中的车系，问用户想深入哪个 */
    static final String FAMILY_INSTRUCTION =
            "用户提到了同品牌的多个车系。只介绍上面命中的这几个车系（不要涉及其他车系），"
            + "不要展开具体款型清单；末尾询问用户想深入了解哪个车系。"
            + PRICE_DISCLOSURE_CLAUSE;

    /** SERIES 级级别指令：只讲命中的车系，末尾问是否深入了解 */
    static final String SERIES_INSTRUCTION =
            "用户聚焦单个车系。只介绍这个车系（不要提其他车系），可基于上面的车系信息回答；"
            + "末尾询问用户是否想深入了解该车系（如优点、亮点等）。"
            + PRICE_DISCLOSURE_CLAUSE;

    private final HybridRetriever hybridRetriever;
    private final VectorStore vectorStore;
    private final QueryLevelClassifier classifier;
    private final AskCountTracker askCountTracker;
    private final JdbcTemplate jdbc;
    private final ObservationRegistry observationRegistry;

    public RetrievalContextAssembler(HybridRetriever hybridRetriever, VectorStore vectorStore,
                                     QueryLevelClassifier classifier,
                                     AskCountTracker askCountTracker, JdbcTemplate jdbc) {
        this(hybridRetriever, vectorStore, classifier, askCountTracker, jdbc, ObservationRegistry.NOOP);
    }

    @Autowired
    public RetrievalContextAssembler(HybridRetriever hybridRetriever, VectorStore vectorStore,
                                     QueryLevelClassifier classifier,
                                     AskCountTracker askCountTracker, JdbcTemplate jdbc, ObservationRegistry observationRegistry) {
        this.hybridRetriever = hybridRetriever;
        this.vectorStore = vectorStore;
        this.classifier = classifier;
        this.askCountTracker = askCountTracker;
        this.jdbc = jdbc;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 组装结果：上下文文本 + 粒度分类结果。
     * 分类结果随上下文返回，供对话日志记录级别归因
     * （《回复过长问题解决评估文档》加固建议 1：上线后可排查"该给细节却给了摘要"）。
     */
    public record Result(String context, QueryClassification classification) {}

    public Result retrieveContext(String userMessage, List<ResolvedEntity> matchedSeries) {
        return ObservationSupport.call(observationRegistry, "agent.retrieval", null, () -> retrieveContextInternal(userMessage, matchedSeries));
    }

    private Result retrieveContextInternal(String userMessage, List<ResolvedEntity> matchedSeries) {
        // ---- 粒度分类（#24） ----
        QueryClassification classification = classifier.classify(userMessage, matchedSeries);

        String ctx;
        if (classification.level() == QueryLevel.BRAND) {
            ctx = assembleBrandContext(classification);
            if (ctx == null) ctx = fallbackContext(userMessage); // 品牌无在售车系数据 → 降级回退
        } else if (classification.level() == QueryLevel.FAMILY) {
            ctx = assembleFamilyContext(classification);
            if (ctx == null) ctx = fallbackContext(userMessage); // 父块全部缺失 → 降级回退
        } else if (classification.level() == QueryLevel.SERIES) {
            ctx = assembleSeriesContext(classification);
            if (ctx == null) ctx = fallbackContext(userMessage); // 父块缺失 → 降级回退
        } else {
            // ---- UNRESTRICTED：现状行为 ----
            ctx = assembleUnrestrictedContext(userMessage, matchedSeries);
        }
        return new Result(ctx, classification);
    }

    // ---- BRAND 级：品牌问句 ----

    private String assembleBrandContext(QueryClassification classification) {
        List<String> seriesKeys = new ArrayList<>(new LinkedHashSet<>(classification.seriesKeys()));
        if (seriesKeys.isEmpty()) return null;

        // 排序：询问热度为主，并列/全零时用门店销量全局求和兜底（冷启动首日即有合理推荐）
        Map<String, Long> salesBySeriesName = loadGlobalSales();
        Map<String, Double> heat = new HashMap<>();
        for (String key : seriesKeys) heat.put(key, askCountTracker.getWeightedHeat(key));
        seriesKeys.sort((a, b) -> {
            int byHeat = Double.compare(heat.get(b), heat.get(a));
            if (byHeat != 0) return byHeat;
            return Long.compare(salesOf(salesBySeriesName, b), salesOf(salesBySeriesName, a));
        });

        String top1Series = seriesKeys.get(0);
        String top1Name = top1Series.contains("-")
                ? top1Series.substring(top1Series.indexOf('-') + 1) : top1Series;

        return "## 品牌咨询（" + classification.brand() + "）\n"
                + "- 在售车系：" + seriesKeys.size() + " 个\n"
                + "- 近期最热门：" + top1Name + "\n"
                + "\n## 级别指令\n" + BRAND_INSTRUCTION;
    }

    /** 门店×车系销量表按车系全局求和（热度并列时的兜底排序数据源） */
    private Map<String, Long> loadGlobalSales() {
        Map<String, Long> sales = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT series_name, SUM(sale_count) AS total FROM store_car_hot GROUP BY series_name");
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.get("series_name"));
                Number total = (Number) row.get("total");
                if (name != null && total != null) sales.put(name, total.longValue());
            }
        } catch (Exception e) {
            // 销量表不可用时静默降级为纯热度排序
        }
        return sales;
    }

    private static long salesOf(Map<String, Long> salesBySeriesName, String seriesKey) {
        String series = seriesKey.contains("-")
                ? seriesKey.substring(seriesKey.indexOf('-') + 1) : seriesKey;
        return salesBySeriesName.getOrDefault(series, 0L);
    }

    // ---- FAMILY 级：同品牌多车系 ----

    private String assembleFamilyContext(QueryClassification classification) {
        // 按 series_id 确定性取命中车系的父块（不走混合检索——BM25 路无过滤会混入噪音）
        List<Document> parents = new ArrayList<>();
        for (String sid : classification.seriesKeys()) {
            parents.addAll(fetchParentsBySeriesId(sid));
        }
        if (parents.isEmpty()) return null;

        // 截断"在售款型"段，避免车型清单被倒出；锚点未命中时 fail-safe 保留全文（宁长勿错）
        List<Document> truncated = parents.stream()
                .map(p -> new Document(truncateModelsSection(p.getText()), p.getMetadata()))
                .toList();
        return buildContext(truncated, List.of(), List.of())
                + "\n## 级别指令\n" + FAMILY_INSTRUCTION;
    }

    /** 截断父块文本中 {@link SeriesParentBuilder#MODELS_SECTION} 及其后的车型清单段 */
    static String truncateModelsSection(String parentText) {
        int anchor = parentText.indexOf(SeriesParentBuilder.MODELS_SECTION);
        if (anchor < 0) return parentText;
        return parentText.substring(0, anchor);
    }

    // ---- SERIES 级：单一车系 ----

    private String assembleSeriesContext(QueryClassification classification) {
        String seriesId = classification.seriesKeys().get(0);
        List<Document> parents = fetchParentsBySeriesId(seriesId);
        if (parents.isEmpty()) return null;
        return buildContext(parents, List.of(), List.of())
                + "\n## 级别指令\n" + SERIES_INSTRUCTION;
    }

    /** 按 series_id 确定性取父块（不走混合检索——其 BM25 路无过滤，会混入无元数据文档） */
    private List<Document> fetchParentsBySeriesId(String seriesId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression parentFilter = b.and(
                b.eq("type", "车源"),
                b.and(b.eq("level", "parent"), b.eq("series_id", seriesId))
        ).build();
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(seriesId).topK(1).filterExpression(parentFilter).build());
    }

    // ---- UNRESTRICTED：现状三层检索 ----

    private String assembleUnrestrictedContext(String userMessage, List<ResolvedEntity> matchedSeries) {
        // EntityResolver 命中的车系（别名匹配）
        Set<String> entitySeries = matchedSeries.stream()
                .map(ResolvedEntity::seriesKey).collect(Collectors.toSet());

        // ---- 阶段一：父块相似度召回（混合检索：BM25 + BGE-M3），阈值 0.6 ----
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression parentOnly = b.and(
                b.eq("type", "车源"), b.eq("level", "parent")).build();

        List<Document> parentDocs = hybridRetriever.search(
                userMessage, 3, SERIES_CONFIDENCE, parentOnly);

        Set<String> hitSeries = new LinkedHashSet<>();
        for (Document p : parentDocs) {
            String sid = metaStr(p, "series_id");
            if (sid != null && !sid.isEmpty()) hitSeries.add(sid);
        }
        hitSeries.addAll(entitySeries);

        // ---- 回退路径：父块相似度 + EntityResolver 都没命中任何车系 ----
        if (hitSeries.isEmpty()) {
            return fallbackContext(userMessage);
        }

        // ---- 主路径：命中车系 ----

        // 补充 EntityResolver 命中但父块相似度没搜到的父块
        for (String sid : entitySeries) {
            boolean alreadyIn = parentDocs.stream()
                    .anyMatch(d -> sid.equals(metaStr(d, "series_id")));
            if (!alreadyIn) {
                parentDocs.addAll(fetchParentsBySeriesId(sid));
            }
        }

        // ---- 阶段二：命中车系子块全量展开（决策：全量，不截断） ----
        List<Document> childDocs = new ArrayList<>();
        Set<Object> seenSkuIds = new HashSet<>();
        FilterExpressionBuilder cb = new FilterExpressionBuilder();
        childLoop:
        for (String sid : hitSeries) {
            Filter.Expression cf = cb.and(
                    cb.eq("type", "车源"),
                    cb.and(cb.eq("level", "child"), cb.eq("parent_series_id", sid))
            ).build();
            for (Document c : vectorStore.similaritySearch(
                    SearchRequest.builder().query(sid).topK(20).filterExpression(cf).build())) {
                if (childDocs.size() >= CHILD_DOCS_TOTAL_LIMIT) break childLoop;
                Object skuId = c.getMetadata().get("sku_id");
                if (skuId != null && seenSkuIds.add(skuId)) childDocs.add(c);
            }
        }

        // 有车系锚点时跳过百科/话术（决策：避免通用知识与具体车系混淆）
        return buildContext(parentDocs, childDocs, Collections.emptyList());
    }

    /** 回退路径：子块泛检索 + 百科/话术补充（无车系锚点）。 */
    private String fallbackContext(String userMessage) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression childOnly = b.and(
                b.eq("type", "车源"), b.eq("level", "child")).build();
        List<Document> childDocs = hybridRetriever.search(
                userMessage, RAG_TOPK, RAG_THRESHOLD, childOnly);

        Filter.Expression nonCar = new FilterExpressionBuilder().ne("type", "车源").build();
        List<Document> knowledgeDocs = hybridRetriever.search(
                userMessage, 3, RAG_THRESHOLD, nonCar);

        return buildContext(Collections.emptyList(), childDocs, knowledgeDocs);
    }

    /** 组装上下文 */
    String buildContext(List<Document> parentDocs, List<Document> childDocs,
                        List<Document> knowledgeDocs) {
        StringBuilder ctx = new StringBuilder();
        if (!parentDocs.isEmpty()) {
            ctx.append("## 匹配车系\n");
            for (Document d : parentDocs) ctx.append(d.getText()).append("\n");
        }
        if (!childDocs.isEmpty()) {
            ctx.append("\n## 在售车型\n");
            for (Document d : childDocs) ctx.append("- ").append(d.getText()).append("\n");
        }
        if (!knowledgeDocs.isEmpty()) {
            ctx.append("\n## 相关知识\n");
            for (Document d : knowledgeDocs) {
                ctx.append("- [").append(metaStr(d, "type") != null ? metaStr(d, "type") : "?")
                   .append("] ").append(d.getText()).append("\n");
            }
        }
        return ctx.toString();
    }

    /** 安全读取 metadata 字符串值 */
    private static String metaStr(Document d, String key) {
        Object v = d.getMetadata().get(key);
        return v != null ? v.toString() : null;
    }
}