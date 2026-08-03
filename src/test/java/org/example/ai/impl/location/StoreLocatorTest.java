package org.example.ai.impl.location;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreLocator 集成测试 —— Haversine 距离计算 + 最近门店 + topN。
 * 使用临时 SQLite 构造 store_config 表，遵循项目已有测试模式。
 */
class StoreLocatorTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private StoreLocator locator;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-store-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_config (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                store_name      TEXT    NOT NULL,
                store_code      TEXT    UNIQUE NOT NULL,
                address         TEXT    NOT NULL,
                phone           TEXT,
                working_hours   TEXT    DEFAULT '9:00-18:00',
                region          TEXT,
                latitude        REAL    DEFAULT 0,
                longitude       REAL    DEFAULT 0,
                is_active       INTEGER DEFAULT 1
            )
        """);

        // 种子数据：北京(2家)、上海(1家)、深圳(1家)、杭州附近无门店
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "朝阳旗舰店", "STORE-CY", "北京朝阳", "朝阳区", 39.9087, 116.4716);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "海淀体验店", "STORE-HD", "北京海淀", "海淀区", 39.9836, 116.3168);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "浦东展厅", "STORE-PD", "上海浦东", "浦东新区", 31.2357, 121.5065);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "深圳科技园店", "STORE-SZ", "深圳南山", "南山区", 22.5370, 113.9550);
        // 停用门店
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude, is_active) VALUES (?,?,?,?,?,?,?)",
                "停用门店", "STORE-OFF", "某地", "某区", 30.0, 120.0, 0);

        locator = new StoreLocator(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- Haversine 公式 ----

    @Test
    @DisplayName("北京 → 上海 ≈ 1068 km")
    void beijingToShanghai() {
        double km = StoreLocator.haversineKm(39.9087, 116.4716, 31.2357, 121.5065);
        assertThat(km).isBetween(1050.0, 1100.0);
    }

    @Test
    @DisplayName("同一点距离为 0")
    void samePointZero() {
        double km = StoreLocator.haversineKm(39.9087, 116.4716, 39.9087, 116.4716);
        assertThat(km).isEqualTo(0.0);
    }

    @Test
    @DisplayName("对跖点 ≈ 20000 km（半周长）")
    void antipodalHalfCircumference() {
        double km = StoreLocator.haversineKm(0, 0, 0, 180);
        assertThat(km).isBetween(19900.0, 20100.0);
    }

    // ---- findNearest ----

    @Test
    @DisplayName("北京天安门 → 最近门店是朝阳旗舰店")
    void nearestToTiananmen() {
        // 天安门 39.9087, 116.3975 → 朝阳比海淀更近
        StoreInfo nearest = locator.findNearest(39.9087, 116.3975);
        assertThat(nearest).isNotNull();
        assertThat(nearest.storeName()).isEqualTo("朝阳旗舰店");
    }

    @Test
    @DisplayName("上海陆家嘴 → 最近门店是浦东展厅")
    void nearestToLujiazui() {
        StoreInfo nearest = locator.findNearest(31.24, 121.50);
        assertThat(nearest).isNotNull();
        assertThat(nearest.storeName()).isEqualTo("浦东展厅");
    }

    // ---- findNearest(topN) ----

    @Test
    @DisplayName("北京坐标 → topN=2 返回 2 家北京门店")
    void top2Beijing() {
        List<StoreInfo> top = locator.findNearest(39.91, 116.40, 2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).storeName()).isEqualTo("朝阳旗舰店"); // 更近
        assertThat(top.get(1).storeName()).isEqualTo("海淀体验店");
    }

    @Test
    @DisplayName("topN 超过总数时返回全部活跃门店（不含停用）")
    void topNExceedsTotal() {
        List<StoreInfo> top = locator.findNearest(39.91, 116.40, 100);
        assertThat(top).hasSize(4); // 4 活跃 + 1 停用
    }

    // ---- 空 / 边界 ----

    @Test
    @DisplayName("全部停用时 findNearest 返回 null")
    void emptyStoresReturnsNull() {
        jdbc.update("UPDATE store_config SET is_active = 0");
        assertThat(locator.findNearest(39.91, 116.40)).isNull();
    }

    @Test
    @DisplayName("全部停用时 topN 返回空列表")
    void emptyStoresTopNReturnsEmpty() {
        jdbc.update("UPDATE store_config SET is_active = 0");
        assertThat(locator.findNearest(39.91, 116.40, 3)).isEmpty();
    }

    @Test
    @DisplayName("findAllWithDistance 返回全部活跃门店按距离排序")
    void findAllWithDistanceSorted() {
        List<StoreInfo> all = locator.findAllWithDistance(39.91, 116.40);
        assertThat(all).hasSize(4);
        // 距离递增
        for (int i = 1; i < all.size(); i++) {
            double prevKm = StoreLocator.haversineKm(39.91, 116.40,
                    all.get(i - 1).latitude(), all.get(i - 1).longitude());
            double currKm = StoreLocator.haversineKm(39.91, 116.40,
                    all.get(i).latitude(), all.get(i).longitude());
            assertThat(prevKm).isLessThanOrEqualTo(currKm);
        }
    }
}