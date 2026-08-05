package org.example.ai.impl.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 客户画像服务（#39 ticket）——编排抽取、合并、落库。
 *
 * <p>合并语义：新值缺失（null）不覆盖旧值；偏好信号按键合并（新值覆盖同键、保留他键）。
 * first_seen 保留首次，last_seen 每次落库刷新。抽取/落库任何环节异常都吞掉记日志——
 * 画像是增值能力，绝不阻断对话主流程。</p>
 */
@Component
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CustomerProfileRepository repository;
    private final ProfileExtractor extractor;
    private final NeedSignalDetector signalDetector;

    public CustomerProfileService(CustomerProfileRepository repository,
                                  ProfileExtractor extractor,
                                  NeedSignalDetector signalDetector) {
        this.repository = repository;
        this.extractor = extractor;
        this.signalDetector = signalDetector;
    }

    /**
     * 抓取一条消息中的客户线索并写入画像。
     *
     * @param channel        渠道（"web" / "macan"）
     * @param externalUserId 渠道内稳定用户 ID；为空则不落库
     * @param message        客户消息
     */
    public void capture(String channel, String externalUserId, String message) {
        if (channel == null || channel.isBlank()
                || externalUserId == null || externalUserId.isBlank()
                || message == null || message.isBlank()) {
            return;
        }
        try {
            String phone = extractor.extractPhone(message).orElse(null);
            String city = extractor.extractCity(message).orElse(null);
            Map<String, String> newSignals = signalDetector.detect(message);

            if (phone == null && city == null && newSignals.isEmpty()) {
                return; // 无可抓取的线索，不落库
            }

            Optional<CustomerProfile> existing = repository.find(channel, externalUserId);
            String now = Instant.now().toString();

            String mergedCity = city != null ? city : existing.map(CustomerProfile::city).orElse(null);
            String mergedPhone = phone != null ? phone : existing.map(CustomerProfile::phone).orElse(null);
            String mergedSignals = mergeSignals(
                    existing.map(CustomerProfile::preferenceSignals).orElse(null), newSignals);
            String firstSeen = existing.map(CustomerProfile::firstSeen).orElse(now);

            repository.upsert(new CustomerProfile(channel, externalUserId,
                    mergedCity, mergedPhone, mergedSignals, firstSeen, now));

            log.info("客户画像更新：channel={}, userId={}, city={}, phone={}, signals={}",
                    channel, externalUserId, mergedCity,
                    mergedPhone != null ? "***" : null, mergedSignals);
        } catch (Exception e) {
            // 画像失败不阻断对话
            log.warn("客户画像抓取失败（不影响对话）: {}", e.getMessage());
        }
    }

    /** 偏好信号按键合并：新值覆盖同键，保留既有他键；结果序列化为 JSON */
    private String mergeSignals(String existingJson, Map<String, String> newSignals) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                merged.putAll(MAPPER.readValue(existingJson, new TypeReference<Map<String, String>>() {}));
            } catch (Exception e) {
                log.debug("既有偏好 JSON 解析失败，忽略重建: {}", e.getMessage());
            }
        }
        merged.putAll(newSignals);
        try {
            return MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            return "{}";
        }
    }
}