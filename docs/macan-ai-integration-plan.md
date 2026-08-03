# macan 接入 AI 项目 —— 整体方案（v0.2）

## 1. 一句话需求

> 把 AI 项目封装成一个接口（入参 = macan 的 `List<Message>`，出参 = 回复字符串），平替掉千问 API。

macan 不需要改业务逻辑，只是把 `QWenServiceImpl` 换成一个新的 `AIProjectServiceImpl`，新实现不再调阿里百炼，而是 HTTP 调 AI 项目的一个对等接口。

---

## 2. 当前切点

### 2.1 千问是怎么被调用的

```
QwKfServiceImpl.buildAiReply()                   // L344
  │
  ├── 构建 messages:
  │     [0] system  = "你是一个汽车销售客服..."
  │     [1..n-1]    = Redis 历史上下文 (user/assistant 交替)
  │     [n]   user   = 当前客户消息
  │
  └── aiService.chatReply(messages)              // L351
        │
        ▼
      QWenServiceImpl.chatReply(messages)        // L38
        │
        ├── JSON.stringify({model, messages, max_tokens, temperature})
        ├── POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
        ├── 解析 OpenAIResponse
        └── return choices[0].message.content     // String
```

### 2.2 `IAIService` 接口

```java
// macan-service/.../ai/IAIService.java
public interface IAIService {
    /** 对话回复 —— 我们只需要平替这一个方法 */
    String chatReply(List<Message> messages);

    // embedding / functionCall / toolCall 等 —— 本次不改，默认抛异常即可
}
```

### 2.3 `Message` 结构

```java
// macan-service/.../chatGpt/model/Message.java
public class Message {
    String role;    // "system" | "user" | "assistant"
    String content; // 消息文本
}
```

---

## 3. 目标：把千问 API 替换为 AI 项目

```
之前：
  macan → QWenServiceImpl → POST dashscope.aliyuncs.com → 千问回复

之后：
  macan → AIProjectServiceImpl → POST <AI项目>/api/chat/messages → AI 项目回复
                                         │
                                         └── CarSalesAgent.chat()
                                               ├── RAG 检索 (ChromaDB + BM25)
                                               ├── ChatMemory (按 userId)
                                               └── LLM 生成回复
```

---

## 4. AI 项目需要做的：暴露一个平替接口

### 4.1 接口定义

```
POST /api/chat/messages
Content-Type: application/json

请求体（与 OpenAI chat completions 的 messages 字段完全一致）：
{
  "messages": [
    {"role": "system", "content": "你是一个汽车销售客服..."},
    {"role": "user", "content": "客户: 在吗"},
    {"role": "assistant", "content": "在的，有什么可以帮您"},
    {"role": "user", "content": "客户: 10万左右推荐什么车"}
  ],
  "userId": "externalUserId"        // 可选，用于 ChatMemory 会话关联
}

响应：
{
  "reply": "您好！10万左右我们目前有..."
}
```

### 4.2 Controller 代码骨架（AI 项目侧）

```java
// 在 ChatController 中新增
@PostMapping("/api/chat/messages")
public Map<String, String> chatForMacan(@RequestBody MacanChatRequest request) {

    // 1. 提取最后一条 user 消息作为当前 query
    String userMessage = request.messages().stream()
            .filter(m -> "user".equals(m.role()))
            .reduce((first, second) -> second)   // 取最后一条
            .map(Message::content)
            .orElse("");

    // 2. 提取 system prompt（macan 配的，优先用）
    String systemPrompt = request.messages().stream()
            .filter(m -> "system".equals(m.role()))
            .findFirst()
            .map(Message::content)
            .orElse(null);

    // 3. 提取历史消息（user/assistant 交替），灌入 ChatMemory
    String userId = request.userId() != null ? request.userId() : "macan-kf";
    // ... 把历史消息写入 ChatMemory ...

    // 4. 走完整 Agent 流程
    String reply = agent.chat(userId, userMessage, "macan-server");

    return Map.of("reply", reply);
}

// DTO
public record MacanChatRequest(
    List<Message> messages,
    String userId
) {}

public record Message(String role, String content) {}
```

