package org.example.ai.config.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LangfuseObservabilityConfig 装配测试。
 *
 * <p>锁定的核心契约是<strong>松耦合</strong>：
 * <ul>
 *   <li>{@code langfuse.enabled} 缺省或为 false → 不产生任何 OTLP 导出器
 *       （Boot 以容器内有无 {@link OtlpTracingConnectionDetails} 为前提装配导出器，
 *       本配置不提供该 bean 即整体旁路）；</li>
 *   <li>{@code langfuse.enabled=true} → 导出器装配，URL 指向 Langfuse OTLP 接收路径，
 *       认证头为 Basic(pk:sk)；</li>
 *   <li>key 未配置不阻塞装配（应用照常启动，Langfuse 侧拒绝导出是运行时行为）。</li>
 * </ul>
 * 基础可观测链路（ObservationRegistry / OTel SDK / Tracer）由 Boot 4 自动装配提供，
 * 这里一并断言其在启用时完整可用。</p>
 */
class LangfuseObservabilityConfigTest {

    /** 与生产 classpath 一致的可观测自动装配链（observation → tracing → otel sdk → otlp 导出） */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class))
            .withUserConfiguration(LangfuseObservabilityConfig.class);

    @Test
    @DisplayName("缺省（未配置 langfuse.enabled）：不装配连接细节与 OTLP 导出器——零 Langfuse 依赖")
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpTracingConnectionDetails.class);
            assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    @DisplayName("langfuse.enabled=false：同样不装配导出器")
    void explicitlyDisabled() {
        contextRunner
                .withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OtlpTracingConnectionDetails.class);
                    assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
                });
    }

    @Test
    @DisplayName("langfuse.enabled=true：OTLP 导出器装配，URL 指向 Langfuse OTLP 接收路径")
    void enabledCreatesOtlpExporterPointingAtLangfuse() {
        contextRunner
                .withPropertyValues(
                        "langfuse.enabled=true",
                        "langfuse.endpoint=http://127.0.0.1:3000",
                        "langfuse.public-key=pk-lf-test",
                        "langfuse.secret-key=sk-lf-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtlpTracingConnectionDetails.class);
                    assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);

                    OtlpTracingConnectionDetails connectionDetails =
                            context.getBean(OtlpTracingConnectionDetails.class);
                    assertThat(connectionDetails.getUrl(Transport.HTTP))
                            .isEqualTo("http://127.0.0.1:3000" + LangfuseObservabilityConfig.OTEL_TRACES_PATH);

                    // 基础链路在启用时完整可用（Spring AI 观测 → OTel 桥接 → SDK）
                    assertThat(context).hasSingleBean(ObservationRegistry.class);
                    assertThat(context).hasSingleBean(OpenTelemetrySdk.class);
                    assertThat(context).hasSingleBean(Tracer.class);
                });
    }

    @Test
    @DisplayName("endpoint 带尾斜杠：拼接前被规范化，URL 不出现双斜杠")
    void trailingSlashEndpointIsNormalized() {
        contextRunner
                .withPropertyValues(
                        "langfuse.enabled=true",
                        "langfuse.endpoint=http://127.0.0.1:3000/")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtlpTracingConnectionDetails connectionDetails =
                            context.getBean(OtlpTracingConnectionDetails.class);
                    assertThat(connectionDetails.getUrl(Transport.HTTP))
                            .isEqualTo("http://127.0.0.1:3000" + LangfuseObservabilityConfig.OTEL_TRACES_PATH);
                });
    }

    @Test
    @DisplayName("启用但未配置 key：装配不受阻塞（应用照常启动，松耦合要求）")
    void enabledWithoutKeysStillAssembles() {
        contextRunner
                .withPropertyValues("langfuse.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
                });
    }

    @Test
    @DisplayName("认证头：Basic base64(publicKey:secretKey) + v4 摄取协议头注入导出器请求头")
    void authHeaderIsBasicAuthOfKeyPair() {
        contextRunner
                .withPropertyValues(
                        "langfuse.enabled=true",
                        "langfuse.public-key=pk-lf-test",
                        "langfuse.secret-key=sk-lf-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtlpHttpSpanExporterBuilderCustomizer customizer =
                            context.getBean(OtlpHttpSpanExporterBuilderCustomizer.class);

                    OtlpHttpSpanExporterBuilder builder = mock(OtlpHttpSpanExporterBuilder.class);
                    // 定制器链式调用 addHeader，mock 需返回自身
                    when(builder.addHeader(anyString(), anyString())).thenReturn(builder);
                    customizer.customize(builder);

                    String expected = "Basic " + Base64.getEncoder()
                            .encodeToString("pk-lf-test:sk-lf-test".getBytes(StandardCharsets.UTF_8));
                    verify(builder).addHeader("Authorization", expected);
                    // Langfuse v4 为 events_only 数据模型：v4 头选择新摄取路径，
                    // observation 级 input/output 与 trace 级 session/user 按 v4 规则映射
                    verify(builder).addHeader("x-langfuse-ingestion-version", "4");
                });
    }

    @Test
    @DisplayName("噪音抑制：http.server.requests 观测被拒绝，其余观测放行")
    void httpServerObservationsAreDenied() {
        contextRunner
                .withPropertyValues("langfuse.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 按 bean 名取本配置的谓词（Boot 自动装配可能另注册 predicate，如 propertiesObservationFilter）
                    ObservationPredicate predicate = context.getBean(
                            "langfuseNoiseReductionPredicate", ObservationPredicate.class);
                    Observation.Context httpServer = new Observation.Context();
                    Observation.Context chatModel = new Observation.Context();

                    assertThat(predicate.test(LangfuseObservabilityConfig.HTTP_SERVER_OBSERVATION, httpServer))
                            .isFalse();
                    assertThat(predicate.test("chat deepseek-chat", chatModel)).isTrue();
                });
    }
}