package org.example.ai.knowledge.facts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 原子事实提取器（#1 决策2路径B）。
 *
 * 替代旧的 TokenTextSplitter(500) 盲切。将百科/话术文档通过 LLM 命题提取为
 * 自包含、可独立检索的 AtomicFact 列表，每条带时序标记。
 *
 * 分块策略：每个原子事实 = 一个 chunk（不需要 splitter）。
 */
@Component
public class LlmFactExtractor implements AtomicFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmFactExtractor.class);

    private final ChatClient.Builder chatClientBuilder;

    /** 提取 Prompt：要求 LLM 输出结构化 JSON 数组 */
    private static final String EXTRACTION_PROMPT = """
        你是一个知识库构建专家。请将以下文档内容拆分为原子事实列表。

        每条原子事实必须满足：
        1. 自包含 —— 脱离上下文也能独立理解
        2. 单一命题 —— 一条事实只陈述一件事
        3. 不可再分 —— 已经是能检索的最小语义单元

        返回 JSON 数组，每个元素包含：
        - content: 原子事实文本（一条自包含的陈述）
        - temporal_type: "ATEMPORAL"（永远正确）| "STATIC"（事实）| "DYNAMIC"（可能过时）

        分类规则：
        - 销售技巧、心理学原则、沟通方法论 → ATEMPORAL
        - 车型参数、官方配置、品牌介绍 → STATIC
        - 价格信息、促销方案、库存数据 → DYNAMIC

        只返回 JSON 数组，不要其他文字。
        [
          {"content": "...", "temporal_type": "ATEMPORAL"},
          {"content": "...", "temporal_type": "STATIC"}
        ]

        文档内容：
        """;

    public LlmFactExtractor(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    public List<AtomicFact> extract() {
        throw new UnsupportedOperationException("LlmFactExtractor 需要指定源文档。请使用 extract(Resource, String, String)");
    }

    /**
     * 从文档资源中提取原子事实。
     *
     * @param resource 文档资源（.md / .txt）
     * @param sourceDoc 来源文件名（如 "汽车百科知识库.md"）
     * @param entityId  实体ID（如 "entity:doc:encyclopedia"）
     */
    public List<AtomicFact> extract(Resource resource, String sourceDoc, String entityId) {
        String text;
        try {
            text = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("LlmFactExtractor: 读取文档失败 - {}", sourceDoc, e);
            return List.of();
        }

        // 文档过长时分段处理（每段不超过 8000 字符）
        if (text.length() > 8000) {
            return extractChunked(text, sourceDoc, entityId);
        }

        return extractSingle(text, sourceDoc, entityId);
    }

    private List<AtomicFact> extractSingle(String text, String sourceDoc, String entityId) {
        String prompt = EXTRACTION_PROMPT + "\n" + text;

        try {
            String response = chatClientBuilder.build().prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("LlmFactExtractor: LLM 返回空响应 - {}", sourceDoc);
                return List.of();
            }

            return parseResponse(response, sourceDoc, entityId);
        } catch (Exception e) {
            log.error("LlmFactExtractor: LLM 调用失败 - {}", sourceDoc, e);
            // 降级：返回整个文档作为一个事实块
            return fallbackSingle(text, sourceDoc, entityId);
        }
    }

    private List<AtomicFact> extractChunked(String text, String sourceDoc, String entityId) {
        List<AtomicFact> allFacts = new ArrayList<>();
        int chunkSize = 8000;
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            String chunk = text.substring(i, end);
            allFacts.addAll(extractSingle(chunk, sourceDoc, entityId + "-p" + (i / chunkSize)));
        }
        return allFacts;
    }

    /**
     * 解析 LLM 返回的 JSON 数组为 AtomicFact 列表。
     * 容错：提取不到 JSON 数组时降级为单条事实。
     */
    @SuppressWarnings("unchecked")
    List<AtomicFact> parseResponse(String response, String sourceDoc, String entityId) {
        List<AtomicFact> facts = new ArrayList<>();
        String json = response.trim();

        // 提取 JSON 数组部分（LLM 可能在前后加说明文字）
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("LlmFactExtractor: 未找到 JSON 数组，降级为单条 - {}", sourceDoc);
            return fallbackSingle(json, sourceDoc, entityId);
        }
        json = json.substring(start, end + 1);

        // 简易 JSON 数组解析（不依赖 Jackson，减少依赖和复杂度）
        try {
            List<Map<String, String>> items = parseSimpleJsonArray(json);
            int idx = 0;
            for (Map<String, String> item : items) {
                String content = item.get("content");
                String tmpType = item.getOrDefault("temporal_type", "STATIC");
                if (content == null || content.isBlank()) continue;

                TemporalType tt = parseTemporalType(tmpType);
                facts.add(AtomicFact.builder()
                        .factId(entityId + "-" + String.format("%03d", idx++))
                        .content(content.trim())
                        .entityId(entityId)
                        .temporalType(tt)
                        .sourceDoc(sourceDoc)
                        .sourceHash(SkuFactExtractor.sha256(content.trim()))
                        .build());
            }
        } catch (Exception e) {
            log.warn("LlmFactExtractor: JSON 解析失败，降级 - {}", sourceDoc);
            return fallbackSingle(json, sourceDoc, entityId);
        }

        log.info("LlmFactExtractor: 从 {} 提取 {} 条原子事实", sourceDoc, facts.size());
        return facts;
    }

    /** LLM 失败或无结果时：整篇文档作为一条事实（优于盲切分） */
    private List<AtomicFact> fallbackSingle(String text, String sourceDoc, String entityId) {
        if (text == null || text.isBlank()) {
            return List.of(AtomicFact.builder()
                    .factId(entityId + "-000")
                    .content("（文档内容为空：" + sourceDoc + "）")
                    .entityId(entityId)
                    .temporalType(TemporalType.STATIC)
                    .sourceDoc(sourceDoc)
                    .sourceHash(SkuFactExtractor.sha256("empty"))
                    .build());
        }
        String snippet = text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
        return List.of(AtomicFact.builder()
                .factId(entityId + "-000")
                .content(snippet)
                .entityId(entityId)
                .temporalType(TemporalType.STATIC)
                .sourceDoc(sourceDoc)
                .sourceHash(SkuFactExtractor.sha256(text))
                .build());
    }

    private TemporalType parseTemporalType(String s) {
        return switch (s.toUpperCase()) {
            case "ATEMPORAL" -> TemporalType.ATEMPORAL;
            case "DYNAMIC" -> TemporalType.DYNAMIC;
            default -> TemporalType.STATIC;
        };
    }

    /**
     * 极简 JSON 数组解析（只处理 [{...}, {...}] 格式，对象只有字符串值）。
     * 不依赖第三方 JSON 库。
     */
    private List<Map<String, String>> parseSimpleJsonArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        int depth = 0, objStart = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    result.add(parseSimpleJsonObject(obj));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    private Map<String, String> parseSimpleJsonObject(String obj) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        // 去除大括号
        String inner = obj.substring(1, obj.length() - 1).trim();
        // 匹配 "key": "value" 对（值可能含转义引号）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher m = p.matcher(inner);
        while (m.find()) {
            map.put(m.group(1), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return map;
    }
}