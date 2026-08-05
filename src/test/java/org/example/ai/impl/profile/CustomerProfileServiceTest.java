package org.example.ai.impl.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CustomerProfileService 编排测试（#39 ticket，接缝 B）：
 * 抽取 → 与既有画像合并（新值缺失不覆盖旧值）→ upsert。
 */
class CustomerProfileServiceTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private CustomerProfileService service;
    private CustomerProfileRepository repo;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-profile-service-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        CustomerProfileSchema.create(jdbc);
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

        repo = new CustomerProfileRepository(jdbc);
        service = new CustomerProfileService(repo, new ProfileExtractor(jdbc), new NeedSignalDetector());
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    @Test
    @DisplayName("一条含城市+电话+偏好的消息 → 画像行字段齐全")
    void captureCreatesFullProfile() {
        service.capture("macan", "wx-1", "我在北京，电话13812345678，想看20万以内的SUV");

        Optional<CustomerProfile> p = repo.find("macan", "wx-1");
        assertThat(p).isPresent();
        assertThat(p.get().city()).isEqualTo("北京");
        assertThat(p.get().phone()).isEqualTo("13812345678");
        assertThat(p.get().preferenceSignals())
                .contains("budget").contains("carType");
    }

    @Test
    @DisplayName("跨轮合并：后轮新信息追加，旧信息不丢")
    void captureMergesAcrossTurns() {
        service.capture("macan", "wx-2", "我在杭州");
        String firstSeen = repo.find("macan", "wx-2").get().firstSeen();

        service.capture("macan", "wx-2", "我电话15900001111，主要家用");

        CustomerProfile p = repo.find("macan", "wx-2").get();
        assertThat(p.city()).isEqualTo("杭州");          // 第一轮的城市还在
        assertThat(p.phone()).isEqualTo("15900001111");  // 第二轮的电话追加
        assertThat(p.preferenceSignals()).contains("use");
        assertThat(p.firstSeen()).isEqualTo(firstSeen);  // first_seen 不被覆盖
    }

    @Test
    @DisplayName("偏好信号按键合并：budget/carType 与 use 共存")
    void preferenceSignalsMergePerKey() {
        service.capture("web", "u-3", "20万的SUV");
        service.capture("web", "u-3", "家用的，混动优先");

        String signals = repo.find("web", "u-3").get().preferenceSignals();
        assertThat(signals).contains("budget").contains("carType")
                .contains("use").contains("energy");
    }

    @Test
    @DisplayName("无信号消息不落库也不误抓")
    void noSignalMessageIsNoop() {
        service.capture("macan", "wx-4", "推荐一款车");

        assertThat(repo.find("macan", "wx-4")).isEmpty();
    }

    @Test
    @DisplayName("空 ID / 空消息安全返回，不抛异常")
    void blankInputsAreSafe() {
        service.capture("macan", null, "我在北京");
        service.capture("macan", "", "我在北京");
        service.capture("macan", "wx-5", null);
        service.capture("macan", "wx-5", "   ");

        assertThat(repo.find("macan", "wx-5")).isEmpty();
    }
}