package org.example.ai.impl.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 客户画像仓储（#39 ticket，接缝 B）。
 *
 * <p>纯存取：upsert 按 (channel, external_user_id) 冲突更新，
 * first_seen 保留首次值；字段合并语义（新值缺失不覆盖旧值）在
 * {@link CustomerProfileService} 层，不在本层。</p>
 */
@Component
public class CustomerProfileRepository {

    private final JdbcTemplate jdbc;

    public CustomerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(CustomerProfile p) {
        jdbc.update("""
                INSERT INTO customer_profile
                    (channel, external_user_id, city, phone, preference_signals, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(channel, external_user_id) DO UPDATE SET
                    city = excluded.city,
                    phone = excluded.phone,
                    preference_signals = excluded.preference_signals,
                    last_seen = excluded.last_seen
                """,
                p.channel(), p.externalUserId(), p.city(), p.phone(),
                p.preferenceSignals(), p.firstSeen(), p.lastSeen());
    }

    public Optional<CustomerProfile> find(String channel, String externalUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM customer_profile WHERE channel = ? AND external_user_id = ?",
                channel, externalUserId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        return Optional.of(new CustomerProfile(
                channel,
                externalUserId,
                str(r.get("city")),
                str(r.get("phone")),
                str(r.get("preference_signals")),
                str(r.get("first_seen")),
                str(r.get("last_seen"))
        ));
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }
}