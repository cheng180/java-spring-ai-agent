package org.example.ai.impl.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProfileExtractor 单元测试（#39 ticket，接缝 B）。
 * 电话 = 11 位手机号识别；城市 = 常见城市词表 + store_config.region 动态词。
 */
class ProfileExtractorTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private ProfileExtractor extractor;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-profile-extract-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_name TEXT, store_code TEXT, address TEXT, phone TEXT,
                working_hours TEXT, region TEXT, latitude REAL DEFAULT 0,
                longitude REAL DEFAULT 0, is_active INTEGER DEFAULT 1
            )
        """);
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region) VALUES (?,?,?,?)",
                "朝阳旗舰店", "S1", "北京市朝阳区", "朝阳区");
        jdbc.update("INSERT INTO store_config (store_name, store_code, address, region) VALUES (?,?,?,?)",
                "浦东展厅", "S2", "上海市浦东新区", "浦东新区");

        extractor = new ProfileExtractor(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 电话 ----

    @Test
    @DisplayName("句中 11 位手机号被识别")
    void phoneInSentence() {
        assertThat(extractor.extractPhone("我的电话是13812345678，方便联系"))
                .contains("13812345678");
        assertThat(extractor.extractPhone("15999998888 记一下"))
                .contains("15999998888");
    }

    @Test
    @DisplayName("非手机号不误抓：座机/位数不对/订单号")
    void phoneFalsePositives() {
        assertThat(extractor.extractPhone("门店电话010-88886666")).isEmpty();
        assertThat(extractor.extractPhone("编号20260805123456的订单")).isEmpty();
        assertThat(extractor.extractPhone("12345678901")).isEmpty(); // 1 开头但第二位 <3
    }

    @Test
    @DisplayName("无电话 → 空")
    void noPhone() {
        assertThat(extractor.extractPhone("我想看看SUV")).isEmpty();
    }

    // ---- 城市 ----

    @Test
    @DisplayName("常见城市词表命中")
    void commonCityHit() {
        assertThat(extractor.extractCity("我在北京，想去看车")).contains("北京");
        assertThat(extractor.extractCity("杭州的店在什么位置")).contains("杭州");
    }

    @Test
    @DisplayName("store_config.region 动态词命中（区级表述）")
    void regionVocabHit() {
        assertThat(extractor.extractCity("我在朝阳区上班")).contains("朝阳区");
        assertThat(extractor.extractCity("浦东那边有车吗")).contains("浦东新区");
    }

    @Test
    @DisplayName("无城市信号 → 空")
    void noCity() {
        assertThat(extractor.extractCity("推荐一款车")).isEmpty();
        assertThat(extractor.extractCity("20万的SUV")).isEmpty();
    }
}