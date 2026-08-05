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
 * CarSalesTools.searchInventory 行数上限测试（#38 ticket，《回复过长问题解决评估文档》R1 决堤口）。
 *
 * <p>历史缺口：matched 分支 SQL 无 LIMIT——受限级别轮次 LLM 一旦违反工具抑制指令
 * 调用 searchInventory，全量 SKU 回流上下文，长回复间歇性复发。
 * 本测试锁定"匹配项极多时返回不超过上限、较少时全量返回"。</p>
 */
class CarSalesToolsSearchInventoryLimitTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private CarSalesTools tools;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-inventory-limit-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id INTEGER PRIMARY KEY,
                brand_name TEXT, series_name TEXT, model_name TEXT,
                outer_color_name TEXT, guide_price INTEGER,
                sale_price INTEGER, sale_price_finance INTEGER,
                energy_type INTEGER DEFAULT 1, memo TEXT,
                sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_mapping (
                entity_id TEXT NOT NULL, display_name TEXT NOT NULL,
                aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name)
            )
        """);

        // 40 条同品牌车源——远超任何合理回复所需
        for (int i = 1; i <= 40; i++) {
            jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name,"
                    + " outer_color_name, sale_price, energy_type)"
                    + " VALUES (?,?,?,?,?,?,?)",
                    i, "比亚笛", "车系" + i, "车系" + i + " 款型A", "白", 10000000 + i, 1);
        }

        DynamicKeywordBuilder kwBuilder = new DynamicKeywordBuilder(jdbc);
        kwBuilder.afterPropertiesSet();

        tools = new CarSalesTools(jdbc,
                mock(StoreLocator.class), mock(PositionStackGeoLocator.class),
                kwBuilder, mock(HotCarRepository.class));
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    @Test
    @DisplayName("#38: matched 分支有上限——40 条匹配车源只返回封顶行数")
    void matchedBranchIsCapped() {
        String result = tools.searchInventory("比亚笛");

        // 输出头部"匹配到 N 条车源"的 N 不超过上限
        assertThat(result).startsWith("匹配到 ");
        int n = Integer.parseInt(result.substring("匹配到 ".length(), result.indexOf(" 条车源")));
        assertThat(n).isLessThanOrEqualTo(30).isGreaterThan(0);
    }

    @Test
    @DisplayName("#38: 匹配项较少时全量返回（上限不误伤正常查询）")
    void fewMatchesReturnAll() {
        // 追加 3 条另一品牌，单独查它
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name,"
                + " outer_color_name, sale_price) VALUES (101,'捷途','大圣','大圣 款型A','白',9000000)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name,"
                + " outer_color_name, sale_price) VALUES (102,'捷途','大圣','大圣 款型B','黑',9500000)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name,"
                + " outer_color_name, sale_price) VALUES (103,'捷途','旅行者','旅行者 款型A','灰',12000000)");
        DynamicKeywordBuilder kwBuilder = new DynamicKeywordBuilder(jdbc);
        kwBuilder.afterPropertiesSet();
        CarSalesTools freshTools = new CarSalesTools(jdbc,
                mock(StoreLocator.class), mock(PositionStackGeoLocator.class),
                kwBuilder, mock(HotCarRepository.class));

        String result = freshTools.searchInventory("捷途");

        assertThat(result).contains("匹配到 3 条车源");
        assertThat(result).contains("大圣 款型A").contains("旅行者 款型A");
    }
}