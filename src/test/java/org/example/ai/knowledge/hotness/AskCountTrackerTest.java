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

    private String currentWeekBucket() {
        return AskCountTracker.currentWeekBucket();
    }
}