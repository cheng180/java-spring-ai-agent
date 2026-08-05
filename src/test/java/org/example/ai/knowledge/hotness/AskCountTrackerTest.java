package org.example.ai.knowledge.hotness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AskCountTracker 集成测试（临时文件 SQLite）。
 */
class AskCountTrackerTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private AskCountTracker tracker;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-ask-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS series_ask_count (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                series_key  TEXT NOT NULL,
                week_bucket TEXT NOT NULL,
                ask_count   INTEGER DEFAULT 0,
                UNIQUE(series_key, week_bucket)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id              INTEGER PRIMARY KEY,
                brand_name      TEXT,
                series_name     TEXT,
                sale_status     INTEGER DEFAULT 1,
                is_deleted      INTEGER DEFAULT 0
            )
        """);

        tracker = new AskCountTracker(jdbc);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS series_ask_count");
        jdbc.execute("DROP TABLE IF EXISTS car_sku");
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    @Test
    @DisplayName("recordMention：当前周桶 UPSERT +1")
    void recordMentionIncrementsCurrentWeek() {
        String seriesKey = "比亚迪-宋PLUS DM-i";

        tracker.recordMention(seriesKey);
        tracker.recordMention(seriesKey);
        tracker.recordMention(seriesKey);

        String weekBucket = currentWeekBucket();
        Integer count = jdbc.queryForObject(
                "SELECT ask_count FROM series_ask_count WHERE series_key = ? AND week_bucket = ?",
                Integer.class, seriesKey, weekBucket);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("recordMention：不同车系独立计数")
    void differentSeriesIndependentCounts() {
        tracker.recordMention("比亚迪-宋PLUS DM-i");
        tracker.recordMention("比亚迪-宋PLUS DM-i");
        tracker.recordMention("特斯拉-Model Y");

        String weekBucket = currentWeekBucket();
        Integer byd = jdbc.queryForObject(
                "SELECT ask_count FROM series_ask_count WHERE series_key = ? AND week_bucket = ?",
                Integer.class, "比亚迪-宋PLUS DM-i", weekBucket);
        Integer tesla = jdbc.queryForObject(
                "SELECT ask_count FROM series_ask_count WHERE series_key = ? AND week_bucket = ?",
                Integer.class, "特斯拉-Model Y", weekBucket);

        assertThat(byd).isEqualTo(2);
        assertThat(tesla).isEqualTo(1);
    }

    @Test
    @DisplayName("getWeightedHeat：8周衰减加权正确")
    void weightedHeatUsesDecayCoefficients() {
        String seriesKey = "比亚迪-汉EV";
        List<String> buckets = AskCountTracker.pastWeekBuckets(8);

        // 插入测试数据：当前周 10 次，上周 5 次
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                seriesKey, buckets.get(0), 10);
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                seriesKey, buckets.get(1), 5);

        // 插入在售车源
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1, '比亚迪', '汉EV', 1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2, '比亚迪', '汉EV', 1)");

        double heat = tracker.getWeightedHeat(seriesKey);

        // weightedAsk = 10*1.0 + 5*0.8 = 14.0
        // sale_count proxy = 2 (在售车型数)
        assertThat(heat).isEqualTo(10 * 1.0 + 5 * 0.8 + 2);
    }

    @Test
    @DisplayName("getWeightedHeat：无询问记录时仅返回在售车型数")
    void heatOnlySkuCountWhenNoAsks() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1, '特斯拉', 'Model 3', 1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2, '特斯拉', 'Model 3', 1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (3, '特斯拉', 'Model 3', 1)");

        double heat = tracker.getWeightedHeat("特斯拉-Model 3");
        assertThat(heat).isEqualTo(3.0);
    }

    @Test
    @DisplayName("getAllWeightedHeats：返回所有车系的热度")
    void getAllWeightedHeatsReturnsAllSeries() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1, '比亚迪', '汉EV', 1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2, '特斯拉', 'Model Y', 1)");

        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                "比亚迪-汉EV", currentWeekBucket(), 5);

        Map<String, Double> heats = tracker.getAllWeightedHeats();
        assertThat(heats).containsKeys("比亚迪-汉EV", "特斯拉-Model Y");
        assertThat(heats.get("比亚迪-汉EV")).isGreaterThan(heats.get("特斯拉-Model Y"));
    }

    @Test
    @DisplayName("pruneOldData：清理超过 8 周的旧数据")
    void pruneRemovesOldData() {
        String seriesKey = "比亚迪-元PLUS";
        List<String> buckets = AskCountTracker.pastWeekBuckets(10);

        // 第9周前的旧数据（应该被清理）
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                seriesKey, buckets.get(9), 100);
        // 第8周的数据（在窗口内，保留）
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                seriesKey, buckets.get(7), 5);
        // 当周数据
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?, ?, ?)",
                seriesKey, buckets.get(0), 3);

        int pruned = tracker.pruneOldData();
        assertThat(pruned).isGreaterThanOrEqualTo(1);

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM series_ask_count WHERE series_key = ?",
                Integer.class, seriesKey);
        assertThat(remaining).isEqualTo(2); // 第0周 + 第7周保留
    }

    @Test
    @DisplayName("DECAY 数组为 8 个元素，和为 3.31")
    void decayArrayCorrect() {
        assertThat(AskCountTracker.DECAY).hasSize(8);
        double sum = 0;
        for (double d : AskCountTracker.DECAY) sum += d;
        assertThat(sum).isEqualTo(1.0 + 0.8 + 0.6 + 0.4 + 0.25 + 0.15 + 0.08 + 0.03);
    }

    @Test
    @DisplayName("recordMention：空/null 输入安全")
    void recordMentionHandlesNullBlank() {
        tracker.recordMention(null);
        tracker.recordMention("");
        tracker.recordMention("   ");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM series_ask_count", Integer.class);
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("pastWeekBuckets 返回正确的 ISO 周格式")
    void pastWeekBucketsFormat() {
        List<String> buckets = AskCountTracker.pastWeekBuckets(2);
        assertThat(buckets).hasSize(2);
        for (String bucket : buckets) {
            assertThat(bucket).matches("\\d{4}-W\\d{2}");
        }
    }

    @Test
    @DisplayName("currentWeekBucket 返回当前 ISO 周")
    void currentWeekBucketIsCurrentIsoWeek() {
        String bucket = AskCountTracker.currentWeekBucket();
        LocalDate today = LocalDate.now();
        int wy = today.get(WeekFields.ISO.weekBasedYear());
        int ww = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        assertThat(bucket).isEqualTo(wy + "-W" + String.format("%02d", ww));
    }

    // ---- #41：全局热门车系 topN（深模糊档 3 引导兜底候选源） ----

    @Test
    @DisplayName("#41 topSeriesByHeat：加权询问 + 在售车型数降序，与 getWeightedHeat 同源")
    void topSeriesByHeatRanksByWeightedAskPlusSkuProxy() {
        List<String> buckets = AskCountTracker.pastWeekBuckets(8);
        // 汉EV：当前周 10 次询问 + 2 在售车型 → heat 12
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?,?,?)",
                "比亚迪-汉EV", buckets.get(0), 10);
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1,'比亚迪','汉EV',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2,'比亚迪','汉EV',1)");
        // 海鸥：当前周 2 次询问 + 1 在售车型 → heat 3
        jdbc.update("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES (?,?,?)",
                "比亚迪-海鸥", buckets.get(0), 2);
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (3,'比亚迪','海鸥',1)");
        // 秦L：无询问，3 在售车型 → heat 3（销量代理兜底，与海鸥并列）
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (4,'比亚迪','秦L',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (5,'比亚迪','秦L',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (6,'比亚迪','秦L',1)");

        List<String> top = tracker.topSeriesByHeat(2);

        assertThat(top).hasSize(2);
        assertThat(top.get(0)).isEqualTo("比亚迪-汉EV");
        assertThat(top.get(1)).isIn("比亚迪-海鸥", "比亚迪-秦L");
    }

    @Test
    @DisplayName("#41 topSeriesByHeat：热度并列 → store_car_hot 销量兜底排序（BRAND 级同源）")
    void topSeriesByHeatUsesSalesTieBreak() {
        jdbc.execute("CREATE TABLE store_car_hot (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " store_id INTEGER NOT NULL, series_name TEXT NOT NULL,"
                + " sale_count INTEGER DEFAULT 0, inquiry_count INTEGER DEFAULT 0, stat_date DATE)");
        try {
            // 两车系热度完全一致（各 1 在售车型、无询问）
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1,'比亚迪','汉EV',1)");
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2,'比亚迪','海鸥',1)");
            // 销量：汉EV 30 > 海鸥 10 → 汉EV 排前
            jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, stat_date)"
                    + " VALUES (1,'汉EV',30,date('now'))");
            jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, stat_date)"
                    + " VALUES (1,'海鸥',10,date('now'))");

            List<String> top = tracker.topSeriesByHeat(2);

            assertThat(top).containsExactly("比亚迪-汉EV", "比亚迪-海鸥");
        } finally {
            jdbc.execute("DROP TABLE store_car_hot");
        }
    }

    @Test
    @DisplayName("#41 topSeriesByHeat：无询问无在售车源 → 空列表；topN 截断生效")
    void topSeriesByHeatEmptyAndTruncation() {
        assertThat(tracker.topSeriesByHeat(3)).isEmpty();

        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (1,'比亚迪','汉EV',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (2,'比亚迪','海鸥',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, sale_status) VALUES (3,'比亚迪','秦L',1)");

        assertThat(tracker.topSeriesByHeat(2)).hasSize(2);
    }

    private String currentWeekBucket() {
        return AskCountTracker.currentWeekBucket();
    }
}