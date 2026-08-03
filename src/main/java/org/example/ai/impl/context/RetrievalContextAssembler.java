package org.example.ai.impl.context;

import org.example.ai.impl.routing.QueryClassification;
import org.example.ai.impl.routing.QueryLevel;
import org.example.ai.impl.routing.QueryLevelClassifier;
import org.example.ai.impl.search.HybridRetriever;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索上下文组装器 —— 从 CarSalesAgent 迁出（#23 ticket，预重构），
 * 分层检索分支（#24 ticket，#21 spec）。
 *
 * <p>先由 {@link QueryLevelClassifier} 判定查询粒度，再按级别注入不同详细程度的上下文：</p>
 * <ul>
 *   <li>SERIES — 单车系：只注入该车系完整父块 + 级别指令（零子块调用）</li>
 *   <li>BRAND/FAMILY — 后续 ticket 接入</li>
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

    /** SERIES 级级别指令：只讲命中的车系，末尾问是否深入了解 */
    static final String SERIES_INSTRUCTION =
            "用户聚焦单个车系。只介绍这个车系（不要提其他车系），可基于上面的车系信息回答；"
            + "末尾询问用户是否想深入了解该车系（如优点、亮点等）。";

    private final HybridRetriever hybridRetriever;
    private final VectorStore vectorStore;
    private final QueryLevelClassifier classifier;

    public RetrievalContextAssembler(HybridRetriever hybridRetriever, VectorStore vectorStore,
                                     QueryLevelClassifier classifier) {
        this.hybridRetriever = hybridRetriever;
        this.vectorStore = vectorStore;
        this.classifier = classifier;
    }

    public String retrieveContext(String userMessage, List<ResolvedEntity> matchedSeries) {
        // ---- 粒度分类（#24） ----
        QueryClassification classification = classifier.classify(userMessage, matchedSeries);

        if (classification.level() == QueryLevel.SERIES) {
            String ctx = assembleSeriesContext(classification);
            if (ctx != null) return ctx;
            // 命中车系的父块取不到（如下架车系实体残留）→ 降级回退泛检索，绝不注入空上下文
            return fallbackContext(userMessage);
        }

        // ---- UNRESTRICTED：现状行为（BRAND/FAMILY 分支后续 ticket 接入） ----
        return assembleUnrestrictedContext(userMessage, matchedSeries);
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
        for (String sid : hitSeries) {
            Filter.Expression cf = cb.and(
                    cb.eq("type", "车源"),
                    cb.and(cb.eq("level", "child"), cb.eq("parent_series_id", sid))
            ).build();
            for (Document c : vectorStore.similaritySearch(
                    SearchRequest.builder().query(sid).topK(20).filterExpression(cf).build())) {
                Object skuId = c.getMetadata().get("sku_id");
                if (skuId != null && seenSkuIds.add(skuId)) childDocs.add(c);
            }
        }

        // 有车系锚点时跳过百科/话术（决策：避免通用知识与具体车系混淆）
        return buildContext(parentDocs, childDocs, Collections.emptyList());
    }

    /** 回退路径：子块泛检索 + 百科/话术补充（无车系锚点） */
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