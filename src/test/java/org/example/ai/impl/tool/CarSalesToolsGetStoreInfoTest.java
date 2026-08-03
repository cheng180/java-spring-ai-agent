package org.example.ai.impl.tool;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.location.HotCarRepository;
import org.example.ai.impl.location.PositionStackGeoLocator;
import org.example.ai.impl.location.StoreLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CarSalesTools.getStoreInfo(city) 单元测试（#16-T2）。
 * region LIKE 优先 / positionstack 兜底 / 未找到三分支。
 * StoreLocator 用临时 SQLite（沿用 StoreLocatorTest 模式）；geocoder 用假 httpGet。
 */
class CarSalesToolsGetStoreInfoTest {

    private static final String BASE = "http://api.positionstack.test/v1/forward";

    private File tmpDb;
    private JdbcTemplate jdbc;
    private StoreLocator storeLocator;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-storeinfo-" + UUID.randomUUID() + ".db");
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

        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "朝阳旗舰店", "STORE-CY", "北京朝阳", "朝阳区", 39.9087, 116.4716);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "浦东展厅", "STORE-PD", "上海浦东", "浦东新区", 31.2357, 121.5065);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region, latitude, longitude) VALUES (?,?,?,?,?,?)",
                "深圳科技园店", "STORE-SZ", "深圳南山", "南山区", 22.5370, 113.9550);

        storeLocator = new StoreLocator(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    private PositionStackGeoLocator geocoderAt(double lat, double lng) {
        String json = "{\"data\":[{\"latitude\":" + lat + ",\"longitude\":" + lng + "}]}";
        return new PositionStackGeoLocator("test-key", BASE, url -> json);
    }

    private PositionStackGeoLocator emptyGeocoder() {
        return new PositionStackGeoLocator("test-key", BASE, url -> "{\"data\":[]}");
    }

    private CarSalesTools tools(PositionStackGeoLocator geocoder) {
        return new CarSalesTools(jdbc, storeLocator, geocoder,
                mock(DynamicKeywordBuilder.class), mock(HotCarRepository.class));
    }

    @Test
    @DisplayName("region LIKE 命中：返回该城市门店，不走地理编码兜底")
    void regionLikeHitWinsOverGeocoder() {
        // geocoder 故意返回浦东坐标；region 命中朝阳时应返回朝阳旗舰店
        String result = tools(geocoderAt(31.2357, 121.5065)).getStoreInfo("朝阳");
        assertThat(result).contains("朝阳旗舰店");
        assertThat(result).doesNotContain("浦东展厅");
    }

    @Test
    @DisplayName("region 未命中：走 positionstack 兜底返回最近门店")
    void positionstackFallbackReturnsNearest() {
        // "上海"不在任何 region 里 → 兜底；geocoder 返回浦东坐标 → 最近是浦东展厅
        String result = tools(geocoderAt(31.2357, 121.5065)).getStoreInfo("上海");
        assertThat(result).contains("浦东展厅");
    }

    @Test
    @DisplayName("region 未命中且地理编码无结果：返回未找到提示")
    void noRegionNoGeocodeReturnsNotFound() {
        String result = tools(emptyGeocoder()).getStoreInfo("不存在的城市");
        assertThat(result).contains("未找到该城市的门店");
    }

    @Test
    @DisplayName("未配置 positionstack key（geocoder 降级 null）：返回未找到提示")
    void geocoderDegradesToNotFound() {
        PositionStackGeoLocator noKey = new PositionStackGeoLocator("", BASE, url -> "{\"data\":[]}");
        String result = tools(noKey).getStoreInfo("上海");
        assertThat(result).contains("未找到该城市的门店");
    }

    @Test
    @DisplayName("city 为空：提示先告知城市，不调用查询")
    void blankCityAsksForCity() {
        assertThat(tools(emptyGeocoder()).getStoreInfo(null)).contains("请先告诉我您所在的城市");
        assertThat(tools(emptyGeocoder()).getStoreInfo("  ")).contains("请先告诉我您所在的城市");
    }

    @Test
    @DisplayName("只返回一家门店（不返回列表）")
    void returnsSingleStoreOnly() {
        String result = tools(geocoderAt(39.9087, 116.4716)).getStoreInfo("朝阳");
        assertThat(result).contains("朝阳旗舰店");
        assertThat(result).doesNotContain("浦东展厅").doesNotContain("深圳科技园店");
    }
}