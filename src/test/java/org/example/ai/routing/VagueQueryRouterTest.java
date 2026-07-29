package org.example.ai.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VagueQueryRouter 单元测试 —— L1 精确匹配（#13 ticket）。
 *
 * L1 逻辑是纯代码，不依赖 VectorStore —— EntityResolver + 父块相似度阈值判断，
 * 所以用 Mock VectorStore 做单元测试。
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

        // 种子数据
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e1", "比亚迪-宋PLUS DM-i", "[\"宋plus\",\"song plus\"]");
        jdbc.update("INSERT INTO entity_mapping VALUES (?,?,?)",
                "e2", "特斯拉-Model Y", "[\"model y\",\"毛豆Y\"]");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (1,'比亚迪','宋PLUS DM-i')");
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (2,'特斯拉','Model Y')");

        EntityResolver entityResolver = new EntityResolver(jdbc);
        DynamicKeywordBuilder kwBuilder = new DynamicKeywordBuilder(jdbc);
        kwBuilder.afterPropertiesSet();

        // Mock VectorStore — L1 需要调用相似度检索验证父块
        var mockVectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);

        router = new VagueQueryRouter(entityResolver, kwBuilder, mockVectorStore);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- L1: 空 / 无匹配 ----

    @Test
    @DisplayName("空输入 → 返回 null")
    void nullInputReturnsNull() {
        assertThat(router.route(null, "")).isNull();
        assertThat(router.route("", "")).isNull();
        assertThat(router.route("   ", "")).isNull();
    }

    @Test
    @DisplayName("无车系消息 → EntityResolver 无命中 → 返回 null")
    void noMatchReturnsNull() {
        assertThat(router.route("今天天气真好", "")).isNull();
    }

    // ---- L1: tryExactMatch ----

    @Test
    @DisplayName("EntityResolver 命中 → tryExactMatch 调用（依赖 VectorStore mock）")
    void entityHitCallsL1() {
        // EntityResolver 能匹配 "宋plus"，但 mock VectorStore 返回空 → L1 不通过
        MatchResult result = router.tryExactMatch("宋plus多少钱");
        assertThat(result).isNull(); // mock VectorStore 返回空，相似度不足
    }

    @Test
    @DisplayName("EntityResolver 命中但无任何父块 → 返回 null")
    void entityHitNoParentReturnsNull() {
        // "model y" 命中了 EntityResolver，但 mock VectorStore 无数据
        MatchResult result = router.tryExactMatch("model y 怎么样");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("tryExactMatch 无实体命中 → 返回 null")
    void noEntityHitL1Null() {
        MatchResult result = router.tryExactMatch("今天天气");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("route 返回 null 时 forward 到后续流程（当前桩返回 null）")
    void routeReturnsNullForwardsCorrectly() {
        // 不匹配任何车系的消息 → EntityResolver 无命中 → route 返回 null
        assertThat(router.route("天气不错", "")).isNull();
    }
}