package org.example.ai.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库调试接口 —— 验证向量检索命中质量
 *
 * 用法：GET /api/kb/search?q=10万预算推荐什么车&topK=5
 * 返回每块的来源文件、相似度得分、内容，用于调 chunkSize / topK / threshold。
 * 不设 similarityThreshold，方便观察原始得分分布。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeDebugController {

    private final VectorStore vectorStore;

    public KnowledgeDebugController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "5") int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK).build());

        List<Map<String, Object>> hits = results.stream()
                .map(doc -> {
                    Map<String, Object> hit = new java.util.LinkedHashMap<>();
                    hit.put("source", doc.getMetadata().get("source"));
                    hit.put("type", doc.getMetadata().get("type"));
                    hit.put("score", doc.getScore());
                    hit.put("content", doc.getText());
                    return (Map<String, Object>) hit;
                })
                .toList();

        return Map.of(
                "query", q,
                "total", hits.size(),
                "hits", hits
        );
    }
}