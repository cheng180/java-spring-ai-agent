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
 * CustomerProfileRepository 单元测试（#39 ticket，接缝 B）。
 * 临时 SQLite 模式（先例：HotCarRepositoryTest / StoreLocatorTest）。
 */
class CustomerProfileRepositoryTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private CustomerProfileRepository repo;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-profile-repo-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        CustomerProfileSchema.create(jdbc);
        repo = new CustomerProfileRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    @Test
    @DisplayName("upsert 新画像 → find 取回全字段")
    void upsertThenFind() {
        repo.upsert(new CustomerProfile("macan", "wx-001", "杭州", "13812345678",
                "{\"budget\":\"20万以内\"}", "2026-08-05T10:00:00Z", "2026-08-05T10:00:00Z"));

        Optional<CustomerProfile> found = repo.find("macan", "wx-001");
        assertThat(found).isPresent();
        assertThat(found.get().city()).isEqualTo("杭州");
        assertThat(found.get().phone()).isEqualTo("13812345678");
        assertThat(found.get().preferenceSignals()).isEqualTo("{\"budget\":\"20万以内\"}");
    }

    @Test
    @DisplayName("同渠道同用户二次 upsert → 覆盖更新而非新增行")
    void upsertUpdatesExistingRow() {
        repo.upsert(new CustomerProfile("macan", "wx-001", "杭州", null,
                "{}", "2026-08-05T10:00:00Z", "2026-08-05T10:00:00Z"));
        repo.upsert(new CustomerProfile("macan", "wx-001", "杭州", "13812345678",
                "{\"use\":\"通勤\"}", "2026-08-05T10:00:00Z", "2026-08-05T11:00:00Z"));

        assertThat(repo.find("macan", "wx-001")).isPresent();
        CustomerProfile p = repo.find("macan", "wx-001").get();
        assertThat(p.phone()).isEqualTo("13812345678");
        assertThat(p.preferenceSignals()).isEqualTo("{\"use\":\"通勤\"}");
        assertThat(p.firstSeen()).isEqualTo("2026-08-05T10:00:00Z");
        assertThat(p.lastSeen()).isEqualTo("2026-08-05T11:00:00Z");
        // 只有一行
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_profile WHERE channel='macan' AND external_user_id='wx-001'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("渠道隔离：同 external_user_id 不同渠道互不干扰")
    void channelIsolation() {
        repo.upsert(new CustomerProfile("web", "u-1", "北京", null,
                "{}", "t1", "t1"));
        repo.upsert(new CustomerProfile("macan", "u-1", "上海", null,
                "{}", "t2", "t2"));

        assertThat(repo.find("web", "u-1").get().city()).isEqualTo("北京");
        assertThat(repo.find("macan", "u-1").get().city()).isEqualTo("上海");
    }

    @Test
    @DisplayName("未建档用户 → find 返回空")
    void findMissingReturnsEmpty() {
        assertThat(repo.find("macan", "ghost")).isEmpty();
    }
}