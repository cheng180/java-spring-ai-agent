package org.example.ai.knowledge.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 实体解析器：运行时从 entity_mapping 表加载别名索引，支持中英文/大小写归一化
 * 匹配（#1 决策3 + #5 ticket）。
 *
 * 启动时从 DB 构建内存索引（ConcurrentHashMap），运行时零数据库访问。
 * - 同一实体多别名：各别名独立指向同一 ResolvedEntity
 * - 长后缀优先匹配：避免 "宋" 先匹配到而漏掉 "宋PLUS"
 * - 线程安全：ConcurrentMap + 不可变 ResolvedEntity record
 */
@Component
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    private final ConcurrentMap<String, ResolvedEntity> aliasIndex = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public EntityResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        rebuild();
        log.info("EntityResolver 初始化完成：{} 个别名索引", aliasIndex.size());
    }

    /**
     * 重建别名索引。启动时由构造函数构建；车源变更广播处理后由更新链路
     * （InMemoryIndexRefresher）再次调用，新车系无需重启即可被识别。
     */
    public void rebuild() {
        ConcurrentMap<String, ResolvedEntity> newIndex = new ConcurrentHashMap<>();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM entity_mapping ORDER BY display_name");
        int aliasCount = 0;

        for (Map<String, Object> row : rows) {
            String entityId = String.valueOf(row.get("entity_id"));
            String displayName = String.valueOf(row.get("display_name"));
            if ("null".equals(entityId) || "null".equals(displayName)) continue;

            String[] parts = displayName.split("-", 2);
            String brand = parts.length > 0 ? parts[0] : "";
            String series = parts.length > 1 ? parts[1] : "";
            ResolvedEntity re = new ResolvedEntity(entityId, displayName, brand, series);

            // 注册 displayName 本身
            newIndex.put(displayName.toLowerCase(), re);

            // 注册 JSON 别名
            String aliasesJson = String.valueOf(row.get("aliases_json"));
            if (!"null".equals(aliasesJson) && !"[]".equals(aliasesJson)) {
                // 简单解析 JSON 数组（不引入 Jackson dependency）
                String content = aliasesJson.substring(1, aliasesJson.length() - 1);
                if (!content.isBlank()) {
                    String[] aliases = content.replace("\"", "").split(",");
                    for (String alias : aliases) {
                        String trimmed = alias.trim();
                        if (!trimmed.isBlank()) {
                            newIndex.put(trimmed.toLowerCase(), re);
                            aliasCount++;
                        }
                    }
                }
            }
        }

        this.aliasIndex.clear();
        this.aliasIndex.putAll(newIndex);

        log.info("EntityResolver: 加载 {} 个实体，{} 个别名", rows.size(), newIndex.size());
    }

    /**
     * 从文本中识别所有匹配的实体别名。
     * 两层匹配策略：
     * 1. 精确包含：别名完整出现在用户文本中（如文本含"汉ev"，别名"汉ev"匹配）
     * 2. Token 回退：用户文本的所有 token（长度≥2）都出现在别名中
     *    （如"比亚迪宋PLUS"匹配"比亚迪-宋PLUS DM-i"）
     * 按匹配的别名长度降序（长匹配优先），entity_id 去重。
     *
     * @param text 用户输入文本（不区分大小写）
     * @return 识别到的实体列表（去重，长匹配在前）
     */
    public List<ResolvedEntity> resolve(String text) {
        if (text == null || text.isBlank() || aliasIndex.isEmpty()) return List.of();

        String lower = text.toLowerCase();
        List<Candidate> hits = new ArrayList<>();

        for (var entry : aliasIndex.entrySet()) {
            String alias = entry.getKey();
            if (matches(alias, lower)) {
                hits.add(new Candidate(alias.length(), entry.getValue()));
            }
        }

        // 长别名在前，然后按 entity_id 去重
        hits.sort((a, b) -> Integer.compare(b.matchLen, a.matchLen));
        Set<String> seen = new HashSet<>();
        List<ResolvedEntity> result = new ArrayList<>();
        for (Candidate c : hits) {
            if (seen.add(c.entity.entityId())) {
                result.add(c.entity);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 是否有任何实体别名命中文本。
     */
    public boolean hasMatch(String text) {
        if (text == null || text.isBlank() || aliasIndex.isEmpty()) return false;

        String lower = text.toLowerCase();
        for (String alias : aliasIndex.keySet()) {
            if (matches(alias, lower)) return true;
        }
        return false;
    }

    /** 别名索引总条目数 */
    public int aliasCount() {
        return aliasIndex.size();
    }

    // ---- 内部匹配逻辑 ----

    /**
     * 两层匹配：精确包含 OR 去特殊字符后子串匹配。
     */
    private boolean matches(String alias, String textLower) {
        // Layer 1：别名完整出现在文本中
        if (textLower.contains(alias)) return true;

        // Layer 2：去特殊字符后，别名包含用户文本的所有有效部分
        // 处理中文无分词场景："比亚迪宋PLUS"→"比亚迪宋plus"⊂"比亚迪宋plusdmi"
        String strippedAlias = stripSpecials(alias);
        String strippedText = stripSpecials(textLower);
        return !strippedText.isBlank() && strippedText.length() >= 3
                && strippedAlias.contains(strippedText);
    }

    /** 移除空格/连字符/特殊符号，用于中文子串匹配 */
    private static String stripSpecials(String s) {
        return s.replaceAll("[\\s\\-/\\.\\\\（）\\(\\)\\[\\]【】]+", "").toLowerCase();
    }

    // ---- 内部 ----

    private record Candidate(int matchLen, ResolvedEntity entity) {}
}