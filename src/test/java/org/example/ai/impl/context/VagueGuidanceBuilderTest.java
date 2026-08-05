package org.example.ai.impl.context;

import org.example.ai.impl.location.HotCarRepository;
import org.example.ai.impl.location.StoreLocator;
import org.example.ai.impl.profile.CustomerProfile;
import org.example.ai.impl.profile.CustomerProfileRepository;
import org.example.ai.impl.profile.CustomerProfileSchema;
import org.example.ai.impl.routing.VagueAssessment;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VagueGuidanceBuilder 测试（#41 ticket，#35 规格决策 6/8）。
 *
 * <p>临时 SQLite 模式（先例：StoreLocatorTest / HotCarRepositoryTest /
 * CustomerProfileRepositoryTest）：门店优先素材、画像城市跨轮升级、
 * 无城市反问（禁止静默退回全局）。</p>
 */
class VagueGuidanceBuilderTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private CustomerProfileRepository profileRepository;
    private VagueGuidanceBuilder builder;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-guidance-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT, store_name TEXT NOT NULL,
                store_code TEXT UNIQUE NOT NULL, address TEXT NOT NULL, phone TEXT,
                working_hours TEXT DEFAULT '9:00-18:00', region TEXT,
                latitude REAL DEFAULT 0, longitude REAL DEFAULT 0, is_active INTEGER DEFAULT 1
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_car_hot (
                id INTEGER PRIMARY KEY AUTOINCREMENT, store_id INTEGER NOT NULL,
                series_name TEXT NOT NULL, sale_count INTEGER DEFAULT 0,
                inquiry_count INTEGER DEFAULT 0, stat_date DATE
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS series_ask_count (
                id INTEGER PRIMARY KEY AUTOINCREMENT, series_key TEXT NOT NULL,
                week_bucket TEXT NOT NULL, ask_count INTEGER DEFAULT 0,
                UNIQUE(series_key, week_bucket)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,
                sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0
            )""");
        CustomerProfileSchema.create(jdbc);

        profileRepository = new CustomerProfileRepository(jdbc);
        builder = new VagueGuidanceBuilder(
                new StoreLocator(jdbc), new HotCarRepository(jdbc),
                new AskCountTracker(jdbc), profileRepository);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 档 0 / null：不注入 ----

    @Test
    @DisplayName("档 0 清晰或 null 评定 → 不生成引导（直接答）")
    void clearTierProducesNoGuidance() {
        assertThat(builder.build(null, "u-1")).isNull();
        assertThat(builder.build(new VagueAssessment(
                VagueAssessment.TIER_CLEAR, 0.9, List.of("宝马X3 M"), Map.of()), "u-1")).isNull();
    }

    // ---- 档 1：浅模糊选项确认 ----

    @Test
    @DisplayName("档 1：候选按热度取 top 1-2 作为选项，不展开其他车系")
    void lightTierOffersTopTwoOptions() {
        VagueAssessment a = new VagueAssessment(VagueAssessment.TIER_LIGHT, 0.6,
                List.of("比亚迪-秦L", "比亚迪-秦PLUS DM-i", "比亚迪-汉EV"), Map.of());

        String g = builder.build(a, "u-1");

        assertThat(g).contains("秦L").contains("秦PLUS DM-i");
        assertThat(g).doesNotContain("汉EV"); // 第三个候选不进选项
        assertThat(g).contains("确认");
    }

    @Test
    @DisplayName("档 1 但候选为空 → 不注入（fail-safe）")
    void lightTierWithoutCandidatesIsSkipped() {
        assertThat(builder.build(new VagueAssessment(
                VagueAssessment.TIER_LIGHT, 0.6, List.of(), Map.of()), "u-1")).isNull();
    }

    @Test
    @DisplayName("档 1：热度高的候选排前（#35 决策：给热度 top 1-2 选项）")
    void lightTierRanksOptionsByHeat() {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','秦L')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'比亚迪','汉EV')");
        // 汉EV 有询问热度，秦L 无 → 汉EV 排前
        new AskCountTracker(jdbc).recordMention("比亚迪-汉EV");

        VagueAssessment a = new VagueAssessment(VagueAssessment.TIER_LIGHT, 0.6,
                List.of("比亚迪-秦L", "比亚迪-汉EV"), Map.of());

        String g = builder.build(a, "u-1");

        assertThat(g.indexOf("汉EV")).isLessThan(g.indexOf("秦L")); // 热度降序
    }

    // ---- 档 2：中模糊匹配/补问 ----

    @Test
    @DisplayName("档 2 有候选 → 推荐 1-2 款并说明适合理由")
    void mediumTierWithCandidatesRecommends() {
        VagueAssessment a = new VagueAssessment(VagueAssessment.TIER_MEDIUM, 0.5,
                List.of("比亚迪-宋PLUS DM-i"), Map.of("budget", "20万", "energy", "混动"));

        String g = builder.build(a, "u-1");

        assertThat(g).contains("宋PLUS DM-i");
        assertThat(g).contains("预算=20万").contains("能源=混动");
        assertThat(g).contains("1-2 款");
    }

    @Test
    @DisplayName("档 2 无候选 → 补问最缺维度（budget 优先于 use）")
    void mediumTierWithoutCandidatesAsksMissingDimension() {
        VagueAssessment a = new VagueAssessment(VagueAssessment.TIER_MEDIUM, 0.4,
                List.of(), Map.of("use", "通勤"));

        String g = builder.build(a, "u-1");

        assertThat(g).contains("预算");       // 最缺维度 = budget
        assertThat(g).contains("一次只问这一个问题");
    }

    @Test
    @DisplayName("档 2 补问维度跳过画像已知偏好（决策 8：偏好已知不重复问）")
    void mediumTierSkipsProfileKnownDimensions() {
        // 画像历史已记 budget → 本轮 use=通勤 时最缺维度应跳过 budget 问 carType
        profileRepository.upsert(new CustomerProfile(
                "web", "u-budget-known", null, null, "{\"budget\":\"20万\"}", "t", "t"));

        VagueAssessment a = new VagueAssessment(VagueAssessment.TIER_MEDIUM, 0.4,
                List.of(), Map.of("use", "通勤"));

        String g = builder.build(a, "u-budget-known");

        assertThat(g).contains("车型");     // 最缺维度 = carType（budget 画像已知）
        assertThat(g).doesNotContain("预算大概"); // 不再重复问预算
    }

    // ---- 档 3：深模糊门店优先（决策 6） ----

    @Test
    @DisplayName("档 3 画像无城市 → 反问城市（带好处话术），禁止推荐具体车型")
    void deepTierWithoutCityAsksCity() {
        String g = builder.build(deep(), "u-no-city");

        assertThat(g).contains("哪个城市");
        assertThat(g).contains("附近店哪款卖得最好"); // 好处话术
        assertThat(g).contains("不要推荐具体车型");
    }

    @Test
    @DisplayName("档 3 userId 为空 → 按无画像处理，同样反问城市")
    void deepTierWithNullUserAsksCity() {
        assertThat(builder.build(deep(), null)).contains("哪个城市");
    }

    @Test
    @DisplayName("档 3 画像有城市 → 最近门店热销 top 1-2 作起点，不重复问城市")
    void deepTierWithCityUsesStoreHot() {
        seedProfile("u-hz", "杭州");
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region)"
                + " VALUES ('杭州西湖店','HZ-01','西湖区文一路1号','杭州')");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date)"
                + " VALUES (1,'宋PLUS DM-i',30,20,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date)"
                + " VALUES (1,'汉EV',20,15,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date)"
                + " VALUES (1,'海鸥',5,3,date('now'))");

        String g = builder.build(deep(), "u-hz");

        assertThat(g).contains("杭州西湖店");
        assertThat(g).contains("宋PLUS DM-i").contains("汉EV");
        assertThat(g).doesNotContain("海鸥");        // 热销只取 top2
        assertThat(g).contains("不要重复询问城市");
        assertThat(g).doesNotContain("您在哪个城市"); // 城市已知不再问（决策 8）
    }

    @Test
    @DisplayName("档 3 城市无门店 → 全局热度兜底（禁止静默退回全局 ≠ 禁止全局兜底素材）")
    void deepTierFallsBackToGlobalHeatWhenNoStore() {
        seedProfile("u-sh", "上海");
        // 无 store_config 记录，但有全局热度数据
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','唐DM-i')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'比亚迪','元PLUS')");
        new AskCountTracker(jdbc).recordMention("比亚迪-唐DM-i"); // 当前周桶 +1

        String g = builder.build(deep(), "u-sh");

        assertThat(g).contains("唐DM-i");            // 全局热度兜底素材
        assertThat(g).contains("不要重复询问城市");
    }

    @Test
    @DisplayName("档 3 城市已知但门店/热度数据全缺 → 轻问题引导，仍不问城市")
    void deepTierWithCityButNoDataGuidesGently() {
        seedProfile("u-cd", "成都");

        String g = builder.build(deep(), "u-cd");

        assertThat(g).contains("成都");
        assertThat(g).contains("不要重复询问城市");
        assertThat(g).contains("轻问题"); // 无素材时退化为开放式轻引导
    }

    // ---- helpers ----

    private static VagueAssessment deep() {
        return new VagueAssessment(VagueAssessment.TIER_DEEP, 0.1, List.of(), Map.of());
    }

    private void seedProfile(String userId, String city) {
        profileRepository.upsert(new CustomerProfile(
                "web", userId, city, null, "{}", "2026-08-05T00:00:00Z", "2026-08-05T00:00:00Z"));
    }
}