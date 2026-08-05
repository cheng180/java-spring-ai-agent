package org.example.ai.impl.profile;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 客户画像表 DDL（#39 ticket）。
 *
 * <p>独立出来供 {@code DatabaseInitializer}（启动建表）与测试（临时 SQLite）共用，
 * 保证两处 schema 一致。</p>
 */
public final class CustomerProfileSchema {

    private CustomerProfileSchema() {}

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS customer_profile (
                channel            TEXT NOT NULL,
                external_user_id   TEXT NOT NULL,
                city               TEXT,
                phone              TEXT,
                preference_signals TEXT DEFAULT '{}',
                first_seen         TEXT NOT NULL,
                last_seen          TEXT NOT NULL,
                PRIMARY KEY (channel, external_user_id)
            )
        """);
    }
}