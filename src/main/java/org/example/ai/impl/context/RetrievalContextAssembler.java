package org.example.ai.impl.context;

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
 * 检索上下文组装器 —— 从 CarSalesAgent 迁出（#23 ticket，预重构）。
 *
 * <p>三层检索（#4 ticket，2026-07-29）：</p>
 *
 * <p>主路径（命中车系）：
 *   阶段一 — 父块相似度召回（车系级语义匹配，阈值 0.6，topK=3）
 *   阶段二 — 命中车系子块全量展开（topK=20）
 *   不注入百科/话术（有车系锚点时通用知识是噪音）</p>
 *
 * <p>回退路径（未命中车系）：
 *   子块泛检索（topK=5）+ 百科/话术补充（topK=3）</p>
 *
 * <p>后续分层检索（#21 spec）将在此组件内按查询粒度分支注入不同详细程度的上下文。</p>
 */
@Component
public class RetrievalContextAssembler {

    private static final int RAG_TOPK = 5;
    private static final double RAG_THRESHOLD = 0.5;
    private static final double SERIES_CONFIDENCE = 0.6;

    private final HybridRetriever hybridRetriever;
    private final VectorStore vectorStore;

    public RetrievalContextAssembler(HybridRetriever hybridRetriever, VectorStore vectorStore) {
        this.hybridRetriever = hybridRetriever;
        this.vectorStore = vectorStore;
    }

    public String retrieveContext(String userMessage, List<ResolvedEntity> matchedSeries) {
        // EntityResolver 命中的车系（别名匹配）
        Set<String> entitySeries = matchedSeries.stream()
                .map(ResolvedEntity::seriesKey).collect(Collectors.toSet());

        // ---- 阶段一：父块相似度召回（混合检索：BM25 + BGE-M3），阈值 0.6 ----
        Filter.Expression parentOnly = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("type", "车源"),
                new FilterExpressionBuilder().eq("level", "parent")
        ).build();

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
            Filter.Expression childOnly = new FilterExpressionBuilder().and(
                    new FilterExpressionBuilder().eq("type", "车源"),
                    new FilterExpressionBuilder().eq("level", "child")
            ).build();
            List<Document> childDocs = hybridRetriever.search(
                    userMessage, RAG_TOPK, RAG_THRESHOLD, childOnly);

            Filter.Expression nonCar = new FilterExpressionBuilder().ne("type", "车源").build();
            List<Document> knowledgeDocs = hybridRetriever.search(
                    userMessage, 3, RAG_THRESHOLD, nonCar);

            return buildContext(Collections.emptyList(), childDocs, knowledgeDocs);
        }

        // ---- 主路径：命中车系 ----

        // 补充 EntityResolver 命中但父块相似度没搜到的父块
        for (String sid : entitySeries) {
            boolean alreadyIn = parentDocs.stream()
                    .anyMatch(d -> sid.equals(metaStr(d, "series_id")));
            if (!alreadyIn) {
                Filter.Expression pf = new FilterExpressionBuilder().and(
                        new FilterExpressionBuilder().eq("type", "车源"),
                        new FilterExpressionBuilder().and(
                                new FilterExpressionBuilder().eq("level", "parent"),
                                new FilterExpressionBuilder().eq("series_id", sid))
                ).build();
                parentDocs.addAll(vectorStore.similaritySearch(
                        SearchRequest.builder().query(sid).topK(1).filterExpression(pf).build()));
            }
        }

        // ---- 阶段二：命中车系子块全量展开（决策：全量，不截断） ----
        List<Document> childDocs = new ArrayList<>();
        Set<Object> seenSkuIds = new HashSet<>();
        for (String sid : hitSeries) {
            Filter.Expression cf = new FilterExpressionBuilder().and(
                    new FilterExpressionBuilder().eq("type", "车源"),
                    new FilterExpressionBuilder().and(
                            new FilterExpressionBuilder().eq("level", "child"),
                            new FilterExpressionBuilder().eq("parent_series_id", sid))
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