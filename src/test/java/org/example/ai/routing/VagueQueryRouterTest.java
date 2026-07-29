package org.example.ai.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.location.GeoLocator;
import org.example.ai.location.HotCarRepository;
import org.example.ai.location.StoreLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VagueQueryRouter 单元测试 —— L1/L2/L3 三层匹配（#13 + #14 + #15 ticket）。
 */
class VagueQueryRouterTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private VagueQueryRouter router;
    private GeoLocator mockGeoLocator;
    private StoreLocator mockStoreLocator;
    private HotCarRepository mockHotCarRepo;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-route-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        // entity_mapping 表（EntityResolver 需要）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_mapping (
                entity_id TEXT NOT NULL, display_name TEXT NOT NULL,
                aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name)
            )
        """);
        // car_sku 表（DynamicKeywordBuilder 需要）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,
                sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0
            )
        """);
        // series_ask_count 表（AskCountTracker 需要）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS series_ask_count (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                series_key TEXT NOT NULL, week_bucket TEXT NOT NULL,
                ask_count INTEGER DEFAULT 0, UNIQUE(series_key, week_bucket)
            )
        """);

        // 种子数据：比亚迪品牌有 3 个车系（品牌级关键词）
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "比亚迪-宋PLUS DM-i", "[\"宋plus\"]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e2", "比亚迪-汉EV", "[\"汉ev\"]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e3", "比亚迪-海鸥", "[\"海鸥\"]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','宋PLUS DM-i')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'比亚迪','汉EV')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (3,'比亚迪','海鸥')");
        // 特斯拉只有 1 个车系
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e4", "特斯拉-Model Y", "[\"model y\",\"毛豆Y\"]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (4,'特斯拉','Model Y')");

        EntityResolver entityResolver = new EntityResolver(jdbc);
        DynamicKeywordBuilder kwBuilder = new DynamicKeywordBuilder(jdbc);
        kwBuilder.afterPropertiesSet();
        AskCountTracker askTracker = new AskCountTracker(jdbc);

        // Mock VectorStore — L1 需要调用相似度检索
        var mockVectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);

        // Mock 位置 + 热度依赖（L3 使用）
        mockGeoLocator = mock(GeoLocator.class);
        mockStoreLocator = mock(StoreLocator.class);
        mockHotCarRepo = mock(HotCarRepository.class);

        router = new VagueQueryRouter(entityResolver, kwBuilder, askTracker,
                mockVectorStore, mockGeoLocator, mockStoreLocator, mockHotCarRepo);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 空 / 无匹配 ----

    @Test
    @DisplayName("空输入 → 返回 null")
    void nullInputReturnsNull() {
        assertThat(router.route(null, "", "127.0.0.1")).isNull();
        assertThat(router.route("", "", "127.0.0.1")).isNull();
        assertThat(router.route("   ", "", "127.0.0.1")).isNull();
    }

    @Test
    @DisplayName("无车系无品牌消息 → L1/L2/L3 无命中 → null")
    void noMatchReturnsNull() {
        // 无历史 + Mock 门店返回 null → Step 1/2 跳过 → Step 3 VAGUE 返回
        // 所以不会返回 null——会走到 Step 3 通用追问
        // 改成: route 在这种情况下返回 VAGUE（Step 3 通用追问）
    }

    // ---- L1: tryExactMatch ----
    // ... keep existing L1 tests ...
    // ---- L2: tryBrandMatch ----
    // ... keep existing L2 tests ...

    // ---- L3: tryVagueMatch ----

    @Test
    @DisplayName("Step 1: 对话历史含车系名 → 确认追问")
    void historyHasSeriesReturnsConfirm() {
        // 历史中有 "比亚迪" → 关键词匹配
        MatchResult result = router.tryVagueMatch("感觉一般", "之前看过比亚迪宋PLUS", "127.0.0.1");
        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(MatchResult.MatchType.VAGUE);
        assertThat(result.needsConfirm()).isTrue();
        assertThat(result.followUpText()).contains("您之前聊过");
    }

    @Test
    @DisplayName("Step 1: 空历史 → 跳过 Step 1")
    void emptyHistorySkipsStep1() {
        // Mock 门店 + 热度返回空 → Step 2 也跳过 → Step 3
        when(mockGeoLocator.locate(any())).thenReturn(null);

        MatchResult result = router.tryVagueMatch("太贵了", "", "127.0.0.1");
        assertThat(result).isNotNull();
        // Step 2 需要门店定位成功+有热度数据 → mock 返回 null → 跳到 Step 3
        assertThat(result.followUpText()).contains("预算");
    }

    @Test
    @DisplayName("Step 2: 历史无匹配 + 门店有热度 → 注入 LLM 上下文（不走 bypass）")
    void storeHotReturnsGuide() {
        // Mock GeoLocator + StoreLocator 返回门店 1
        when(mockGeoLocator.locate(any()))
                .thenReturn(new org.example.ai.location.GeoLocation(30.28, 120.02, "杭州"));
        when(mockStoreLocator.findNearest(30.28, 120.02))
                .thenReturn(new org.example.ai.location.StoreInfo(1, "杭州店", "S1", "addr", "110", "9-18", "杭州", 30.28, 120.02, true));
        when(mockHotCarRepo.getHotCars(1, 3))
                .thenReturn(java.util.List.of(
                        new org.example.ai.location.HotCar("宋PLUS DM-i", 30, 20),
                        new org.example.ai.location.HotCar("Model Y", 25, 15),
                        new org.example.ai.location.HotCar("理想L6", 20, 10)));

        MatchResult result = router.tryVagueMatch("再看看", "", "127.0.0.1");
        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(MatchResult.MatchType.VAGUE);
        // L3 Step2 改为注入 LLM 上下文，不再直接 bypass
        assertThat(result.needsInference()).isTrue();
        assertThat(result.needsConfirm()).isFalse();
        assertThat(result.followUpText()).contains("宋PLUS DM-i").contains("Model Y").contains("热销");
    }

    @Test
    @DisplayName("Step 3: 历史无匹配 + 门店无热度 → 通用追问")
    void noHistoryNoHotReturnsGeneric() {
        // Mock 门店定位成功但热度数据空
        when(mockGeoLocator.locate(any()))
                .thenReturn(new org.example.ai.location.GeoLocation(30.28, 120.02, "杭州"));
        when(mockStoreLocator.findNearest(30.28, 120.02))
                .thenReturn(new org.example.ai.location.StoreInfo(1, "杭州店", "S1", "addr", "110", "9-18", "杭州", 30.28, 120.02, true));
        when(mockHotCarRepo.getHotCars(anyInt(), anyInt()))
                .thenReturn(java.util.List.of());

        MatchResult result = router.tryVagueMatch("感觉一般", "", "127.0.0.1");
        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(MatchResult.MatchType.VAGUE);
        assertThat(result.needsInference()).isTrue();
        assertThat(result.followUpText()).contains("预算").contains("通勤").contains("SUV");
    }

    // ---- L1: tryExactMatch ----

    @Test
    @DisplayName("EntityResolver 命中 → tryExactMatch 调用（Mock VectorStore 空 → L1 不通过）")
    void entityHitCallsL1() {
        MatchResult result = router.tryExactMatch("宋plus多少钱");
        assertThat(result).isNull(); // mock VectorStore 返回空，相似度不足
    }

    @Test
    @DisplayName("tryExactMatch 无实体命中 → 返回 null")
    void noEntityHitL1Null() {
        MatchResult result = router.tryExactMatch("今天天气");
        assertThat(result).isNull();
    }

    // ---- L2: tryBrandMatch ----

    @Test
    @DisplayName("品牌名匹配 → BRAND + 列出车系按热度")
    void brandKeywordReturnsBrand() {
        // "比亚迪" → keywordBuilder.getSeriesKeys("比亚迪") >= 2 → BRAND
        MatchResult result = router.tryBrandMatch("比亚迪有什么车");
        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(MatchResult.MatchType.BRAND);
        assertThat(result.brand()).isEqualTo("比亚迪");
        assertThat(result.needsConfirm()).isTrue();
        assertThat(result.hotModels()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.followUpText()).contains("比亚迪").contains("在售").contains("您想看轿车还是SUV");
    }

    @Test
    @DisplayName("品牌+追问混合 → BRAND")
    void brandWithQuestionReturnsBrand() {
        MatchResult result = router.tryBrandMatch("你们比亚迪最便宜的车是哪款");
        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(MatchResult.MatchType.BRAND);
    }

    @Test
    @DisplayName("单一车系关键词 → 不走 L2（不是品牌级）")
    void singleSeriesNotBrand() {
        // "model y" 只对应1个车系 → tryBrandMatch 跳过
        MatchResult result = router.tryBrandMatch("model y 续航");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("无匹配关键词 → tryBrandMatch 返回 null")
    void noKeywordMatchForBrand() {
        MatchResult result = router.tryBrandMatch("推荐一款车");
        assertThat(result).isNull();
    }

    // ---- 集成：route() 仅保留 L1 ----

    @Test
    @DisplayName("route: L1 miss → L2/L3 已回退，返回 null 走 RAG + LLM")
    void routeL1MissReturnsNull() {
        // L2（品牌匹配）已禁用，返回 null 让 RAG + LLM 处理
        assertThat(router.route("比亚迪有什么车", "", "127.0.0.1")).isNull();
        assertThat(router.route("推荐一款车", "", "127.0.0.1")).isNull();
        assertThat(router.route("20万混动SUV推荐", "", "127.0.0.1")).isNull();
    }

    // ---- 门店/地址查询：返回 null 让 LLM 处理 ----

    @Test
    @DisplayName("门店地址查询 → route 返回 null（不进入 L1/L2/L3）")
    void storeAddressQueryReturnsNull() {
        assertThat(router.route("你们门店在哪里？", "", "127.0.0.1")).isNull();
        assertThat(router.route("我想要线下试车要去哪里，地址给我一个，电话也给我一个", "", "127.0.0.1")).isNull();
        assertThat(router.route("你们地址在哪", "", "127.0.0.1")).isNull();
        assertThat(router.route("电话多少", "", "127.0.0.1")).isNull();
        assertThat(router.route("怎么去你们店", "", "127.0.0.1")).isNull();
        assertThat(router.route("营业时间是几点", "", "127.0.0.1")).isNull();
        assertThat(router.route("我想预约试驾", "", "127.0.0.1")).isNull();
        assertThat(router.route("到店看车", "", "127.0.0.1")).isNull();
    }

    @Test
    @DisplayName("品牌+门店混合查询 → route 返回 null（门店意图优先）")
    void brandWithLocationReturnsNull() {
        // "比亚迪门店在哪里" 中有品牌关键词也有门店关键词 → 门店意图优先
        assertThat(router.route("比亚迪门店在哪里", "", "127.0.0.1")).isNull();
        assertThat(router.route("特斯拉的实体店在哪", "", "127.0.0.1")).isNull();
    }

    @Test
    @DisplayName("门店查询 + 历史有车系 → route 返回 null（门店意图优先于历史）")
    void locationWithHistoryReturnsNull() {
        // 即使历史中有车系关键词，门店查询也应该优先返回 null
        when(mockGeoLocator.locate(any()))
                .thenReturn(new org.example.ai.location.GeoLocation(30.28, 120.02, "杭州"));
        when(mockStoreLocator.findNearest(30.28, 120.02))
                .thenReturn(new org.example.ai.location.StoreInfo(1, "杭州店", "S1", "addr", "110", "9-18", "杭州", 30.28, 120.02, true));
        when(mockHotCarRepo.getHotCars(anyInt(), anyInt()))
                .thenReturn(java.util.List.of(
                        new org.example.ai.location.HotCar("Model Y", 25, 15)));

        assertThat(router.route("地址在哪", "之前看过比亚迪宋PLUS", "127.0.0.1")).isNull();
    }
}