### 4.3 关键处理逻辑

macan 传来的 `messages` 里每条 user 消息前面都有 `"客户: "` 前缀（来自 `Message.prompt()` 或原始企微消息经过 `AIDialogServiceImpl` 格式化），AI 项目需要决定是否对此做处理。建议在 Agent 的 prompt 中兼容即可，无需额外字符串操作。

**上下文管理**：两种选择——

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 无状态 | AI 项目不用 ChatMemory，macan 每次传来完整历史就够 | 最简单 | 每次请求 token 多，历史由 macan Redis 控制 |
| B. 有状态（推荐） | AI 项目用 ChatMemory + userId 维护会话，同时 macan 也可保留 Redis 历史做冗余 | 请求体小，AI 项目统一管记忆 | 首次请求需灌历史 |

推荐方案 B 渐进实施：先用 A 验证链路通，再改为 B 优化。

---

## 5. macan 需要做的：新增 AIProjectServiceImpl

### 5.1 代码骨架

```java
// 新建文件: macan-service/.../ai/impl/AIProjectServiceImpl.java
@Component("AIProjectService")
public class AIProjectServiceImpl implements IAIService {

    @Value("${macan.ai.project.url:http://localhost:8080}")
    private String aiProjectUrl;

    @Override
    public String chatReply(List<Message> messages) {
        // 1. 构造请求体
        Map<String, Object> body = Map.of("messages", messages);

        // 2. 发 HTTP POST
        Request request = new Request.Builder()
                .url(aiProjectUrl + "/api/chat/messages")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        JSON_TOOL.toJson(body)))
                .build();

        String responseBody = httpCall(request, false);
        if (StringUtils.isBlank(responseBody)) {
            log.error("AI项目调用失败，response为空");
            return "";
        }

        // 3. 解析 {"reply": "..."}
        JsonObject json = JSON_TOOL.fromJson(responseBody, JsonObject.class);
        return json.get("reply").getAsString();
    }

    // 以下方法本次不实现，默认抛异常
    @Override
    public List<List<Double>> embedding(List<String> textList) {
        throw new UnsupportedOperationException("embedding 暂不走 AI 项目");
    }

    @Override
    public FunctionCall functionCall(List<Message> messages, List<FunctionTool> functionTools) {
        throw new UnsupportedOperationException("functionCall 暂不走 AI 项目");
    }
}
```

### 5.2 修改注入点

```java
// QwKfServiceImpl.java L70-72，改一个字符串即可
@Autowired
@Qualifier("AIProjectService")   // 原来是 "QWenService"
private IAIService aiService;
```

### 5.3 新增配置

```yaml
# macan 配置文件
macan:
  ai:
    project:
      url: http://ai-project:8080    # AI 项目地址
```

### 5.4 影响范围

| 组件 | 受影响？ | 说明 |
|---|---|---|
| `QwKfServiceImpl` | ✅ 改一行注入 | 只改 qualifier |
| `AIClient` | ❌ 不受影响 | 有自己的 `@Qualifier("QWenService")` 注入，继续用千问做意图分析 |
| `ChatGPTServiceImpl` | ❌ 不受影响 | 保留作为备选 |
| `QWenServiceImpl` | ❌ 保留不动 | 作为降级备选 |
| 企微回调 Controller | ❌ 不改 | 回调链路完全不变 |

---

## 6. 接口协议（最终版）

### 请求

```
POST /api/chat/messages
Content-Type: application/json

{
  "messages": [
    {"role": "system", "content": "系统提示词"},
    {"role": "user", "content": "历史用户消息1"},
    {"role": "assistant", "content": "历史AI回复1"},
    {"role": "user", "content": "当前用户消息"}
  ],
  "userId": "externalUserId"    // 可选
}
```

### 响应

成功：
```json
{"reply": "AI 生成的回复文本"}
```

失败：
```json
{"reply": ""}
```
或 HTTP 500。

### 健康检查（已有）

```
GET /api/health
→ {"status": "UP", "service": "卖好车智能汽车销售客服"}
```

---

## 7. 部署

