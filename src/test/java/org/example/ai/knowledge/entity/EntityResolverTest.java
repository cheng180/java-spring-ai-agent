package org.example.ai.knowledge.entity;

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
 * EntityResolver 集成测试（#5 ticket）。
 *
 * 使用临时 SQLite 数据库构造 entity_mapping 表，测试索引构建与解析。
 * 遵循项目已有测试模式（AskCountTrackerTest / DatabaseInitializerTest）。
 */
class EntityResolverTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private EntityResolver resolver;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-entity-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_mapping (
                entity_id       TEXT    NOT NULL,
                display_name    TEXT    NOT NULL,
                aliases_json    TEXT    DEFAULT '[]',
                PRIMARY KEY (entity_id, display_name)
            )
        """);

        // 种子数据（对齐 DatabaseInitializer.seedEntityMapping 的实际格式）
        jdbc.update("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)",
                "entity:car:byd:song-plus-dm-i", "比亚迪-宋PLUS DM-i",
                "[\"BYD-宋PLUS DM-i\",\"Song Plus DM-i\",\"宋plus dm-i\"]");
        jdbc.update("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)",
                "entity:car:byd:han-ev", "比亚迪-汉EV",
                "[\"汉ev\",\"Han EV\"]");
        jdbc.update("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)",
                "entity:car:tesla:model-y", "特斯拉-Model Y",
                "[\"model y\",\"毛豆Y\"]");
        jdbc.update("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)",
                "entity:car:li-auto:ideal-l6", "理想-理想L6",
                "[\"理想l6\",\"l6\"]");

        resolver = new EntityResolver(jdbc);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS entity_mapping");
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 测试方法 ----

    @Test
    @DisplayName("displayName 精确匹配")
    void resolvesDisplayName() {
        List<ResolvedEntity> hits = resolver.resolve("比亚迪宋PLUS DM-i这款车怎么样");
        assertThat(hits).extracting(ResolvedEntity::displayName).contains("比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("中文别名归一匹配")
    void resolvesChineseAlias() {
        List<ResolvedEntity> hits = resolver.resolve("汉ev多少钱");
        assertThat(hits).extracting(ResolvedEntity::entityId)
                .contains("entity:car:byd:han-ev");
    }

    @Test
    @DisplayName("英文别名归一匹配")
    void resolvesEnglishAlias() {
        List<ResolvedEntity> hits = resolver.resolve("Han EV 续航");
        assertThat(hits).extracting(ResolvedEntity::entityId)
                .contains("entity:car:byd:han-ev");
    }

    @Test
    @DisplayName("大小写不敏感匹配")
    void caseInsensitiveMatch() {
        List<ResolvedEntity> hits = resolver.resolve("MODEL Y 有没有现车");
        assertThat(hits).extracting(ResolvedEntity::entityId)
                .contains("entity:car:tesla:model-y");
    }

    @Test
    @DisplayName("口语别名「毛豆Y」匹配")
    void resolvesSlangAlias() {
        List<ResolvedEntity> hits = resolver.resolve("想看毛豆Y");
        assertThat(hits).extracting(ResolvedEntity::displayName)
                .contains("特斯拉-Model Y");
    }

    @Test
    @DisplayName("同一消息多车系匹配，按长匹配优先，entity_id 去重")
    void deduplicatesByEntityIdPrefersLongMatch() {
        List<ResolvedEntity> hits = resolver.resolve("宋PLUS DM-i 和 l6 哪个好");
        assertThat(hits).hasSize(2);
        // 长别名"宋PLUS DM-i"排在短别名"l6"前面
        assertThat(hits.get(0).displayName()).isEqualTo("比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("hasMatch：命中返回 true")
    void hasMatchReturnsTrue() {
        // 精确别名命中
        assertThat(resolver.hasMatch("宋PLUS DM-i")).isTrue();
        assertThat(resolver.hasMatch("model y 怎么样")).isTrue();
        // token 回退匹配："比亚迪宋PLUS" tokens [比亚迪, 宋plus] 命中 "比亚迪-宋plus dm-i"
        assertThat(resolver.hasMatch("比亚迪宋PLUS")).isTrue();
    }

    @Test
    @DisplayName("hasMatch：未命中返回 false")
    void hasMatchReturnsFalse() {
        assertThat(resolver.hasMatch("天气真好")).isFalse();
        assertThat(resolver.hasMatch("")).isFalse();
    }

    @Test
    @DisplayName("resolve：空/空白输入安全")
    void resolveHandlesEmptyInput() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }

    @Test
    @DisplayName("resolve：完全不相关文本返回空")
    void resolveUnrelatedReturnsEmpty() {
        assertThat(resolver.resolve("今天吃了吗")).isEmpty();
    }

    @Test
    @DisplayName("resolve：token 回退匹配（用户缩写形式）")
    void tokenFallbackMatching() {
        // "比亚迪宋PLUS" → tokens [比亚迪, 宋plus] 全在别名"比亚迪-宋plus dm-i"中
        List<ResolvedEntity> hits = resolver.resolve("比亚迪宋PLUS");
        assertThat(hits).extracting(ResolvedEntity::entityId)
                .contains("entity:car:byd:song-plus-dm-i");
    }

    @Test
    @DisplayName("resolve：单 token 不触发回退匹配（防误匹配）")
    void singleTokenDoesNotFallback() {
        // "宋" 单 token → 不回退，避免图中所有"宋"系都被命中
        List<ResolvedEntity> hits = resolver.resolve("宋");
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("seriesKey 格式为 brand-series，与 AskCountTracker key 一致")
    void seriesKeyFormat() {
        List<ResolvedEntity> hits = resolver.resolve("宋PLUS DM-i");
        assertThat(hits.get(0).seriesKey()).isEqualTo("比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("aliasCount 返回别名索引总条目数")
    void aliasCountReturnsTotal() {
        // 4 displayName + 3+2+2+2 = 9 JSON 别名 = 13
        assertThat(resolver.aliasCount()).isEqualTo(13);
    }
}