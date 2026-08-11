package org.example.ai.knowledge.facts;

import org.example.ai.knowledge.hotness.AskCountTracker;
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
 * SeriesParentBuilder + SkuFactExtractor 集成测试（临时文件 SQLite，连接共享）。
 */
class SeriesParentBuilderTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private SkuFactExtractor skuExtractor;
    private SeriesParentBuilder parentBuilder;
    private AskCountTracker askTracker;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-car-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE car_sku (
                id              INTEGER PRIMARY KEY,
                brand_name      TEXT,
                series_name     TEXT,
                model_name      TEXT,
                outer_color_name TEXT,
                guide_price     TEXT,
                sale_price      INTEGER,
                sale_price_finance INTEGER,
                spec_name       TEXT,
                energy_type     INTEGER DEFAULT 1,
                in_store_insurance INTEGER DEFAULT 1,
                can_issue_vat_invoice INTEGER DEFAULT 1,
                owner_name      TEXT,
                sale_status     INTEGER DEFAULT 1,
                is_deleted      INTEGER DEFAULT 0,
                memo            TEXT
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS series_ask_count (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                series_key  TEXT NOT NULL,
                week_bucket TEXT NOT NULL,
                ask_count   INTEGER DEFAULT 0,
                UNIQUE(series_key, week_bucket)
            )
        """);

        skuExtractor = new SkuFactExtractor(jdbc);
        askTracker = new AskCountTracker(jdbc);
        parentBuilder = new SeriesParentBuilder(jdbc, skuExtractor, askTracker);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS series_ask_count");
        jdbc.execute("DROP TABLE IF EXISTS car_sku");
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    @Test
    @DisplayName("子块提取：单条 SKU → AtomicFact（含 entity_id + hash + 时序标记）")
    void extractSingleSkuChild() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                100, "比亚迪", "宋PLUS DM-i", "比亚迪 宋PLUS DM-i 2025款 旗舰型",
                "雪域白", "16.98万", 15880000, "中规", 2, "杭州猛禽", 1);

        List<AtomicFact> facts = skuExtractor.extractAll();
        assertThat(facts).hasSize(1);

        AtomicFact f = facts.get(0);
        assertThat(f.getFactId()).isEqualTo("sku-100");
        assertThat(f.getEntityId()).isEqualTo("entity:car:比亚迪:宋plus-dm-i");
        assertThat(f.getTemporalType()).isEqualTo(TemporalType.DYNAMIC);
        assertThat(f.getSourceDoc()).isEqualTo("car_sku表");
        assertThat(f.getSourceHash()).isNotNull().hasSize(64);
        // 新 render 只保留指导价（16.98万），不再输出全款销售价（sale_price）
        assertThat(f.getContent()).contains("比亚迪", "宋PLUS DM-i", "雪域白", "16.98万", "杭州猛禽");
        assertThat(f.getMetadata()).containsEntry("level", "child");
        assertThat(f.getMetadata()).containsEntry("parent_series_id", "比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("子块过滤：只提取上架且未删除的")
    void onlyExtractsActiveSkus() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, sale_status) VALUES (1,'比亚迪','汉EV','汉EV','黑','24.98万','中规',2,'杭州',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, sale_status) VALUES (2,'比亚迪','汉EV','汉EV','白','25.98万','中规',2,'杭州',0)"); // 下架
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, is_deleted) VALUES (3,'比亚迪','汉EV','汉EV','灰','26.98万','中规',2,'杭州',1)"); // 删除

        List<AtomicFact> facts = skuExtractor.extractAll();
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).getFactId()).isEqualTo("sku-1");
    }

    @Test
    @DisplayName("父块构建：聚合车系统计")
    void buildParentWithAggregation() {
        // 同一车系下 2 款不同 SKU
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (1,'比亚迪','宋PLUS DM-i','宋PLUS DM-i 旗舰','雪域白','16.98万',15880000,'中规',2,'杭州',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (2,'比亚迪','宋PLUS DM-i','宋PLUS DM-i 尊贵','时光灰','17.98万',16880000,'中规',2,'杭州',1)");

        AtomicFact parent = parentBuilder.buildParent("比亚迪", "宋PLUS DM-i");
        assertThat(parent).isNotNull();
        assertThat(parent.getMetadata()).containsEntry("level", "parent");
        assertThat(parent.getMetadata()).containsEntry("sku_count", 2);
        assertThat(parent.getEntityId()).isEqualTo("entity:car:比亚迪:宋plus-dm-i");
        assertThat(parent.getTemporalType()).isEqualTo(TemporalType.STATIC);
        assertThat(parent.getContent()).contains("在售车型：2款", "15.88万", "16.88万");

        // 验证哈希一致性
        AtomicFact parent2 = parentBuilder.buildParent("比亚迪", "宋PLUS DM-i");
        assertThat(parent.getSourceHash()).isEqualTo(parent2.getSourceHash());
    }

    @Test
    @DisplayName("截断锚点契约：MODELS_SECTION 在父块文本中出现且唯一")
    void modelsSectionAnchorContract() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (1,'比亚迪','宋PLUS DM-i','宋PLUS DM-i 旗舰','雪域白','16.98万',15880000,'中规',2,'杭州',1)");

        AtomicFact parent = parentBuilder.buildParent("比亚迪", "宋PLUS DM-i");
        assertThat(parent).isNotNull();
        String content = parent.getContent();

        // 下游分层检索按此锚点截断"在售款型"段——锚点缺失或不唯一都会让截断悄悄失效
        int first = content.indexOf(SeriesParentBuilder.MODELS_SECTION);
        assertThat(first).as("父块文本包含截断锚点").isGreaterThanOrEqualTo(0);
        assertThat(content.indexOf(SeriesParentBuilder.MODELS_SECTION, first + 1))
                .as("截断锚点只出现一次").isEqualTo(-1);
    }

    @Test
    @DisplayName("父块构建：无在售车源时返回 null")
    void buildParentReturnsNullForEmpty() {
        AtomicFact parent = parentBuilder.buildParent("不存在的品牌", "不存在的车系");
        assertThat(parent).isNull();
    }

    @Test
    @DisplayName("buildAllParents 覆盖所有车系")
    void buildAllParentsCoversAllSeries() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, sale_status) VALUES (1,'比亚迪','汉EV','汉EV','黑','24.98万','中规',2,'杭州',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, sale_status) VALUES (2,'特斯拉','Model Y','Model Y','灰','26.35万','中规',2,'深圳',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, spec_name, energy_type, owner_name, sale_status) VALUES (3,'特斯拉','Model Y','Model Y','白','30.35万','中规',2,'深圳',1)");

        List<AtomicFact> parents = parentBuilder.buildAllParents();
        assertThat(parents).hasSize(2); // 比亚迪+汉EV, 特斯拉+Model Y
        assertThat(parents).allMatch(p -> "parent".equals(p.getMetadata().get("level")));
    }

    @Test
    @DisplayName("父块内容包含热度信息（#4）")
    void parentContentIncludesHeat() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (1,'理想','理想L6','理想L6 Pro','白','24.98万',23980000,'中规',2,'理想汽车',1)");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name, model_name, outer_color_name, guide_price, sale_price, spec_name, energy_type, owner_name, sale_status) VALUES (2,'理想','理想L6','理想L6 Max','灰','27.98万',26980000,'中规',2,'理想汽车',1)");

        // 记录一些询问
        askTracker.recordMention("理想-理想L6");
        askTracker.recordMention("理想-理想L6");

        AtomicFact parent = parentBuilder.buildParent("理想", "理想L6");
        assertThat(parent).isNotNull();
        assertThat(parent.getMetadata()).containsKey("heat");
        assertThat(((Number) parent.getMetadata().get("heat")).doubleValue()).isGreaterThanOrEqualTo(2.0);
        assertThat(parent.getContent()).contains("近期热度");
    }
}