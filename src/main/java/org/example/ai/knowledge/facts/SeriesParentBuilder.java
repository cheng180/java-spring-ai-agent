package org.example.ai.knowledge.facts;

import org.example.ai.knowledge.hotness.AskCountTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 车系父块构建器（#2 决策6 + #4 热度集成）。
 *
 * 父块 = SQL聚合统计（车型数/价格区间/能源类型/颜色） + 百科匹配（后续Ticket实现） + 加权热度（#4）。
 * 父块也做 embedding，metadata 标记 level=parent。
 * 检索时只返回子块，父块通过 metadata 的 parent_series_id 按需加载（#2 决策5）。
 */
@Component
public class SeriesParentBuilder {

    private static final Logger log = LoggerFactory.getLogger(SeriesParentBuilder.class);

    /**
     * 父块文本中"在售款型"段的标题锚点。
     *
     * <p>契约：该锚点在父块文本中出现且只出现一次（见 SeriesParentBuilderTest 契约测试）。
     * 下游分层检索（车系族级）按此锚点截断车型清单段——修改父块格式时必须保证锚点不破。</p>
     */
    public static final String MODELS_SECTION = "在售款型：";

    private final JdbcTemplate jdbc;
    private final SkuFactExtractor skuFactExtractor;
    private final AskCountTracker askCountTracker;

    public SeriesParentBuilder(JdbcTemplate jdbc, SkuFactExtractor skuFactExtractor,
                               AskCountTracker askCountTracker) {
        this.jdbc = jdbc;
        this.skuFactExtractor = skuFactExtractor;
        this.askCountTracker = askCountTracker;
    }

    /**
     * 为指定 brand+series 构建父块原子事实。
     *
     * @param brand  品牌名（如 "比亚迪"）
     * @param series 车系名（如 "宋PLUS DM-i"）
     * @return 父块 AtomicFact，品牌/车系无在售车源时返回 null
     */
    public AtomicFact buildParent(String brand, String series) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE brand_name = ? AND series_name = ? AND sale_status = 1 AND is_deleted = 0",
                brand, series);

        if (rows.isEmpty()) {
            log.debug("SeriesParentBuilder: {}-{} 无在售车源，跳过父块构建", brand, series);
            return null;
        }

        String content = buildContent(brand, series, rows);
        String entityId = SkuFactExtractor.buildEntityId(brand, series);
        String hash = SkuFactExtractor.sha256(content);

        double heat = askCountTracker.getWeightedHeat(brand + "-" + series);

        return AtomicFact.builder()
                .factId("parent-" + entityId)
                .content(content)
                .entityId(entityId)
                .temporalType(TemporalType.STATIC) // 车系描述：仅车系列表或销量变更时重建
                .sourceDoc("car_sku表-聚合")
                .sourceHash(hash)
                .metadata("type", "车源")
                .metadata("level", "parent")
                .metadata("series_id", brand + "-" + series)
                .metadata("brand_name", brand)
                .metadata("series_name", series)
                .metadata("sku_count", rows.size())
                .metadata("heat", heat)
                .build();
    }

    /**
     * 构建所有车系的父块（去重 brand+series），按加权热度降序排列。
     */
    public List<AtomicFact> buildAllParents() {
        List<Map<String, Object>> seriesList = jdbc.queryForList(
                "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE sale_status = 1 AND is_deleted = 0");

        List<AtomicFact> parents = new java.util.ArrayList<>();
        for (Map<String, Object> s : seriesList) {
            AtomicFact parent = buildParent(
                    String.valueOf(s.get("brand_name")),
                    String.valueOf(s.get("series_name")));
            if (parent != null) parents.add(parent);
        }

        // 按热度降序排列
        parents.sort((a, b) -> Double.compare(
                ((Number) b.getMetadata().getOrDefault("heat", 0.0)).doubleValue(),
                ((Number) a.getMetadata().getOrDefault("heat", 0.0)).doubleValue()
        ));

        log.info("SeriesParentBuilder: 构建 {} 个父块（按热度排序）", parents.size());
        return parents;
    }

    /**
     * 父块内容拼接（#2 决策6）：
     * 1. 车系简介占位（百科匹配后续Ticket实现）
     * 2. SQL聚合统计：车型数、能源覆盖、价格区间、颜色
     * 3. 询问次数占位（Ticket #4实现）
     */
    private String buildContent(String brand, String series, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(brand).append(" ").append(series).append("】车系信息\n");

        // 聚合统计
        sb.append("在售车型：").append(rows.size()).append("款\n");

        // 能源类型覆盖
        boolean hasFuel = rows.stream().anyMatch(r -> isType(r, 1));
        boolean hasEv = rows.stream().anyMatch(r -> isType(r, 2));
        if (hasFuel && hasEv) sb.append("能源类型：燃油 + 新能源\n");
        else if (hasEv) sb.append("能源类型：新能源\n");
        else sb.append("能源类型：燃油\n");

        // 价格区间
        double minPrice = rows.stream()
                .filter(r -> r.get("sale_price") != null)
                .mapToDouble(r -> ((Number) r.get("sale_price")).doubleValue() / 1_000_000.0)
                .min().orElse(0);
        double maxPrice = rows.stream()
                .filter(r -> r.get("sale_price") != null)
                .mapToDouble(r -> ((Number) r.get("sale_price")).doubleValue() / 1_000_000.0)
                .max().orElse(0);
        if (minPrice > 0) sb.append(String.format("价格区间：%.2f万 ~ %.2f万\n", minPrice, maxPrice));

        // 可选颜色（去重）
        List<String> colors = rows.stream()
                .map(r -> String.valueOf(r.get("outer_color_name")))
                .filter(c -> !c.isBlank() && !"null".equals(c))
                .distinct().toList();
        if (!colors.isEmpty()) sb.append("可选颜色：").append(String.join("、", colors)).append("\n");

        // 车型列表（最多列5款）
        sb.append(MODELS_SECTION).append("\n");
        rows.stream().limit(5).forEach(r -> {
            sb.append("  - ").append(String.valueOf(r.get("model_name")));
            if (r.get("sale_price") != null) {
                sb.append(String.format(" | 全款%.2f万",
                        ((Number) r.get("sale_price")).doubleValue() / 1_000_000.0));
            }
            String color = String.valueOf(r.get("outer_color_name"));
            if (!"null".equals(color)) sb.append(" | ").append(color);
            sb.append("\n");
        });
        if (rows.size() > 5) sb.append("  ... 共").append(rows.size()).append("款\n");

        // 加权热度（#4 决策11）
        double heat = askCountTracker.getWeightedHeat(brand + "-" + series);
        sb.append(String.format("近期热度：%.1f（基于近8周询问 + 在售车型数）\n", heat));

        return sb.toString();
    }

    private boolean isType(Map<String, Object> r, int type) {
        Object e = r.get("energy_type");
        return e != null && ((Number) e).intValue() == type;
    }
}