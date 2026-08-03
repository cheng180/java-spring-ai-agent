package org.example.ai.knowledge.hotness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * 询问次数追踪器（#4 决策11）。
 *
 * 按周分桶记录各车系的用户询问次数，支持时间衰减加权计算热度。
 * 热度公式：heat = Σ(weekly_ask_count × decay[week]) + sale_count
 */
@Component
public class AskCountTracker {

    private static final Logger log = LoggerFactory.getLogger(AskCountTracker.class);

    /** 8 周时间衰减系数（决策11） */
    static final double[] DECAY = {1.0, 0.8, 0.6, 0.4, 0.25, 0.15, 0.08, 0.03};

    /** 保留窗口：8 周 */
    static final int WINDOW_WEEKS = 8;

    private final JdbcTemplate jdbc;

    public AskCountTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次对某车系的询问。当前周桶 +1（UPSERT）。
     *
     * @param seriesKey 车系标识（如 "比亚迪-宋PLUS DM-i"）
     */
    public void recordMention(String seriesKey) {
        if (seriesKey == null || seriesKey.isBlank()) return;
        String weekBucket = currentWeekBucket();
        jdbc.update("""
            INSERT INTO series_ask_count (series_key, week_bucket, ask_count)
            VALUES (?, ?, 1)
            ON CONFLICT(series_key, week_bucket)
            DO UPDATE SET ask_count = ask_count + 1
        """, seriesKey, weekBucket);
        log.debug("AskCountTracker: {} → {} +1", seriesKey, weekBucket);
    }

    /**
     * 计算某车系的加权热度。
     * heat = Σ(最近8周每周询问次数 × 衰减系数) + 该车系在售车型数（作为销量代理）
     *
     * @param seriesKey 车系标识
     * @return 热度得分
     */
    public double getWeightedHeat(String seriesKey) {
        List<String> buckets = pastWeekBuckets(WINDOW_WEEKS);

        // 查询各周桶的询问次数
        double weightedAsk = 0;
        for (int i = 0; i < buckets.size(); i++) {
            List<Integer> counts = jdbc.queryForList(
                    "SELECT ask_count FROM series_ask_count WHERE series_key = ? AND week_bucket = ?",
                    Integer.class, seriesKey, buckets.get(i));
            if (!counts.isEmpty()) {
                weightedAsk += counts.get(0) * DECAY[i];
            }
        }

        // 销量代理：该车系在售车型数
        int skuCount = countActiveSkus(seriesKey);

        return weightedAsk + skuCount;
    }

    /**
     * 获取所有车系的加权热度（用于排序）。
     */
    public Map<String, Double> getAllWeightedHeats() {
        List<String> seriesKeys = jdbc.queryForList(
                "SELECT DISTINCT series_key FROM series_ask_count", String.class);

        // 也纳入有在售车源但还没有询问记录的车系
        List<Map<String, Object>> allSeries = jdbc.queryForList(
                "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE sale_status = 1 AND is_deleted = 0");
        Set<String> allKeys = new LinkedHashSet<>(seriesKeys);
        for (Map<String, Object> s : allSeries) {
            allKeys.add(s.get("brand_name") + "-" + s.get("series_name"));
        }

        Map<String, Double> heats = new LinkedHashMap<>();
        for (String key : allKeys) {
            heats.put(key, getWeightedHeat(key));
        }
        return heats;
    }

    /**
     * 清理超过 8 周的旧数据。
     * 在启动时调用或定时任务调用。
     */
    public int pruneOldData() {
        String cutoff = pastWeekBuckets(WINDOW_WEEKS + 1).get(WINDOW_WEEKS); // 第9周（过期的第一周）
        int deleted = jdbc.update(
                "DELETE FROM series_ask_count WHERE week_bucket <= ?", cutoff);
        if (deleted > 0) {
            log.info("AskCountTracker: 清理 {} 条过期询问记录（≤ {}）", deleted, cutoff);
        }
        return deleted;
    }

    // ---- 内部辅助 ----

    /** 当前 ISO 周桶，如 "2026-W31" */
    static String currentWeekBucket() {
        LocalDate now = LocalDate.now();
        int year = now.get(WeekFields.ISO.weekOfWeekBasedYear()) == 1 && now.getMonthValue() == 12
                ? now.getYear() + 1
                : now.getYear();
        // 使用 WeekFields.ISO 获取正确的周年和周年份
        int weekYear = now.get(WeekFields.ISO.weekBasedYear());
        int week = now.get(WeekFields.ISO.weekOfWeekBasedYear());
        return weekYear + "-W" + String.format("%02d", week);
    }

    /** 获取最近 N 周的周桶列表（当前周 = index 0） */
    static List<String> pastWeekBuckets(int n) {
        List<String> buckets = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < n; i++) {
            LocalDate d = today.minusWeeks(i);
            int wy = d.get(WeekFields.ISO.weekBasedYear());
            int ww = d.get(WeekFields.ISO.weekOfWeekBasedYear());
            buckets.add(wy + "-W" + String.format("%02d", ww));
        }
        return buckets;
    }

    /** 计算某车系的在售车型数（作为销量代理） */
    private int countActiveSkus(String seriesKey) {
        // seriesKey 格式："比亚迪-宋PLUS DM-i"
        int dash = seriesKey.indexOf('-');
        if (dash < 0) return 0;
        String brand = seriesKey.substring(0, dash);
        String series = seriesKey.substring(dash + 1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM car_sku WHERE brand_name = ? AND series_name = ? AND sale_status = 1 AND is_deleted = 0",
                Integer.class, brand, series);
        return count != null ? count : 0;
    }
}