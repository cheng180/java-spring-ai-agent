package org.example.ai.impl.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.ResolvedEntity;
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
 * QueryLevelClassifier 表驱动测试（#24 ticket）。
 *
 * <p>覆盖实测校准过的判定表：裸品牌名 Layer-2 命中全部车系 → BRAND、
 * 品牌内少量车系 → FAMILY、跨品牌 → UNRESTRICTED、细节词优先等。</p>
 */
class QueryLevelClassifierTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private QueryLevelClassifier classifier;

    /** 品牌在售车系数（种子数据），用于裸品牌名 BRAND 判定断言 */
    private static final int BYD_SERIES_COUNT = 4;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-classifier-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE car_sku (
                id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,
                sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0
            )""");
        jdbc.execute("""
            CREATE TABLE entity_mapping (
                entity_id TEXT NOT NULL, display_name TEXT NOT NULL,
                aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name)
            )""");

        // 种子：比亚迪 4 个车系、特斯拉与保时捷各 1 个（单系列品牌用例）
        seedSku(1, "比亚迪", "宋PLUS DM-i");
        seedSku(2, "比亚迪", "汉EV");
        seedSku(3, "比亚迪", "秦L");
        seedSku(4, "比亚迪", "秦PLUS DM-i");
        seedSku(5, "特斯拉", "Model Y");
        seedSku(6, "保时捷", "Macan");
        seedEntity("比亚迪", "宋PLUS DM-i");
        seedEntity("比亚迪", "汉EV");
        seedEntity("比亚迪", "秦L");
        seedEntity("比亚迪", "秦PLUS DM-i");
        seedEntity("特斯拉", "Model Y");
        seedEntity("保时捷", "Macan");

        var keywordBuilder = new DynamicKeywordBuilder(jdbc);
        keywordBuilder.rebuild();
        classifier = new QueryLevelClassifier(keywordBuilder);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 判定表 ----

    @Test
    @DisplayName("品牌问句（无实体、品牌关键词命中多车系）→ BRAND")
    void brandQueryViaKeyword() {
        QueryClassification c = classifier.classify("有比亚迪吗", List.of());
        assertThat(c.level()).isEqualTo(QueryLevel.BRAND);
        assertThat(c.brand()).isEqualTo("比亚迪");
        assertThat(c.seriesKeys()).hasSize(BYD_SERIES_COUNT);
    }

    @Test
    @DisplayName("裸品牌名（Layer-2 命中该品牌全部车系）→ BRAND 而非 FAMILY")
    void bareBrandResolvesToBrand() {
        List<ResolvedEntity> allByd = List.of(
                entity("比亚迪", "宋PLUS DM-i"), entity("比亚迪", "汉EV"),
                entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"));
        QueryClassification c = classifier.classify("比亚迪", allByd);
        assertThat(c.level()).isEqualTo(QueryLevel.BRAND);
        assertThat(c.brand()).isEqualTo("比亚迪");
    }

    @Test
    @DisplayName("实体数超过 3 个（同品牌）→ BRAND")
    void manySameBrandEntitiesIsBrand() {
        List<ResolvedEntity> four = List.of(
                entity("比亚迪", "宋PLUS DM-i"), entity("比亚迪", "汉EV"),
                entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"));
        // 4 > 3 → BRAND（即使恰好等于品牌全部车系数也成立，两条规则任一命中即可）
        QueryClassification c = classifier.classify("比亚迪的车", four);
        assertThat(c.level()).isEqualTo(QueryLevel.BRAND);
    }

    @Test
    @DisplayName("同品牌少量车系（比亚迪秦 → 秦L+秦PLUS）→ FAMILY")
    void sameBrandFewSeriesIsFamily() {
        List<ResolvedEntity> qin = List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"));
        QueryClassification c = classifier.classify("比亚迪秦", qin);
        assertThat(c.level()).isEqualTo(QueryLevel.FAMILY);
        assertThat(c.brand()).isEqualTo("比亚迪");
        assertThat(c.seriesKeys()).containsExactly("比亚迪-秦L", "比亚迪-秦PLUS DM-i");
    }

    @Test
    @DisplayName("单车系命中 → SERIES")
    void singleSeries() {
        QueryClassification c = classifier.classify("海豹", List.of(entity("比亚迪", "汉EV")));
        assertThat(c.level()).isEqualTo(QueryLevel.SERIES);
        assertThat(c.brand()).isEqualTo("比亚迪");
        assertThat(c.seriesKeys()).containsExactly("比亚迪-汉EV");
    }

    @Test
    @DisplayName("单系列品牌问句（有保时捷吗）→ SERIES 而非 BRAND")
    void singleSeriesBrandIsSeries() {
        QueryClassification c = classifier.classify("有保时捷吗", List.of());
        assertThat(c.level()).isEqualTo(QueryLevel.SERIES);
        assertThat(c.seriesKeys()).containsExactly("保时捷-Macan");
    }

    @Test
    @DisplayName("细节词优先：单车系+多少钱 → UNRESTRICTED")
    void detailWordBeatsSeries() {
        QueryClassification c = classifier.classify("海豹多少钱", List.of(entity("比亚迪", "汉EV")));
        assertThat(c.level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("#31 预算表述：我要买20万的宝马 → UNRESTRICTED（提预算=谈价格，需全量数据）")
    void budgetWordsAreUnrestricted() {
        QueryClassification c = classifier.classify("我要买20万的宝马",
                List.of(entity("宝马", "宝马X3 M")));
        assertThat(c.level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("#31 预算词变体：预算/以内/左右 → UNRESTRICTED")
    void budgetWordVariantsAreUnrestricted() {
        assertThat(classifier.classify("预算15万，有什么推荐", List.of()).level())
                .isEqualTo(QueryLevel.UNRESTRICTED);
        assertThat(classifier.classify("10万以内的电车", List.of()).level())
                .isEqualTo(QueryLevel.UNRESTRICTED);
        assertThat(classifier.classify("20万左右的SUV", List.of()).level())
                .isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("对比查询：宋PLUS和海豹哪个好 → UNRESTRICTED（对比需要两边数据）")
    void comparisonIsUnrestricted() {
        QueryClassification c = classifier.classify("宋PLUS和海豹哪个好",
                List.of(entity("比亚迪", "汉EV")));
        assertThat(c.level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("跨品牌双实体 → UNRESTRICTED")
    void crossBrandIsUnrestricted() {
        QueryClassification c = classifier.classify("汉EV和Model Y",
                List.of(entity("比亚迪", "汉EV"), entity("特斯拉", "Model Y")));
        assertThat(c.level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("同品牌双车系+区别 → UNRESTRICTED（细节词优先于 FAMILY）")
    void detailWordBeatsFamily() {
        QueryClassification c = classifier.classify("秦PLUS和秦L的区别",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i")));
        assertThat(c.level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    @Test
    @DisplayName("无实体无关键词 → UNRESTRICTED（走现状回退路径）")
    void noSignalIsUnrestricted() {
        assertThat(classifier.classify("买车要注意什么", List.of()).level())
                .isEqualTo(QueryLevel.UNRESTRICTED);
        assertThat(classifier.classify("", List.of()).level()).isEqualTo(QueryLevel.UNRESTRICTED);
        assertThat(classifier.classify(null, List.of()).level()).isEqualTo(QueryLevel.UNRESTRICTED);
    }

    // ---- helpers ----

    private void seedSku(long id, String brand, String series) {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (?,?,?)",
                id, brand, series);
    }

    private void seedEntity(String brand, String series) {
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "entity:" + brand + ":" + series, brand + "-" + series, "[]");
    }

    private static ResolvedEntity entity(String brand, String series) {
        return new ResolvedEntity("entity:" + brand + ":" + series, brand + "-" + series, brand, series);
    }
}