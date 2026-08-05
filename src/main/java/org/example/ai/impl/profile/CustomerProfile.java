package org.example.ai.impl.profile;

/**
 * 客户画像记录（#39 ticket）。
 *
 * <p>对话中出现的关键销售线索：位置（城市）、电话、偏好信号。
 * 主键为渠道 + 外部用户 ID——macan 用请求携带的企微 externalUserId，
 * web 用传入 userId，按渠道隔离不串话。</p>
 *
 * @param channel          渠道（"web" / "macan"）
 * @param externalUserId   渠道内稳定的外部用户标识
 * @param city             客户提到的城市（可为 null）
 * @param phone            客户留下的手机号（可为 null）
 * @param preferenceSignals 偏好信号 JSON（budget/use/carType/energy → 命中的表述），无则 "{}"
 * @param firstSeen        首次出现（ISO 时间）
 * @param lastSeen         最近一次出现（ISO 时间）
 */
public record CustomerProfile(
        String channel,
        String externalUserId,
        String city,
        String phone,
        String preferenceSignals,
        String firstSeen,
        String lastSeen
) {}