### 核心约束

macan 是已部署的系统，它通过 HTTP 调 AI 项目。AI 项目必须有一个 **macan 服务器能访问到的 URL**。本地 localhost 不行。

### 开发调试：内网穿透

```bash
# 本地启动 AI 项目（8080 端口）
# 用 ngrok 暴露
ngrok http 8080
# → https://abc123.ngrok-free.app
```

macan 本地配置指向 `https://abc123.ngrok-free.app`。

### 生产：Docker Compose 同机部署

```
同一台服务器上：

┌──────────────────────────────────────┐
│  macan (:8080)                        │
│    │  HTTP                            │
│    ▼                                  │
│  ai-project (:8081, 不暴露外网)       │
│    ├── ChromaDB (:8001)               │
│    └── → LLM API (外部)               │
└──────────────────────────────────────┘

macan 配置: macan.ai.project.url=http://ai-project:8081
```

---

## 8. 改造清单

### AI 项目（当前项目）

| 文件 | 操作 | 内容 |
|---|---|---|
| `ChatController.java` | 新增方法 | `POST /api/chat/messages`，接收 `{messages, userId}`，内部走 `agent.chat()`，返回 `{reply}` |
| `CarSalesAgent.java` | 可能新增重载 | `chat(userId, message, historyMessages)` —— 把 history 灌进 ChatMemory 后再调 LLM |

### macan 项目

| 文件 | 操作 | 内容 |
|---|---|---|
| `AIProjectServiceImpl.java` | **新建** | 实现 `IAIService`，HTTP 调 AI 项目的 `/api/chat/messages` |
| `QwKfServiceImpl.java` L71 | **改一行** | `@Qualifier("QWenService")` → `@Qualifier("AIProjectService")` |
| `application.yml` | **新增 1 行** | `macan.ai.project.url: http://...` |

### 不改的文件

```
QWenServiceImpl.java      —— 保留不动（降级备选）
ChatGPTServiceImpl.java   —— 保留不动
AIClient.java             —— 有自己的注入，不受影响
QwKfController.java       —— 回调入口不变
QwKfService.java          —— 接口不变
IAIService.java           —— 接口不变
```

---

## 9. 数据流总览

```
企业微信用户
  │ "10万左右推荐什么车"
  ▼
企微服务器 → POST /wechat/mall/kf/callBack
  │
  ▼
QwKfController.kfCallback()
  │
  ▼
QwKfServiceImpl.callback()
  ├── 解密、拉消息、去重、转人工检测（全不变）
  └── replyMessagesByAi()
        └── buildAiReply()
              │
              │  List<Message> messages = [
              │    system("你是汽车销售客服..."),
              │    user("在吗"), assist("在的"),
              │    user("10万左右推荐什么车")
              │  ]
              │
              ▼
            aiService.chatReply(messages)  ←── 这里被替换
              │
              │  【旧】QWenServiceImpl → dashscope.aliyuncs.com
              │  【新】AIProjectServiceImpl → AI项目 /api/chat/messages
              │
              ▼
            return "您好！10万左右目前有轩逸、朗逸..."
              │
              ▼
            sendTextMessage() → 企微 → 用户收到回复
```

---

## 10. 实施顺序

```
Phase 1 ─ 代码
  ├── AI项目: ChatController 新增 /api/chat/messages
  ├── macan: 新建 AIProjectServiceImpl
  └── macan: QwKfServiceImpl 改 qualifier + 加配置

Phase 2 ─ 本地验证
  ├── AI项目启动 (localhost:8080)
  ├── ngrok http 8080
  ├── macan 配置指向 ngrok 地址
  └── 模拟企微回调 / 发测试消息

Phase 3 ─ 部署
  ├── AI项目打 Docker 镜像
  ├── 部署到 macan 同网段服务器
  └── 灰度启用
```

---

## 11. 修订记录

| 日期 | 版本 | 修订内容 |
|---|---|---|
| 2026-07-29 | v0.1 | 初稿，完整架构分析 |
| 2026-07-29 | v0.2 | 需求明确：聚焦"一个接口平替千问 API"，精简方案 |