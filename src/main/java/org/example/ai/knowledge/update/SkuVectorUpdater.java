package org.example.ai.knowledge.update;

import org.example.ai.knowledge.facts.AtomicFact;
import org.example.ai.knowledge.facts.SeriesParentBuilder;
import org.example.ai.knowledge.facts.SkuFactExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SKU 向量同步更新器（#3 决策9）。
 *
 * 处理单条 SKU 的增/删/改 → Chroma 同步直写 + 级联父块检查。
 * 不攒批，每条消息独立处理。
 */
@Component
public class SkuVectorUpdater implements SkuChangeListener {

    private static final Logger log = LoggerFactory.getLogger(SkuVectorUpdater.class);

    public static final String DOC_TYPE = "车源";

    private final VectorStore vectorStore;
    private final SkuFactExtractor skuFactExtractor;
    private final SeriesParentBuilder parentBuilder;
    private final JdbcTemplate jdbc;
    private final InMemoryIndexRefresher indexRefresher;

    public SkuVectorUpdater(VectorStore vectorStore,
                            SkuFactExtractor skuFactExtractor,
                            SeriesParentBuilder parentBuilder,
                            JdbcTemplate jdbc,
                            InMemoryIndexRefresher indexRefresher) {
        this.vectorStore = vectorStore;
        this.skuFactExtractor = skuFactExtractor;
        this.parentBuilder = parentBuilder;
        this.jdbc = jdbc;
        this.indexRefresher = indexRefresher;
    }

    // ---- SkuChangeListener 接口实现 ----

    @Override
    public void onSkuChanged(SkuChangeEvent event) {
        log.info("SkuVectorUpdater: 收到变更事件 - {}", event);
        processChange(event);
        // 广播下半场：刷新问答侧内存知识结构（BM25/实体索引/关键词表），
        // 否则 Chroma 虽已更新，检索与路由仍用旧数据 → "必须重启才生效"
        indexRefresher.refreshAll();
    }

    // ---- 核心同步逻辑 ----

    /**
     * 处理单条 SKU 变更：删除旧向量 → 写入新向量 → 更新同步日志 → 级联父块检查。
     */
    public void processChange(SkuChangeEvent event) {
        Long skuId = event.getSkuId();
        switch (event.getType()) {
            case DELETE -> {
                deleteBySkuId(skuId);
                removeSyncLog(skuId);
            }
            case INSERT, UPDATE -> {
                Map<String, Object> row = event.getChangedFields();
                if (row == null || row.isEmpty()) {
                    // 变更字段为空时，从数据库加载完整行
                    row = loadSkuRow(skuId);
                }
                if (row == null || row.isEmpty()) {
                    log.warn("SkuVectorUpdater: 未找到 SKU {} 的数据，跳过", skuId);
                    return;
                }
                deleteBySkuId(skuId); // 先删旧向量
                upsertFromRow(row);    // 再写新向量
            }
        }

        // 级联父块检查
        String brand = null;
        String series = null;
        if (event.getChangedFields() != null) {
            brand = String.valueOf(event.getChangedFields().getOrDefault("brand_name", ""));
            series = String.valueOf(event.getChangedFields().getOrDefault("series_name", ""));
        }
        if ((brand == null || brand.isBlank() || "null".equals(brand))
                || (series == null || series.isBlank() || "null".equals(series))) {
            // 从 DB 加载 brand+series
            Map<String, Object> row = loadSkuRow(skuId);
            if (row != null && !row.isEmpty()) {
                brand = String.valueOf(row.getOrDefault("brand_name", ""));
                series = String.valueOf(row.getOrDefault("series_name", ""));
            }
        }
        if (brand != null && !brand.isBlank() && !"null".equals(brand)
                && series != null && !series.isBlank() && !"null".equals(series)) {
            checkAndRebuildParent(brand, series);
        }
    }

