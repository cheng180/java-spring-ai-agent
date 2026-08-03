package org.example.ai.knowledge;

import org.example.ai.knowledge.facts.AtomicFact;
import org.example.ai.knowledge.facts.LlmFactExtractor;
import org.example.ai.knowledge.facts.SkuFactExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 知识库初始化器 —— 启动时把 knowledge/ 下的语料写入 Chroma 向量库。
 *
 * 分块：.md/.txt → LlmFactExtractor LLM 命题提取为原子事实。
 *
 * 增量模式（#6 ticket）：
 * - 首次启动（集合为空）→ 全量重建
 * - 后续启动 → 每个文档计算内容 SHA-256 → 对比 doc_sync_log → 只重建变化的文档
 * - 新增文档自动入库；删除的文档自动清理向量
 *
 * @Order(2)：先于 CarSkuVectorIndexer(3) 执行
 */
@Component
@Order(2)
public class KnowledgeBaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private static final String ENCYCLOPEDIA_ENTITY = "entity:doc:encyclopedia";
    private static final String SCRIPT_ENTITY = "entity:doc:script";
    private static final String ENCYCLOPEDIA_FILE = "汽车百科知识库.md";

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourceResolver;
    private final LlmFactExtractor llmExtractor;
    private final JdbcTemplate jdbc;

    public KnowledgeBaseInitializer(VectorStore vectorStore,
                                    ResourcePatternResolver resourceResolver,
                                    LlmFactExtractor llmExtractor,
                                    JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.resourceResolver = resourceResolver;
        this.llmExtractor = llmExtractor;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws IOException {
        refreshDocs();
    }

    /**
     * 手动触发文档增量同步（#6 ticket）。
     * 启动时自动调用，也可通过 API 手动触发。
     *
     * @return 同步结果摘要，如 "full-rebuild" 或 "synced=2,skipped=0,deleted=1"
     */
    public String refreshDocs() throws IOException {
        log.info("=== 开始增量同步知识库 ===");

        // Collection 为空 → 首次全量重建
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query("汽车").topK(1).build());
        if (existing.isEmpty()) {
            fullRebuild();
            return "full-rebuild";
        }

        // 增量同步
        String result = syncIncremental();
        log.info("=== 知识库增量同步完成：{} ===", result);
        return result;
    }

    // ---- 首次全量重建 ----

    private void fullRebuild() throws IOException {
        log.info("向量库为空，执行首次全量构建");
        List<Document> docs = new ArrayList<>();
        for (Resource f : allKnowledgeFiles()) {
            processFileFull(f, docs);
        }
        if (docs.isEmpty()) {
            log.warn("没有解析出知识块");
            return;
        }
        vectorStore.add(docs);
        log.info("知识库全量构建完成，共 {} 条原子事实", docs.size());
    }

    private void processFileFull(Resource file, List<Document> docs) {
        String filename = file.getFilename();
        String entityId = ENCYCLOPEDIA_FILE.equals(filename)
                ? ENCYCLOPEDIA_ENTITY : SCRIPT_ENTITY;
        List<AtomicFact> facts = llmExtractor.extract(file, filename, entityId);
        for (AtomicFact fact : facts) {
            docs.add(toSpringAiDoc(fact, filename));
        }
        // 首次记录哈希
        try {
            String content = file.getContentAsString(StandardCharsets.UTF_8);
            upsertDocSyncLog(filename, SkuFactExtractor.sha256(content));
        } catch (IOException e) {
            log.warn("首次记录哈希失败：{}", filename);
        }
        log.info("{} → {} 条原子事实", filename, facts.size());
    }

    // ---- 增量同步 ----

    private String syncIncremental() throws IOException {
        Set<String> seen = new HashSet<>();
        int synced = 0, skipped = 0;

        for (Resource file : allKnowledgeFiles()) {
            String filename = file.getFilename();
            String currentHash = SkuFactExtractor.sha256(
                    file.getContentAsString(StandardCharsets.UTF_8));
            String storedHash = queryStoredHash(filename);

            if (currentHash.equals(storedHash)) {
                log.info("  {} — 未变更，跳过", filename);
                skipped++;
                seen.add(filename);
                continue;
            }

            deleteBySource(filename);
            String entityId = ENCYCLOPEDIA_FILE.equals(filename)
                    ? ENCYCLOPEDIA_ENTITY : SCRIPT_ENTITY;
            List<AtomicFact> facts = llmExtractor.extract(file, filename, entityId);
            if (!facts.isEmpty()) {
                List<Document> docs = new ArrayList<>();
                for (AtomicFact fact : facts) docs.add(toSpringAiDoc(fact, filename));
                vectorStore.add(docs);
                log.info("  {} → {} 条事实（hash:{})", filename, facts.size(),
                        currentHash.substring(0, 8));
            }
            upsertDocSyncLog(filename, currentHash);
            synced++;
            seen.add(filename);
        }

        // 清理已删除文档
        int deleted = 0;
        for (String source : getAllTrackedSources()) {
            if (!seen.contains(source)) {
                deleteBySource(source);
                removeDocSyncLog(source);
                log.info("  已清理已删除文档：{}", source);
                deleted++;
            }
        }

        return String.format("synced=%d,skipped=%d,deleted=%d", synced, skipped, deleted);
    }

    // ---- 数据库操作 ----

    private String queryStoredHash(String source) {
        List<String> hashes = jdbc.queryForList(
                "SELECT source_hash FROM doc_sync_log WHERE source = ?",
                String.class, source);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    private void upsertDocSyncLog(String source, String hash) {
        jdbc.update("""
            INSERT INTO doc_sync_log (source, source_hash, last_synced_at)
            VALUES (?, ?, datetime('now','localtime'))
            ON CONFLICT(source) DO UPDATE SET source_hash = excluded.source_hash,
            last_synced_at = excluded.last_synced_at
        """, source, hash);
    }

    private void removeDocSyncLog(String source) {
        jdbc.update("DELETE FROM doc_sync_log WHERE source = ?", source);
    }

    private List<String> getAllTrackedSources() {
        return jdbc.queryForList("SELECT source FROM doc_sync_log", String.class);
    }

    // ---- 向量库操作 ----

    private void deleteBySource(String filename) {
        try {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            Filter.Expression expr = fb.eq("source", filename).build();
            vectorStore.delete(expr);
        } catch (Exception e) {
            log.debug("删除文档 {} 旧向量失败（可能不存在）: {}", filename, e.getMessage());
        }
    }

    // ---- 共享 ----

    private Resource[] allKnowledgeFiles() throws IOException {
        Resource[] md = resourceResolver.getResources("classpath:knowledge/*.md");
        Resource[] txt = resourceResolver.getResources("classpath:knowledge/*.txt");
        Resource[] all = new Resource[md.length + txt.length];
        System.arraycopy(md, 0, all, 0, md.length);
        System.arraycopy(txt, 0, all, md.length, txt.length);
        return all;
    }

    private Document toSpringAiDoc(AtomicFact fact, String filename) {
        Document doc = new Document(fact.getContent());
        doc.getMetadata().put("source", filename);
        doc.getMetadata().put("type", ENCYCLOPEDIA_FILE.equals(filename) ? "百科" : "话术");
        doc.getMetadata().put("fact_id", fact.getFactId());
        doc.getMetadata().put("entity_id", fact.getEntityId());
        doc.getMetadata().put("temporal_type", fact.getTemporalType().name());
        doc.getMetadata().put("source_hash", fact.getSourceHash());
        return doc;
    }
}
