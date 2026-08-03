package org.example.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态关键词表 —— 启动时从 entity_mapping + car_sku 构建内存关键词集合（#12 ticket，决策8）。
 *
 * 替代 CarSalesAgent 中的硬编码 CAR_KEYWORDS 和 CarSalesTools 中的每次查库品牌/车系匹配。
 * 新增品牌/车系不需要改代码，重启即生效。
 *
 * 同时构建关键词→车系映射，供后续阶段三 VagueQueryRouter 使用。
 */
@Component
public class DynamicKeywordBuilder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicKeywordBuilder.class);

    private final JdbcTemplate jdbc;

    /** 归一化关键词 → 匹配的 seriesKey 列表（一个关键词可能对应多个车系，如 "比亚迪" 对应所有比亚迪车系） */
    private Map<String, List<String>> keywordToSeries;

    /** 全量关键词集合（小写归一化），供 isIdleChat / searchInventory 快速匹配 */
    private Set<String> allKeywords;

    public DynamicKeywordBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    /**
     * 重建关键词表。启动时由 afterPropertiesSet 构建；车源变更广播处理后
     * 由更新链路（InMemoryIndexRefresher）再次调用，新车系无需重启即可进入
     * 闲聊判定与模糊路由。
     */
    public synchronized void rebuild() {
        Map<String, List<String>> kw2series = new LinkedHashMap<>();

        // 1. 从 entity_mapping 提取别名
        List<Map<String, Object>> entities = jdbc.queryForList("SELECT * FROM entity_mapping");
        for (Map<String, Object> row : entities) {
            String displayName = str(row.get("display_name"));
            if (displayName.isEmpty()) continue;

            // displayName 如 "比亚迪-宋PLUS DM-i"，seriesKey 同 displayName
            addKeyword(kw2series, displayName, displayName);

            // 解析 aliases_json 中的每个别名
            String aliasesJson = str(row.get("aliases_json"));
            if (!aliasesJson.isEmpty() && !"[]".equals(aliasesJson)) {
                String content = aliasesJson.substring(1, aliasesJson.length() - 1);
                if (!content.isBlank()) {
                    for (String alias : content.replace("\"", "").split(",")) {
                        String trimmed = alias.trim();
                        if (!trimmed.isBlank()) {
                            addKeyword(kw2series, trimmed, displayName);
                        }
                    }
                }
            }
        }

        // 2. 从 car_sku 补充品牌名和车系名
        List<Map<String, Object>> brands = jdbc.queryForList(
                "SELECT DISTINCT brand_name FROM car_sku WHERE is_deleted = 0");
        for (Map<String, Object> row : brands) {
            String brand = str(row.get("brand_name"));
            if (!brand.isBlank()) {
                // 品牌名关联到该品牌下所有车系
                List<Map<String, Object>> seriesList = jdbc.queryForList(
                        "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE brand_name = ? AND is_deleted = 0",
                        brand);
                for (Map<String, Object> s : seriesList) {
                    String sk = str(s.get("brand_name")) + "-" + str(s.get("series_name"));
                    addKeyword(kw2series, brand, sk);
                }
            }
        }
        // 车系名（独立关键词）
        List<Map<String, Object>> series = jdbc.queryForList(
                "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE is_deleted = 0");
        for (Map<String, Object> row : series) {
            String sName = str(row.get("series_name"));
            if (!sName.isBlank()) {
                String sk = str(row.get("brand_name")) + "-" + sName;
                addKeyword(kw2series, sName, sk);
            }
        }

        this.keywordToSeries = Collections.unmodifiableMap(kw2series);
        this.allKeywords = Collections.unmodifiableSet(kw2series.keySet());

        log.info("DynamicKeywordBuilder 构建完成：{} 个关键词", allKeywords.size());
    }

    /** 检查文本中是否包含任何关键词 */
    public boolean containsAnyKeyword(String text) {
        if (text == null || text.isBlank() || allKeywords.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String kw : allKeywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** 从文本中提取匹配的关键词 */
    public List<String> extractKeywords(String text) {
        if (text == null || text.isBlank() || allKeywords.isEmpty()) return List.of();
        String lower = text.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (String kw : allKeywords) {
            if (lower.contains(kw)) hits.add(kw);
        }
        // 长关键词优先
        hits.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return hits;
    }

    /** 关键词 → 关联的 seriesKey 列表 */
    public List<String> getSeriesKeys(String keyword) {
        return keywordToSeries.getOrDefault(keyword.toLowerCase(), List.of());
    }

    /** 关键词总数 */
    public int keywordCount() {
        return allKeywords.size();
    }

    /** 全量关键词集合 */
    public Set<String> allKeywords() {
        return allKeywords;
    }

    // ---- 内部 ----

    private void addKeyword(Map<String, List<String>> map, String keyword, String seriesKey) {
        String norm = keyword.toLowerCase().trim();
        if (norm.isEmpty() || norm.length() < 2) return; // 跳过单字符
        map.computeIfAbsent(norm, k -> new ArrayList<>()).add(seriesKey);
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
}