    /**
     * 按 sku_id 删除 Chroma 中的子块向量。
     */
    public void deleteBySkuId(Long skuId) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression expr = b.and(
                    b.eq("type", DOC_TYPE),
                    b.and(b.eq("level", "child"), b.eq("sku_id", skuId))
            ).build();
            vectorStore.delete(expr);
            log.debug("SkuVectorUpdater: 已删除 sku_id={} 的旧向量", skuId);
        } catch (Exception e) {
            log.warn("SkuVectorUpdater: 删除 sku_id={} 的旧向量失败: {}", skuId, e.getMessage());
        }
    }

    /**
     * 从数据库行渲染子块 → 写入 Chroma → 更新 vector_sync_log。
     */
    public void upsertFromRow(Map<String, Object> row) {
        AtomicFact fact = skuFactExtractor.toChildFact(row);
        Document doc = toSpringAiDoc(fact);
        vectorStore.add(List.of(doc));

        // 更新同步日志
        long skuId = ((Number) row.get("id")).longValue();
        String hash = fact.getSourceHash();
        jdbc.update("""
            INSERT INTO vector_sync_log (sku_id, source_hash, last_synced_at)
            VALUES (?, ?, datetime('now','localtime'))
            ON CONFLICT(sku_id) DO UPDATE SET source_hash = excluded.source_hash, last_synced_at = excluded.last_synced_at
        """, skuId, hash);

        log.info("SkuVectorUpdater: 已同步 sku_id={}, hash={}", skuId, hash.substring(0, 8));
    }

    /**
     * 级联父块检查：对比父块内容哈希，有变化则删除旧父块 + 写入新父块。
     */
    public void checkAndRebuildParent(String brand, String series) {
        // 先查旧父块哈希（从 Chroma metadata）
        String parentEntityId = SkuFactExtractor.buildEntityId(brand, series);
        String oldHash = null;
        try {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            Filter.Expression parentFilter = fb.and(
                    fb.eq("type", DOC_TYPE),
                    fb.and(fb.eq("level", "parent"), fb.eq("series_id", brand + "-" + series))
            ).build();
            List<Document> oldDocs = vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(brand + " " + series + " 车系信息")
                            .topK(1)
                            .filterExpression(parentFilter)
                            .build());
            if (!oldDocs.isEmpty()) {
                oldHash = String.valueOf(oldDocs.get(0).getMetadata().getOrDefault("source_hash", ""));
            }
        } catch (Exception e) {
            log.debug("SkuVectorUpdater: 查询旧父块失败（可能不存在）: {}", e.getMessage());
        }

        // 构建新父块
        AtomicFact newParent = parentBuilder.buildParent(brand, series);
        if (newParent == null) {
            // 车系无在售车源，删除旧父块
            if (oldHash != null) {
                deleteParentVector(brand, series);
                log.info("SkuVectorUpdater: {}-{} 无在售车源，已删除父块", brand, series);
            }
            return;
        }

        String newHash = newParent.getSourceHash();
        if (oldHash != null && oldHash.equals(newHash)) {
            log.debug("SkuVectorUpdater: {}-{} 父块无变化，跳过重建", brand, series);
            return;
        }

        // 删除旧父块 + 写入新父块
        deleteParentVector(brand, series);
        vectorStore.add(List.of(toSpringAiDoc(newParent)));
        log.info("SkuVectorUpdater: 已重建父块 {}-{}, hash={}", brand, series, newHash.substring(0, 8));
    }

    /**
     * 启动时 / 手动触发：对比 car_sku 全量行哈希 vs vector_sync_log，只重建变化行。
     *
     * @return 同步统计：{changed: N, deleted: N, total: N}
     */
    public Map<String, Integer> syncChangedSkus() {
        log.info("SkuVectorUpdater: 开始哈希增量对比...");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 ORDER BY id");

        int changed = 0, unchanged = 0;
        List<Long> currentSkuIds = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            long skuId = ((Number) row.get("id")).longValue();
            currentSkuIds.add(skuId);

            AtomicFact fact = skuFactExtractor.toChildFact(row);
            String currentHash = fact.getSourceHash();

            // 查询上次同步的哈希
            List<String> hashes = jdbc.queryForList(
                    "SELECT source_hash FROM vector_sync_log WHERE sku_id = ?",
                    String.class, skuId);
            String storedHash = hashes.isEmpty() ? null : hashes.get(0);

            if (storedHash == null || !storedHash.equals(currentHash)) {
                deleteBySkuId(skuId);
                upsertFromRow(row);
                changed++;
            } else {
                unchanged++;
            }
        }

        // 清理已删除的 SKU 对应的向量和日志
        int deleted = cleanupStaleEntries(currentSkuIds);

        // 重建所有父块（子块变更后父块可能也需要更新）
        List<Map<String, Object>> seriesList = jdbc.queryForList(
                "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE sale_status = 1 AND is_deleted = 0");
        int parentsBuilt = 0;
        for (Map<String, Object> s : seriesList) {
            checkAndRebuildParent(
                    String.valueOf(s.get("brand_name")),
                    String.valueOf(s.get("series_name")));
            parentsBuilt++;
        }

        log.info("SkuVectorUpdater: 增量同步完成 — 变更 {} 条, 未变 {} 条, 清理 {} 条, 父块 {} 个",
                changed, unchanged, deleted, parentsBuilt);

        // 全量同步后同样刷新内存知识结构（覆盖手动 /api/kb/sku/refresh 全量模式；
        // 启动时 CarSkuVectorIndexer 走此路径，顺带修正启动顺序导致的旧数据索引）
        indexRefresher.refreshAll();

        return Map.of("changed", changed, "unchanged", unchanged,
                "deleted", deleted, "parentsBuilt", parentsBuilt);
    }

    // ---- 内部辅助 ----

    private int cleanupStaleEntries(List<Long> currentSkuIds) {
        if (currentSkuIds.isEmpty()) return 0;

        // 查询 vector_sync_log 中不在当前有效 SKU 列表中的记录
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < currentSkuIds.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        List<Long> staleIds = jdbc.queryForList(
                "SELECT sku_id FROM vector_sync_log WHERE sku_id NOT IN (" + placeholders + ")",
                Long.class, currentSkuIds.toArray());

        for (Long staleId : staleIds) {
            deleteBySkuId(staleId);
            removeSyncLog(staleId);
        }

        return staleIds.size();
    }

    private void deleteParentVector(String brand, String series) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression expr = b.and(
                    b.eq("type", DOC_TYPE),
                    b.and(b.eq("level", "parent"), b.eq("series_id", brand + "-" + series))
            ).build();
            vectorStore.delete(expr);
        } catch (Exception e) {
            log.debug("SkuVectorUpdater: 删除父块 {}-{} 失败（可能不存在）: {}", brand, series, e.getMessage());
        }
    }

    private void removeSyncLog(Long skuId) {
        jdbc.update("DELETE FROM vector_sync_log WHERE sku_id = ?", skuId);
    }

    private Map<String, Object> loadSkuRow(Long skuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE id = ?", skuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Document toSpringAiDoc(AtomicFact fact) {
        Document doc = new Document(fact.getContent());
        doc.getMetadata().putAll(fact.getMetadata());
        doc.getMetadata().put("fact_id", fact.getFactId());
        doc.getMetadata().put("entity_id", fact.getEntityId());
        doc.getMetadata().put("temporal_type", fact.getTemporalType().name());
        doc.getMetadata().put("source_doc", fact.getSourceDoc());
        doc.getMetadata().put("source_hash", fact.getSourceHash());
        return doc;
    }
}