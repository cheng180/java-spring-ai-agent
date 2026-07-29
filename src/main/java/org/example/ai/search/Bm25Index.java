package org.example.ai.search;

import java.util.*;

/**
 * 纯 Java 内存 BM25 关键词检索引擎。
 *
 * 中文分词策略：逐字 bigram + unigram，不依赖外部分词器。
 * BM25 参数：k1=1.2, b=0.75（标准值）。
 *
 * 线程安全：写操作（add/remove/clear）和读操作（search）之间不互斥，
 * 调用方负责在构建完成后不再并发写入。
 */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 文档存储：docId → 原始文本 */
    private final Map<String, String> documents = new LinkedHashMap<>();

    /** 词频：docId → (term → tf) */
    private final Map<String, Map<String, Integer>> termFreq = new HashMap<>();

    /** 文档频率：term → 包含该词的文档数 */
    private final Map<String, Integer> docFreq = new HashMap<>();

    /** 文档长度（词元数）：docId → length */
    private final Map<String, Integer> docLengths = new HashMap<>();

    /** 总文档数 */
    private int totalDocs = 0;

    /** 总词元数（用于计算平均文档长度） */
    private long totalTokens = 0;

    // ---- 公开 API ----

    /**
     * 向索引中添加一篇文档。
     * @param id   文档唯一标识
     * @param text 文档文本内容
     */
    public void addDocument(String id, String text) {
        if (id == null || text == null) return;
        removeDocument(id); // 幂等：先删旧的

        List<String> tokens = tokenize(text);
        documents.put(id, text);
        docLengths.put(id, tokens.size());
        totalDocs++;
        totalTokens += tokens.size();

        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        termFreq.put(id, tf);

        for (String t : tf.keySet()) {
            docFreq.merge(t, 1, Integer::sum);
        }
    }

    /**
     * 从索引中移除一篇文档。
     */
    public void removeDocument(String id) {
        if (!documents.containsKey(id)) return;

        documents.remove(id);
        Map<String, Integer> tf = termFreq.remove(id);
        Integer len = docLengths.remove(id);
        totalDocs--;
        if (len != null) totalTokens -= len;

        if (tf != null) {
            for (String t : tf.keySet()) {
                int df = docFreq.getOrDefault(t, 0);
                if (df <= 1) docFreq.remove(t);
                else docFreq.put(t, df - 1);
            }
        }
    }

    /**
     * BM25 关键词检索。
     *
     * @param query 查询文本
     * @param topK  返回结果数量上限
     * @return 按 BM25 分数降序排列的结果列表
     */
    public List<ScoredDocument> search(String query, int topK) {
        if (query == null || query.isBlank() || totalDocs == 0) return List.of();

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        double avgdl = totalDocs > 0 ? (double) totalTokens / totalDocs : 1.0;

        // 对每篇文档计算 BM25 分数
        PriorityQueue<ScoredDocument> heap = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredDocument::score));

        for (var entry : documents.entrySet()) {
            String docId = entry.getKey();
            String text = entry.getValue();
            int dl = docLengths.getOrDefault(docId, 1);
            Map<String, Integer> tf = termFreq.get(docId);

            double score = 0;
            if (tf != null) {
                for (String qt : queryTokens) {
                    int f = tf.getOrDefault(qt, 0);
                    if (f == 0) continue;
                    int df = docFreq.getOrDefault(qt, 1);
                    // BM25 公式
                    double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
                    double numerator = f * (K1 + 1);
                    double denominator = f + K1 * (1 - B + B * dl / avgdl);
                    score += idf * numerator / denominator;
                }
            }

            if (score > 0) {
                heap.offer(new ScoredDocument(docId, text, score));
                if (heap.size() > topK) heap.poll(); // 保留 topK
            }
        }

        // 降序排列
        List<ScoredDocument> results = new ArrayList<>(heap);
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    /** 清空索引 */
    public void clear() {
        documents.clear();
        termFreq.clear();
        docFreq.clear();
        docLengths.clear();
        totalDocs = 0;
        totalTokens = 0;
    }

    /** 索引中的文档数 */
    public int size() {
        return totalDocs;
    }

    /** 索引是否为空 */
    public boolean isEmpty() {
        return totalDocs == 0;
    }

    // ---- 分词 ----

    /**
     * 中文文本分词：bigram（相邻二字） + unigram（单字）混合。
     * 英文/数字/特殊字符保持原样。
     *
     * 示例："省油的车" → ["省油", "油的", "的车", "省", "油", "的", "车"]
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (isCjk(c)) {
                // 先把累积的英文/数字 buffer 输出
                if (buf.length() > 0) {
                    tokens.add(buf.toString().toLowerCase());
                    buf.setLength(0);
                }
                // unigram
                tokens.add(String.valueOf(c));
                // bigram
                if (i + 1 < text.length() && isCjk(text.charAt(i + 1))) {
                    tokens.add(text.substring(i, i + 2));
                }
            } else if (Character.isWhitespace(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString().toLowerCase());
                    buf.setLength(0);
                }
                // 跳过空白
            } else {
                // 英文、数字、标点等
                buf.append(c);
            }
        }

        // 末尾 buffer 输出
        if (buf.length() > 0) {
            tokens.add(buf.toString().toLowerCase());
        }

        return tokens;
    }

    /** 判断字符是否属于 CJK 统一表意文字区间 */
    private static boolean isCjk(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /**
     * BM25 检索结果。
     */
    public record ScoredDocument(String id, String text, double score) {
    }
}
