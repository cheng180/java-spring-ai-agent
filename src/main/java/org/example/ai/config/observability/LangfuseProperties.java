package org.example.ai.config.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 接入配置（{@code langfuse.*}）。
 *
 * <p>Langfuse 走自托管（docker compose），OTLP/HTTP 追踪接收端点固定为
 * {@code <endpoint>/api/public/otel/v1/traces}，认证为 HTTP Basic
 * （用户名 = public-key，密码 = secret-key；两个 key 在 Langfuse 页面创建项目后获取）。</p>
 *
 * <p>松耦合约定：{@code langfuse.enabled=false} 时不装配任何导出器；
 * enabled=true 但 Langfuse 未启动时，导出在后台异步失败、只丢弃 span，不影响应用。</p>
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 总开关。false 时不装配 Langfuse 相关 bean，等同于未接入 */
    private boolean enabled = false;

    /** Langfuse 服务基础地址（不含 OTLP 路径，路径由 {@code LangfuseObservabilityConfig} 拼接） */
    private String endpoint = "http://127.0.0.1:3000";

    /** Public key（pk-lf-...，Basic 认证用户名），建议走环境变量 LANGFUSE_PUBLIC_KEY 注入 */
    private String publicKey = "";

    /** Secret key（sk-lf-...，Basic 认证密码），建议走环境变量 LANGFUSE_SECRET_KEY 注入 */
    private String secretKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}