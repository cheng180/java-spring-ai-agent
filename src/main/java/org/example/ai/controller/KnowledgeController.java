package org.example.ai.controller;

import org.example.ai.knowledge.KnowledgeBaseInitializer;
import org.example.ai.knowledge.update.ChangeType;
import org.example.ai.knowledge.update.SkuChangeEvent;
import org.example.ai.knowledge.update.SkuVectorUpdater;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 知识库管理接口 —— 调试 + 刷新。
 *
 * <p>仅运维使用，不对 macan 暴露。</p>
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final VectorStore vectorStore;
    private final SkuVectorUpdater updater;
    private final KnowledgeBaseInitializer docInitializer;
    private final JdbcTemplate jdbc;

    public KnowledgeController(VectorStore vectorStore,
                               SkuVectorUpdater updater,
                               KnowledgeBaseInitializer docInitializer,
                               JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.updater = updater;
        this.docInitializer = docInitializer;
        this.jdbc = jdbc;
    }

    // ==================== 向量检索调试 ====================

    /**
     * 验证向量检索命中质量。不设 similarityThreshold，方便观察原始得分分布。
     *
     * @param q    查询文本
     * @param topK 返回条数（默认 5）
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "5") int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK).build());

        List<Map<String, Object>> hits = results.stream()
                .map(doc -> {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("source", doc.getMetadata().get("source"));
                    hit.put("type", doc.getMetadata().get("type"));
                    hit.put("score", doc.getScore());
                    hit.put("content", doc.getText());
                    return hit;
                })
                .toList();

        return Map.of("query", q, "total", hits.size(), "hits", hits);
    }

    // ==================== 知识库刷新 ====================

    /**
     * 手动触发车源向量同步。
     *
     * @param skuId 可选，指定单条 SKU ID；不传则全量哈希增量对比
     */
    @PostMapping("/sku/refresh")
    public Map<String, Object> refreshSku(@RequestParam(required = false) Long skuId) {
        if (skuId != null) {
            var rows = jdbc.queryForList("SELECT * FROM car_sku WHERE id = ?", skuId);
            if (rows.isEmpty()) {
                updater.deleteBySkuId(skuId);
                jdbc.update("DELETE FROM vector_sync_log WHERE sku_id = ?", skuId);
                return Map.of("skuId", skuId, "status", "deleted", "message", "SKU not found, cleaned up vectors");
            }
            var row = rows.get(0);
            var event = new SkuChangeEvent(skuId, ChangeType.UPDATE, row);
            updater.processChange(event);
            return Map.of("skuId", skuId, "status", "synced",
                    "brand", String.valueOf(row.getOrDefault("brand_name", "")),
                    "series", String.valueOf(row.getOrDefault("series_name", "")));
        }

        Map<String, Integer> stats = updater.syncChangedSkus();
        return Map.of("mode", "incremental", "stats", stats);
    }

    /**
     * 手动触发百科/话术文档增量同步。
     * 对比文件内容哈希，只重建变化的文档。
     */
    @PostMapping("/doc/refresh")
    public Map<String, Object> refreshDocs() {
        try {
            String result = docInitializer.refreshDocs();
            return Map.of("status", "ok", "result", result);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}