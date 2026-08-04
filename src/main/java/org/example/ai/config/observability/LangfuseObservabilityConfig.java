package org.example.ai.config.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Langfuse OTLP 追踪的松耦合装配。
 *
 * <p>本类<strong>不手写</strong> ObservationRegistry / OTel SDK / BatchSpanProcessor ——
 * 这些由 Boot 4 的可观测自动装配提供（spring-boot-micrometer-observation /
 * spring-boot-micrometer-tracing / spring-boot-opentelemetry）。Boot 的 OTLP 导出器
 * 以容器内存在 {@link OtlpTracingConnectionDetails} bean 为装配前提，
 * 因此本类只提供两样 Langfuse 专属的东西：</p>
 *
 * <ol>
 *   <li>{@link OtlpTracingConnectionDetails} —— 告诉导出器 Langfuse 的 OTLP 接收 URL
 *       （{@code <endpoint>/api/public/otel/v1/traces}）；</li>
 *   <li>{@link OtlpHttpSpanExporterBuilderCustomizer} —— 注入 HTTP Basic 认证头
 *       （Langfuse 以 public-key 为用户名、secret-key 为密码）。</li>
 * </ol>
 *
 * <p><strong>松耦合</strong>（对应需求"没有 Langfuse 启动，项目也能正常运行"）：</p>
 * <ul>
 *   <li>{@code langfuse.enabled=false}（或未配置）→ 本类不生效 → 容器内没有
 *       {@code OtlpTracingConnectionDetails} → Boot 不创建 OTLP 导出器 → 零导出、零外部依赖；</li>
 *   <li>{@code langfuse.enabled=true} 但 Langfuse 未启动 → 导出器在后台批处理线程上
 *       连接失败，span 被静默丢弃（OTel BatchSpanProcessor 语义），不阻塞、不影响对话主流程；</li>
 *   <li>key 未配置（占位值）→ 仅打 WARN，应用照常启动；Langfuse 起来后会以 401 拒绝导出，
 *       同样不影响应用。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseObservabilityConfig.class);

    /** Langfuse 侧固定的 OTLP/HTTP 追踪接收路径（OpenTelemetry 信号路径 /v1/traces 已含在内） */
    static final String OTEL_TRACES_PATH = "/api/public/otel/v1/traces";

    /** key 占位值标记（application.properties 默认值），用于识别"还没拿到真实 key" */
    private static final String UNCONFIGURED_MARKER = "not-set";

    @Bean
    OtlpTracingConnectionDetails langfuseOtlpConnectionDetails(LangfuseProperties properties) {
        String endpoint = trimTrailingSlash(properties.getEndpoint());
        return transport -> transport == Transport.HTTP ? endpoint + OTEL_TRACES_PATH : endpoint;
    }

    @Bean
    OtlpHttpSpanExporterBuilderCustomizer langfuseAuthHeaderCustomizer(LangfuseProperties properties) {
        warnIfKeysUnconfigured(properties);
        String credentials = properties.getPublicKey() + ":" + properties.getSecretKey();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return builder -> builder.addHeader("Authorization", basicAuth);
    }

    private static void warnIfKeysUnconfigured(LangfuseProperties properties) {
        if (looksUnconfigured(properties.getPublicKey()) || looksUnconfigured(properties.getSecretKey())) {
            log.warn("Langfuse 已启用但 public-key/secret-key 仍为占位值——追踪导出会被 Langfuse 拒绝（401）。"
                    + "docker compose 启动 Langfuse 并创建项目后，用环境变量 "
                    + "LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY 注入真实 key。应用本身不受影响。");
        }
    }

    private static boolean looksUnconfigured(String key) {
        return key == null || key.isBlank() || key.contains(UNCONFIGURED_MARKER);
    }

    private static String trimTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}