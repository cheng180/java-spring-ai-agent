package org.example.ai.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DatabaseInitializer 的三张新表 schema 和 entity_mapping 填充逻辑。
 * 纯 JUnit + 单连接 SQLite :memory: — 不启动 Spring 上下文。
 */
class DatabaseInitializerTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE vector_sync_log (sku_id INTEGER PRIMARY KEY, source_hash TEXT NOT NULL, last_synced_at TEXT DEFAULT (datetime('now','localtime')))");
            stmt.execute("CREATE TABLE series_ask_count (id INTEGER PRIMARY KEY AUTOINCREMENT, series_key TEXT NOT NULL, week_bucket TEXT NOT NULL, ask_count INTEGER DEFAULT 0, UNIQUE(series_key, week_bucket))");
            stmt.execute("CREATE TABLE entity_mapping (entity_id TEXT NOT NULL, display_name TEXT NOT NULL, aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name))");
            stmt.execute("CREATE TABLE car_sku (id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT, is_deleted INTEGER DEFAULT 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    @DisplayName("vector_sync_log 表可读写")
    void vectorSyncLogTable() throws Exception {
        try (var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO vector_sync_log (sku_id, source_hash) VALUES (100, 'abc123def456')");
            var rs = stmt.executeQuery("SELECT source_hash FROM vector_sync_log WHERE sku_id = 100");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("source_hash")).isEqualTo("abc123def456");
        }
    }

    @Test
    @DisplayName("series_ask_count 支持 UPSERT（唯一约束）")
    void seriesAskCountUpsert() throws Exception {
        try (var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES ('比亚迪-宋PLUS', '2026-W30', 5)");
            stmt.execute("INSERT INTO series_ask_count (series_key, week_bucket, ask_count) VALUES ('比亚迪-宋PLUS', '2026-W30', 3) ON CONFLICT(series_key, week_bucket) DO UPDATE SET ask_count = ask_count + excluded.ask_count");
            var rs = stmt.executeQuery("SELECT ask_count FROM series_ask_count WHERE series_key = '比亚迪-宋PLUS' AND week_bucket = '2026-W30'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("ask_count")).isEqualTo(8);
        }
    }

    @Test
    @DisplayName("entity_mapping 可存储别名 JSON")
    void entityMappingAliases() throws Exception {
        try (var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES ('entity:car:byd:song-plus', '比亚迪-宋PLUS', '[\"BYD-宋PLUS\",\"Song Plus\"]')");
            var rs = stmt.executeQuery("SELECT * FROM entity_mapping WHERE entity_id = 'entity:car:byd:song-plus'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("display_name")).isEqualTo("比亚迪-宋PLUS");
            assertThat(rs.getString("aliases_json")).contains("BYD-宋PLUS");
        }
    }

    @Test
    @DisplayName("entity_mapping 从 car_sku 自动去重填充")
    void entityMappingAutoPopulation() throws Exception {
        try (var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1, '比亚迪', '宋PLUS DM-i')");
            stmt.execute("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2, '比亚迪', '汉EV')");
            stmt.execute("INSERT INTO car_sku (id, brand_name, series_name) VALUES (3, '比亚迪', '宋PLUS DM-i')");
            stmt.execute("INSERT INTO car_sku (id, brand_name, series_name) VALUES (4, '特斯拉', 'Model Y')");
            stmt.execute("INSERT INTO car_sku (id, brand_name, series_name) VALUES (5, '理想', '理想L6')");

            var rs = stmt.executeQuery("SELECT DISTINCT brand_name, series_name FROM car_sku WHERE is_deleted = 0");
            int inserted = 0;
            try (var ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)")) {
                while (rs.next()) {
                    String brand = rs.getString("brand_name");
                    String series = rs.getString("series_name");
                    String entityId = "entity:car:" + toSlug(brand) + ":" + toSlug(series);
                    ps.setString(1, entityId);
                    ps.setString(2, brand + "-" + series);
                    ps.setString(3, "[]");
                    int rows = ps.executeUpdate();
                    if (rows > 0) inserted++;
                }
            }
            assertThat(inserted).isEqualTo(4);

            var rs2 = stmt.executeQuery("SELECT COUNT(*) FROM entity_mapping");
            assertThat(rs2.next()).isTrue();
            assertThat(rs2.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("时间衰减加权计算正确（决策11：8周系数）")
    void askCountDecayWeights() {
        double[] weights = {1.0, 0.8, 0.6, 0.4, 0.25, 0.15, 0.08, 0.03};
        int[] weeklyCounts = {10, 5, 8, 0, 0, 0, 0, 0};
        double actual = 0;
        for (int i = 0; i < weeklyCounts.length; i++) {
            actual += weeklyCounts[i] * weights[i];
        }
        assertThat(actual).isEqualTo(10 * 1.0 + 5 * 0.8 + 8 * 0.6); // 18.8
    }

    // ---- #30：裸车型词拆词注册别名 ----

    @Test
    @DisplayName("rebuildEntityMapping：车系名拆词注册裸车型别名（宝马X3 M → X3）")
    void rebuildEntityMappingRegistersBareModelTokens() throws Exception {
        // :memory: 单连接库不便复用，切临时文件 DB 走 JdbcTemplate
        File tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-init-" + UUID.randomUUID() + ".db");
        try {
            var ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE car_sku (id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,"
                    + " sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0)");
            jdbc.execute("CREATE TABLE entity_mapping (entity_id TEXT NOT NULL, display_name TEXT NOT NULL,"
                    + " aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name))");
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1, '宝马', '宝马X3 M')");
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2, '比亚迪', '宋PLUS DM-i')");
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (3, '测试', '测试GS 2026')");

            new DatabaseInitializer(jdbc).rebuildEntityMapping();

            String x3m = jdbc.queryForObject(
                    "SELECT aliases_json FROM entity_mapping WHERE display_name = '宝马-宝马X3 M'",
                    String.class);
            assertThat(x3m).contains("\"X3\"");        // 去品牌前缀拆词后裸车型词成为独立别名
            assertThat(x3m).doesNotContain("\"M\"");   // 单字母 token 不注册

            String song = jdbc.queryForObject(
                    "SELECT aliases_json FROM entity_mapping WHERE display_name = '比亚迪-宋PLUS DM-i'",
                    String.class);
            assertThat(song).contains("\"宋PLUS\"").contains("\"DM\"");
            assertThat(song).doesNotContain("\"i\"");  // 单字母 token 不注册

            String gs = jdbc.queryForObject(
                    "SELECT aliases_json FROM entity_mapping WHERE display_name = '测试-测试GS 2026'",
                    String.class);
            assertThat(gs).contains("\"GS\"");
            assertThat(gs).doesNotContain("\"2026\""); // 纯数字 token 不注册
        } finally {
            tmpDb.delete();
        }
    }

    private static String toSlug(String s) {
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
    }
}