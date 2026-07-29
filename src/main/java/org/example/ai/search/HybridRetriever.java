package org.example.ai.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合检索引擎 —— BM25 关键词检索 + BGE-M3 语义检索并行执行，RRF 融合排序。
 *
 * 用法：
 * <pre>
 *   List<Document> results = hybridRetriever.search(query, topK);
 *   List<Document> results = hybridRetriever.search(query, topK, threshold);
 *   List<Document> results = hybridRetriever.search(query, topK, threshold, filterExpression);
 * </pre>
 *
 * RRF 公式：RRF_score(d) = Σ 1/(k + rank_i(d))，k=60。
 * 两路结果通过文本指纹去重后融合排名。
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** RRF 平滑常数 */
    private static final double RRF_K = 60.0;

    /** 并行检索超时（秒） */
    private static final long TIMEOUT_SECONDS = 10;

    /** BM25 候选数倍率（取 topK×2 增加召回） */
    private static final int CANDIDATE_MULTIPLIER = 2;

    private final Bm25Indexer bm25Indexer;
    private final VectorStore vectorStore;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public HybridRetriever(Bm25Indexer bm25Indexer, VectorStore vectorStore) {
        this.bm25Indexer = bm25Indexer;
        this.vectorStore = vectorStore;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * 混合检索（无阈值限制，无过滤条件）。
     */
    public List<Document> search(String query, int topK) {
        return search(query, topK, 0.0, null);
    }

    /**
     * 混合检索（带相似度阈值，无过滤条件）。
     */
    public List<Document> search(String query, int topK, double similarityThreshold) {
        return search(query, topK, similarityThreshold, null);
    }

    /**
     * 混合检索（带相似度阈值和元数据过滤条件）。
     *
     * @param query              查询文本
     * @param topK               最终返回结果数
     * @param similarityThreshold BGE-M3 相似度阈值（低于此值的向量结果丢弃）
     * @param filterExpression   元数据过滤（Chroma where 条件），null 表示不过滤
     * @return RRF 融合后的 topK 结果
     */
    public List<Document> search(String query, int topK, double similarityThreshold,
                                  Filter.Expression filterExpression) {
        if (query == null || query.isBlank()) return List.of();

        int candidateK = topK * CANDIDATE_MULTIPLIER;

        // ---- 并行执行 BM25 + BGE-M3 ----
        CompletableFuture<List<RankedDoc>> bm25Future = CompletableFuture.supplyAsync(
                () -> runBm25(query, candidateK), executor);

        CompletableFuture<List<RankedDoc>> bgeFuture = CompletableFuture.supplyAsync(
                () -> runBge(query, candidateK, similarityThreshold, filterExpression), executor);

        List<RankedDoc> bm25Results;
        List<RankedDoc> bgeResults;
        try {
            bm25Results = bm25Future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("BM25 检索超时或失败: {}", e.getMessage());
            bm25Results = List.of();
        }
        try {
            bgeResults = bgeFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("BGE-M3 检索超时或失败: {}", e.getMessage());
            bgeResults = List.of();
        }

        // ---- RRF 融合 + 去重 ----
        List<RankedDoc> fused = rrfFuse(bm25Results, bgeResults, topK);

        // 转换回 Spring AI Document（BM25 结果只有文本，直接构造 Document）
        return fused.stream()
                .map(r -> {
                    if (r.source() instanceof Document doc) return doc;
                    // BM25 ScoredDocument → Spring AI Document
                    return new Document(r.text());
                })
                .collect(Collectors.toList());
    }

    // ---- 内部检索方法 ----

    private List<RankedDoc> runBm25(String query, int topK) {
        Bm25Index index = bm25Indexer.getIndex();
        if (!bm25Indexer.isReady() || index.isEmpty()) return List.of();

        List<Bm25Index.ScoredDocument> results = index.search(query, topK);
        List<RankedDoc> ranked = new ArrayList<>();
        for (Bm25Index.ScoredDocument r : results) {
            ranked.add(new RankedDoc(r.id(), r.text(), r.score(), null));
        }
        return ranked;
    }

    private List<RankedDoc> runBge(String query, int topK, double threshold,
                                    Filter.Expression filter) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);
            if (threshold > 0) {
                builder.similarityThreshold(threshold);
            }
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> results = vectorStore.similaritySearch(builder.build());
            List<RankedDoc> ranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                // BGE-M3 相似度分数（Spring AI 2.0 中从 metadata 取）
                double score = getSimilarityScore(doc, i, results.size());
                ranked.add(new RankedDoc(doc.getId(), doc.getText(), score, doc));
            }
            return ranked;
        } catch (Exception e) {
            log.warn("BGE-M3 检索异常: {}", e.getMessage());
            return List.of();
        }
    }

    /** 从 Document metadata 提取相似度分数，无分数时用排名倒数估算 */
    private double getSimilarityScore(Document doc, int rank, int total) {
        Object score = doc.getMetadata().get("similarity");
        if (score instanceof Number n) return n.doubleValue();
        score = doc.getMetadata().get("distance");
        if (score instanceof Number n) return 1.0 / (1.0 + n.doubleValue());
        // 无分数时：用排名位置估算（rank 0 = 最高 = 1.0, rank N = 最低 = 1/(N+1)）
        return 1.0 - (double) rank / (total + 1);
    }

    // ---- RRF 融合 ----

    /**
     * RRF (Reciprocal Rank Fusion) 融合两路检索结果。
     *
     * 1. 按文本指纹去重（同一文档在两路中都出现 → 累加 RRF 分数）
     * 2. 按 RRF 分数降序排列
     * 3. 返回 topK
     */
    List<RankedDoc> rrfFuse(List<RankedDoc> listA, List<RankedDoc> listB, int topK) {
        // fingerprint → RRF score accumulator + best doc
        Map<String, RrfAccumulator> fused = new LinkedHashMap<>();

        // 第一路
        for (int i = 0; i < listA.size(); i++) {
            RankedDoc rd = listA.get(i);
            String fp = fingerprint(rd.text());
            double rrf = 1.0 / (RRF_K + i + 1); // rank 从 1 开始
            fused.compute(fp, (k, v) -> {
                if (v == null) return new RrfAccumulator(rd, rrf);
                v.addScore(rrf);
                if (rd.source() instanceof Document) v.best = rd; // 优先保留有 Document 的
                return v;
            });
        }

        // 第二路
        for (int i = 0; i < listB.size(); i++) {
            RankedDoc rd = listB.get(i);
            String fp = fingerprint(rd.text());
            double rrf = 1.0 / (RRF_K + i + 1);
            fused.compute(fp, (k, v) -> {
                if (v == null) return new RrfAccumulator(rd, rrf);
                v.addScore(rrf);
                if (rd.source() instanceof Document) v.best = rd; // 优先保留有 Document 的
                return v;
            });
        }

        // 按 RRF 分数降序，取 topK
        return fused.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore, a.rrfScore))
                .limit(topK)
                .map(a -> new RankedDoc(a.best.id(), a.best.text(), a.rrfScore, a.best.source()))
                .collect(Collectors.toList());
    }

    /** 文本指纹（SHA-256 前 16 位，用于跨路去重） */
    static String fingerprint(String text) {
        if (text == null || text.isBlank()) return "empty";
        String sample = text.length() > 200 ? text.substring(0, 200) : text;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sample.hashCode());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---- 内部类型 ----

    /** RRF 融合累加器 */
    private static class RrfAccumulator {
        RankedDoc best;
        double rrfScore;
        RrfAccumulator(RankedDoc best, double score) { this.best = best; this.rrfScore = score; }
        void addScore(double s) { this.rrfScore += s; }
    }

    /**
     * 带排名信息的文档（内部使用）。
     */
    record RankedDoc(String id, String text, double score, Object source) {}
}
