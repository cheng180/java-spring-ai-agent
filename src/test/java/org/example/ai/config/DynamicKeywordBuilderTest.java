package org.example.ai.config;

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
 * DynamicKeywordBuilder 集成测试（#12 ticket）。
 * 遵循项目已有模式：临时 SQLite 数据库 + JdbcTemplate。
 */
class DynamicKeywordBuilderTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private DynamicKeywordBuilder builder;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-kw-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,
                sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_mapping (
                entity_id TEXT NOT NULL, display_name TEXT NOT NULL,
                aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name)
            )
        """);

        builder = new DynamicKeywordBuilder(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 基础功能 ----

    @Test
    @DisplayName("从 entity_mapping 加载 displayName 和别名")
    void loadsAliases() {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "比亚迪-宋PLUS DM-i", "[\"宋plus\",\"BYD-宋PLUS\"]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e2", "特斯拉-Model Y", "[\"Model Y\",\"毛豆Y\"]");
        // 车源表放对应数据
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','宋PLUS DM-i')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'特斯拉','Model Y')");

        builder.rebuild();

        assertThat(builder.keywordCount()).isGreaterThanOrEqualTo(6);
        assertThat(builder.allKeywords()).contains("比亚迪-宋PLUS DM-i".toLowerCase());
        assertThat(builder.allKeywords()).contains("宋plus".toLowerCase());
        assertThat(builder.allKeywords()).contains("毛豆y");
        assertThat(builder.allKeywords()).contains("比亚迪");
        assertThat(builder.allKeywords()).contains("特斯拉");
    }

    @Test
    @DisplayName("新增品牌关键词自动识别")
    void newBrandRecognized() {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "零跑-零跑C11", "[]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'零跑','零跑C11')");
        builder.rebuild();

        assertThat(builder.allKeywords()).contains("零跑");
        assertThat(builder.containsAnyKeyword("零跑")).isTrue();
    }

    @Test
    @DisplayName("删除品牌后不再识别")
    void removedBrandNotRecognized() {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "理想-理想L6", "[]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'理想','理想L6')");
        builder.rebuild();
        assertThat(builder.containsAnyKeyword("理想")).isTrue();

        // 删光数据重建
        jdbc.update("DELETE FROM car_sku");
        jdbc.update("DELETE FROM entity_mapping");
        builder.rebuild();
        assertThat(builder.containsAnyKeyword("理想")).isFalse();
    }

    // ---- containsAnyKeyword ----

    @Test
    @DisplayName("文本含品牌名 → 返回 true")
    void containsBrand() {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "丰田-凯美瑞", "[]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'丰田','凯美瑞')");
        builder.rebuild();

        assertThat(builder.containsAnyKeyword("丰田凯美瑞多少钱")).isTrue();
        assertThat(builder.containsAnyKeyword("凯美瑞")).isTrue();
    }

    @Test
    @DisplayName("空文本安全")
    void emptyTextSafe() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','海鸥')");
        builder.rebuild();

        assertThat(builder.containsAnyKeyword(null)).isFalse();
        assertThat(builder.containsAnyKeyword("")).isFalse();
        assertThat(builder.containsAnyKeyword("   ")).isFalse();
    }

    @Test
    @DisplayName("空表安全")
    void emptyTableSafe() {
        builder.rebuild();
        assertThat(builder.keywordCount()).isEqualTo(0);
        assertThat(builder.containsAnyKeyword("比亚迪")).isFalse();
        assertThat(builder.extractKeywords("比亚迪")).isEmpty();
    }

    // ---- extractKeywords ----

    @Test
    @DisplayName("提取匹配关键词，长词优先")
    void extractKeywordsLongFirst() {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "比亚迪-宋PLUS DM-i", "[]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e2", "比亚迪-海鸥", "[]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','宋PLUS DM-i')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'比亚迪','海鸥')");
        builder.rebuild();

        List<String> hits = builder.extractKeywords("比亚迪宋PLUS DM-i和海鸥");
        assertThat(hits).isNotEmpty();
        // 长关键词排在前面
        assertThat(hits.get(0).length()).isGreaterThanOrEqualTo(hits.get(hits.size() - 1).length());
    }

    // ---- getSeriesKeys ----

    @Test
    @DisplayName("品牌名关键词映射到该品牌所有车系")
    void brandMapsToAllSeries() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','海鸥')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'比亚迪','汉EV')");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)", "e1", "比亚迪-海鸥", "[]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)", "e2", "比亚迪-汉EV", "[]");
        builder.rebuild();

        List<String> seriesKeys = builder.getSeriesKeys("比亚迪");
        assertThat(seriesKeys).hasSizeGreaterThanOrEqualTo(2);
        assertThat(seriesKeys).anyMatch(s -> s.contains("海鸥"));
        assertThat(seriesKeys).anyMatch(s -> s.contains("汉EV"));
    }

    @Test
    @DisplayName("未知关键词返回空列表")
    void unknownKeywordReturnsEmpty() {
        builder.rebuild();
        assertThat(builder.getSeriesKeys("不存在的品牌")).isEmpty();
    }
}