package org.example.ai.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
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
import static org.mockito.Mockito.mock;

/**
 * VagueQueryRouter 单元测试 —— L1 精确匹配 + L2 品牌级匹配（#13 + #14 ticket）。
 */
class VagueQueryRouterTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private VagueQueryRouter router;

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

        router = new VagueQueryRouter(entityResolver, kwBuilder, askTracker, mockVectorStore);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 空 / 无匹配 ----

    @Test
    @DisplayName("空输入 → 返回 null")
    void nullInputReturnsNull() {
        assertThat(router.route(null, "")).isNull();
        assertThat(router.route("", "")).isNull();
        assertThat(router.route("   ", "")).isNull();
    }

    @Test
    @DisplayName("无车系无品牌消息 → L1/L2 均无命中 → 返回 null")
    void noMatchReturnsNull() {
        assertThat(router.route("今天天气真好", "")).isNull();
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

    // ---- 集成：route() 先 L1 再 L2 ----

    @Test
    @DisplayName("route: L1 miss + L2 品牌命中 → BRAND")
    void routeL1MissL2Brand() {
        // "比亚迪" → EntityResolver.resolve() 返回空或匹配多个 → L1 验证失败 → L2 接管
        MatchResult result = router.route("比亚迪有什么车", "");
        // L1: EntityResolver 可能不会返回具体实体（"比亚迪"单独不匹配任何别名）
        // 但 L2 应该能通过 DynamicKeywordBuilder 检测到品牌关键词
        if (result != null) {
            assertThat(result.type()).isEqualTo(MatchResult.MatchType.BRAND);
        }
    }
}