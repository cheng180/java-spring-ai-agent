# Langfuse 接入说明（智能客服 Agent 追踪）

- **接入日期**：2026-08-04
- **目标**：用 Langfuse 监测/追踪智能客服 Agent 的每轮对话（LLM 调用、提示词、回复、工具调用、耗时）
- **实现方式**：Spring AI Micrometer Observation → micrometer-tracing OTel 桥接 → OpenTelemetry SDK → **OTLP HTTP** 导出到 Langfuse（Spring AI 官方推荐路径，不引入 Langfuse 私有 SDK）
- **松耦合承诺**：Langfuse 未启动 / key 未配置时，应用照常运行（见第五节）

---

## 一、链路总览

```
ChatClient.prompt()…call()/stream()
   └─ Spring AI Observation（chat client / chat model / tool call 各一层）
        └─ ObservationRegistry（Boot 4：spring-boot-micrometer-observation 自动装配）
             └─ DefaultTracingObservationHandler → OtelTracer 桥接（micrometer-tracing-bridge-otel）
                  └─ SdkTracerProvider + BatchSpanProcessor（后台异步、守护线程）
                       └─ OtlpHttpSpanExporter ──POST──▶ Langfuse /api/public/otel/v1/traces
                                                          （HTTP Basic：pk 为用户名、sk 为密码）
```

本项目只写了两个小件（`org.example.ai.config.observability` 包）：

| 类 | 职责 |
|---|---|
| `LangfuseProperties` | `langfuse.*` 配置绑定（enabled / endpoint / publicKey / secretKey） |
| `LangfuseObservabilityConfig` | `langfuse.enabled=true` 时提供 `OtlpTracingConnectionDetails`（OTLP 接收 URL）与认证头定制器（Basic pk:sk）；Boot 的 OTLP 导出器以容器内存在 `OtlpTracingConnectionDetails` bean 为装配前提——**不提供即整体旁路** |

其余（ObservationRegistry、OTel SDK、批处理器、采样器）全部由 Boot 4 可观测自动装配模块提供：`spring-boot-micrometer-observation` / `-tracing` / `-tracing-opentelemetry` / `spring-boot-opentelemetry`（pom 里只显式声明了 `-tracing-opentelemetry`，其余传递引入）。

## 二、启动 Langfuse（docker compose）

官方 v4 自托管是 6 件套（langfuse-web、langfuse-worker、postgres、clickhouse、redis、minio），直接用官方 compose 文件：

```bash
# 官方 docker-compose.yml（本环境直连 raw.githubusercontent.com 会被重置，走 gh-proxy 镜像）
curl -O https://gh-proxy.com/https://raw.githubusercontent.com/langfuse/langfuse/main/docker-compose.yml
# 按文件内 CHANGEME 注释改掉默认口令（SALT / ENCRYPTION_KEY / 各密码），然后：
docker compose up -d
```

启动后 Web 界面在 `http://127.0.0.1:3000`。注意 3000 端口不要与本机其他服务冲突。

**拿 key**：注册/登录 → 创建 Organization 与 Project → Project Settings → **API Keys** → 得到 `pk-lf-...`（public）与 `sk-lf-...`（secret）。

## 三、本项目配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `langfuse.enabled` | `true` | 总开关；`false` 时不装配任何导出器 |
| `langfuse.endpoint` | `${LANGFUSE_BASE_URL:http://127.0.0.1:3000}` | Langfuse 基础地址（OTLP 路径 `/api/public/otel/v1/traces` 由代码拼接，勿重复填） |
| `langfuse.public-key` | `${LANGFUSE_PUBLIC_KEY:pk-lf-not-set}` | 走环境变量注入，勿写明文进仓库（仓库 public） |
| `langfuse.secret-key` | `${LANGFUSE_SECRET_KEY:sk-lf-not-set}` | 同上 |
| `management.tracing.sampling.probability` | `1.0` | Boot 默认 0.1 会丢 90% 对话，排查期保持 1.0 |
| `spring.ai.chat(.client).observations.log-prompt/log-completion` | `true` | 把提示词/回复内容写进 span，否则 Langfuse 里只有耗时没有对话内容 |

真实 key 的注入位置：**`start.sh`**（已 gitignore，与 DeepSeek 等 key 同一机制）。本地起应用照旧 `./start.sh`。

## 四、验证

1. Langfuse 与应用都启动后，随便发一条对话（页面 / `POST /api/chat` / macan 路径均可）。
2. Langfuse → 项目 → **Tracing → Traces**：应出现一条 trace，树形结构为 `chat <model>`（ChatClient 层）→ `chat <model>`（ChatModel 层）→（如有）工具调用 span；span 上可见 input/output 内容（由 log-prompt/log-completion 开启）。
3. 没看到 trace 时按顺序排查：
   - 启动日志有 `LangfuseObservabilityConfig` 的 WARN（key 占位值）→ key 没注入，检查 `start.sh`；
   - Langfuse UI 收到 401 → key 与项目不匹配；
   - 连接被拒 → `docker compose ps` 看 3000 是否真的起来了；
   - 都没有异常但就是没数据 → 确认 `langfuse.enabled=true`、采样率 1.0。

## 五、松耦合行为说明（为什么 Langfuse 挂了应用不受影响）

| 场景 | 行为 |
|---|---|
| `langfuse.enabled=false` | 无 `OtlpTracingConnectionDetails` → Boot 不创建 OTLP 导出器 → 零导出、零外部调用 |
| enabled 但 Langfuse 未启动 | `BatchSpanProcessor` 在后台守护线程导出，连接失败仅丢弃 span（OTel 语义），**不同步、不阻塞、不抛到对话主流程** |
| enabled 但 key 为占位值 | 启动打 WARN，应用正常起；Langfuse 起来后导出被 401 拒绝，同样只丢弃 |
| 导出队列积压 | 队列上限 2048 span，超出直接丢弃，内存有界 |

装配契约由测试锁定：`LangfuseObservabilityConfigTest`（关闭/缺省时无导出器、启用时 URL 与 Basic 认证头正确、无 key 不阻塞装配）。

## 六、后续可选

- 把 Langfuse 6 件套并入本仓库 `docker-compose.yml`（目前独立 compose，避免与 chromadb 编排互相牵制）
- 上线观察指标：受限级别轮次的工具调用率（《回复过长问题解决评估文档》R1 的软约束失守信号），Langfuse 按 span 名聚合即可统计
- macan 无状态回放路径与主路径共用同一 ChatClient，trace 行为天然一致，无需额外改动