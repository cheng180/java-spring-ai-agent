# Spring AI 历史学家 AI —— 详细操作文档

> 基于 [腾讯云开发者社区文章 ID 2538285](https://cloud.tencent.com/developer/article/2538285)，作者：程序员NEO（2025-07-07）

---

## 一、项目概述

本项目使用 **Spring AI** 框架构建一个"历史知识专家"AI 聊天机器人，核心特性是**多轮对话记忆**——AI 能记住你在前几轮说过的话，并基于历史上下文给出连贯回答。

---

## 二、项目目录结构

```
D:\idea\project\history-expert-ai/
├── .gitattributes
├── .gitignore
├── HELP.md
├── mvnw
├── mvnw.cmd
├── pom.xml
│
├── .idea/                                          # IntelliJ IDEA 配置
│   ├── .gitignore
│   ├── compiler.xml
│   ├── encodings.xml
│   ├── jarRepositories.xml
│   ├── misc.xml
│   └── workspace.xml
│
├── .mvn/                                           # Maven Wrapper
│   └── wrapper/
│       └── maven-wrapper.properties
│
└── src/
    ├── main/
    │   ├── java/
    │   │   └── org/
    │   │       └── example/
    │   │           └── historyexpertai/            # 根包
    │   │               ├── HistoryExpertAiApplication.java   # Spring Boot 主启动类
    │   │               ├── app/
    │   │               │   └── HistoryExpertApp.java        # 应用主逻辑
    │   │               ├── config/
    │   │               │   └── AppConfig.java               # 配置类
    │   │               ├── controller/
    │   │               │   └── ChatController.java          # Chat 控制器
    │   │               └── model/
    │   │                   └── ChatRequest.java             # Chat 请求模型
    │   │
    │   └── resources/
    │       ├── application.properties              # Spring Boot 配置文件
    │       ├── static/                             # 静态资源（空）
    │       └── templates/                          # 模板文件（空）
    │
    └── test/
        └── java/
            └── org/
                └── example/
                    └── historyexpertai/
                        └── HistoryExpertAiApplicationTests.java  # 测试类
```

---

## 三、详细实现步骤

### 步骤 1：创建 Spring Boot 项目（⚠️ 不要手动写 pom.xml）

> **为什么不能手动创建？**
> Spring AI 目前处于里程碑阶段（1.0.0-M4），依赖不在 Maven Central，需要配置 Spring Milestones 仓库。同时 `spring-ai-bom` 统一管理内部各子模块版本，手写版本号极易冲突。**手动写 pom.xml 十有八九会因为缺仓库、版本不匹配、starter 命名错误而构建失败。**

#### 方式一（推荐）：Spring Initializr 网页

打开 [start.spring.io](https://start.spring.io)，按以下参数填写：

| 参数 | 取值 |
|------|------|
| **Project** | Maven |
| **Language** | Java |
| **Spring Boot** | 3.4.1（或最新稳定版） |
| **Group** | `org.example` |
| **Artifact** | `history-expert-ai` |
| **Name** | `history-expert-ai` |
| **Package name** | `org.example.historyexpertai` |
| **Java** | 17 |

点击 **ADD DEPENDENCIES**，搜索并添加以下依赖：

| 搜索关键词 | 要添加的依赖 |
|------------|-------------|
| `spring web` | **Spring Web** |
| `openai` | **OpenAI**（Spring AI 分类下） |
| `lombok` | **Lombok** |

点击 **GENERATE** 下载 zip，解压后用 IDE 打开即可。

#### 方式二（同样推荐）：IntelliJ IDEA 内置

1. `File` → `New` → `Project`
2. 左侧选择 **Spring Initializr**
3. 参数同上面表格
4. 勾选依赖：**Spring Web**、**OpenAI (Spring AI)**、**Lombok**
5. 点击 **Create**

#### 生成后 pom.xml 关键部分（供参考，无需手写）

Spring Initializr 会自动生成完整 pom.xml，核心部分如下。**注意 `<repositories>` 中的 Spring Milestones 仓库和 `<dependencyManagement>` 中的 `spring-ai-bom` —— 这是 Spring AI 能正常工作的关键，手写最容易漏的就是这两块。**

```xml
<!-- 关键点 1：BOM 统一管控 Spring AI 所有子模块版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0-M4</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- 关键点 2：Spring AI M4 阶段依赖发布在里程碑仓库，Maven Central 拉不到 -->
<repositories>
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

> **原理说明**：`spring-ai-openai-spring-boot-starter` 自动装配 `ChatModel`、`ChatClient.Builder` 等核心 Bean。你用 `spring-ai-openai` starter，但底层统一由 BOM 控制版本，所以不需要（也不能）手动写 `<version>`。

---

### 步骤 2：配置文件 `application.properties`

Spring Initializr 生成的是 `application.properties`，直接写入以下内容即可（位于 `src/main/resources/application.properties`）：

```properties
spring.application.name=history-expert-ai

# ==================== OpenAI 大模型配置 ====================
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.base-url=https://api.openai.com
spring.ai.openai.chat.options.model=gpt-4o
spring.ai.openai.chat.options.temperature=0.8

# ==================== 日志 ====================
logging.level.org.example.historyexpertai=DEBUG
```

> **对接国内大模型示例**（通义千问）：
> ```properties
> spring.ai.openai.api-key=${DASHSCOPE_API_KEY}
> spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
> spring.ai.openai.chat.options.model=qwen-plus
> ```

---

### 步骤 3：主启动类 `HistoryExpertAiApplication.java`

**路径**：`src/main/java/org/example/historyexpertai/HistoryExpertAiApplication.java`

```java
package org.example.historyexpertai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动入口。
 *
 * @SpringBootApplication 是以下三个注解的复合：
 *   - @Configuration       : 标记此类为配置类
 *   - @EnableAutoConfiguration : 启用 Spring Boot 自动装配
 *   - @ComponentScan       : 扫描当前包及其子包中的 @Component/@Service/@Controller
 *
 * 由于其他类都放在 org.example.historyexpertai 的子包中
 * （app/、config/、controller/、model/），主类位于根包即可自动扫描到。
 */
@SpringBootApplication
public class HistoryExpertAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(HistoryExpertAiApplication.class, args);
    }
}
```

---

### 步骤 4：核心组件 `HistoryExpertApp.java` ⭐

**路径**：`src/main/java/org/example/historyexpertai/app/HistoryExpertApp.java`

这是项目的**灵魂文件**，负责与大模型交互并管理对话记忆。

```java
package org.example.historyexpertai.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 历史知识专家 —— 核心 AI 对话组件。
 *
 * 职责：
 *   1. 持有 ChatClient 实例（与大模型通信的门面）
 *   2. 管理对话记忆（ChatMemory），实现多轮对话上下文保持
 *   3. 对外暴露 doChat() 方法，接受用户消息和会话 ID
 */
@Slf4j          // Lombok：自动生成 log 对象
@Component      // Spring：将此对象纳入 IoC 容器管理
public class HistoryExpertApp {

    // ============ 成员变量 ============

    /**
     * ChatClient 是 Spring AI 1.0 引入的 Fluent API，推荐替代直接使用 ChatModel。
     *
     * 为什么用 ChatClient 而不是 ChatModel？
     *   - 支持链式调用，代码更简洁
     *   - 内置 Advisor（拦截器）机制，轻松挂载记忆、日志、RAG 等增强能力
     *   - 支持多种返回格式：ChatResponse / entity() 对象映射 / stream() 流式输出
     */
    private final ChatClient chatClient;

    // ============ 系统提示词（人设） ============

    /**
     * 系统提示词（System Prompt）是整个对话的"角色设定"。
     *
     * 原理：
     *   每次请求 LLM 时，这个消息会被放在 messages 列表的最前面，
     *   告诉模型"你是谁、你的语气、你的知识边界、你如何处理异常输入"。
     *   它不参与 ChatMemory 的存取轮回 —— 由 defaultSystem() 固定在请求顶部。
     */
    private static final String SYSTEM_PROMPT =
            "你是一位风趣幽默的历史知识专家，学识渊博。" +
            "你需要根据用户的提问，生动、清晰地回答相关的历史知识。" +
            "如果用户的问题不清晰，你需要引导用户提供更多信息。";

    // ============ 构造函数 ============

    /**
     * Spring 自动注入 ChatModel（由 spring-ai-openai-starter 提供）。
     *
     * 构造函数内部完成三件事：
     *   1. 创建 ChatMemory   —— 对话记忆的"仓库"
     *   2. 创建 MessageChatMemoryAdvisor —— 对话记忆的"搬运工"
     *   3. 构建 ChatClient   —— 对话的"总指挥"
     *
     * @param chatModel Spring AI 自动装配的大模型客户端
     */
    public HistoryExpertApp(ChatModel chatModel) {
        // --- 第 1 步：创建记忆仓库 ---
        /*
         * InMemoryChatMemory：基于 ConcurrentHashMap 的内存实现。
         *
         * 内部数据结构（简化）：
         *   Map<String, List<Message>> conversations
         *        ↑ chatId        ↑ 消息列表
         *
         * 优点：零配置、测试方便
         * 缺点：应用重启即丢失；不适合多实例部署（各实例内存独立）
         *
         * 生产环境替代方案：
         *   - JdbcChatMemory       → MySQL/PostgreSQL 持久化
         *   - RedisChatMemory      → 高性能缓存，支持 TTL 过期
         *   - CassandraChatMemory  → 大规模、高可用场景
         *   - MongoDBChatMemory    → 文档存储，灵活扩展字段
         */
        ChatMemory chatMemory = new InMemoryChatMemory();

        // --- 第 2 步：创建记忆 Advisor ---
        /*
         * MessageChatMemoryAdvisor 实现 Spring AI 的 Advisor 接口，工作在责任链中。
         *
         * 【 责任链机制 】
         *   ChatClient 发请求前，会依次调用所有 Advisor：
         *     1. advisor.before(request)  → 前置拦截：把历史消息注入 Prompt
         *     2. 大模型处理
         *     3. advisor.after(response)  → 后置拦截：把本轮对话存入 ChatMemory
         *
         * 【 为什么用 MessageChatMemoryAdvisor 而不是 PromptChatMemoryAdvisor？ 】
         *   - Message 版本：保留 MessageType（SYSTEM / USER / ASSISTANT），
         *     历史消息以独立消息块注入，角色结构完整。
         *   - Prompt 版本：把所有历史拼接成一段纯文本 String，塞进系统提示词。
         *     丢失角色信息，对大模型理解历史不利。
         *   → 结论：始终优选 MessageChatMemoryAdvisor。
         */

        // --- 第 3 步：构建 ChatClient ---
        /*
         * ChatClient.builder() 是构造入口。
         *
         * .defaultSystem(SYSTEM_PROMPT)
         *   → 设置默认系统提示词，每次请求自动携带
         *
         * .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
         *   → 注册默认 Advisor，每次请求自动执行
         *   → 此处传入 chatMemory，Advisor 知道从哪读、往哪写
         *
         * .build() 返回不可变的 ChatClient 实例。
         */
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();

        log.info("历史专家 AI 初始化完成");
    }

    // ============ 核心方法：doChat ============

    /**
     * 执行一次对话。
     *
     * 调用链路：
     *   doChat(message, chatId)
     *     → chatClient.prompt().user(message)
     *         .advisors(spec -> ...)    // 覆盖/补充默认 Advisor 参数
     *         .call()                   // 同步调用大模型
     *         .chatResponse()           // 获取完整响应
     *     → 提取文本内容返回
     *
     * @param message 用户输入的自然语言消息
     * @param chatId  会话唯一标识（不同用户/会话用不同 ID 实现隔离）
     * @return AI 的文本回复
     */
    public String doChat(String message, String chatId) {
        log.debug("会话 [{}] 收到提问: {}", chatId, message);

        /*
         * .prompt()          → 创建 Prompt 对象（可链式设置 user/system/functions 等）
         * .user(message)     → 设置用户消息
         * .advisors(spec -> ...)  → 动态配置 Advisor 参数
         * .call()            → 同步执行，返回 ChatClient.CallResponseSpec（阻塞式）
         * .chatResponse()    → 获取 ChatResponse 对象
         */
        ChatResponse chatResponse = this.chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec
                        /*
                         * CHAT_MEMORY_CONVERSATION_ID_KEY:
                         *   常量值 = "chat_memory_conversation_id"
                         *   作用：告诉 Advisor "当前请求属于哪个会话"。
                         *
                         *   Advisor.before() 中：
                         *     chatMemory.get(chatId, lastN) → 只取这个 chatId 的消息
                         *
                         *   Advisor.after() 中：
                         *     chatMemory.add(chatId, userMessage)
                         *     chatMemory.add(chatId, assistantMessage)
                         *
                         *   原理类比：
                         *     ChatMemory   = 电话录音服务器
                         *     chatId       = 每通电话的唯一编号
                         *     retrieveSize = 回听最近 N 句
                         */
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)

                        /*
                         * CHAT_MEMORY_RETRIEVE_SIZE_KEY:
                         *   常量值 = "chat_memory_retrieve_size"
                         *   作用：限制每次取多少条最近消息注入 Prompt。
                         *
                         *   设为 10 的含义：
                         *     → 每次请求前，取 chatId 下最近 10 条消息
                         *     → 加上当前用户消息和系统提示词，组成完整 Prompt
                         *
                         *   为什么需要这个限制？
                         *     1. Token 经济性：GPT-4o 输入 $2.5/1M tokens，
                         *        不限制会导致每次请求的成本线性增长
                         *     2. 上下文窗口：即使 GPT-4o 支持 128K，
                         *        塞入过长历史也可能稀释关键信息
                         *     3. 首字延迟：Prompt 越长，大模型处理越慢
                         *
                         *   设置为 1 的效果：
                         *     → AI 秒变"金鱼记忆"，只记得最近 1 条消息
                         *     → 适合验证记忆机制是否生效
                         */
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .chatResponse();

        /*
         * chatResponse.getResult()        → ChatGeneration（单次生成结果）
         *   .getOutput()                   → AssistantMessage（AI 的回复消息对象）
         *     .getContent()                → String（纯文本内容）
         */
        String reply = chatResponse.getResult().getOutput().getContent();
        log.debug("会话 [{}] AI 回复: {}", chatId, reply);

        return reply;
    }
}
```

---

### 步骤 5：请求模型 `ChatRequest.java`

**路径**：`src/main/java/org/example/historyexpertai/model/ChatRequest.java`

```java
package org.example.historyexpertai.model;

import lombok.Data;

/**
 * 前端发送的聊天请求体。
 *
 * 使用 @Data 自动生成 getter/setter/toString/equals/hashCode。
 */
@Data
public class ChatRequest {
    /** 用户输入的消息 */
    private String message;

    /** 会话 ID（前端生成并维护，例如存在 localStorage） */
    private String chatId;
}
```

---

### 步骤 6：REST 控制器 `ChatController.java`

**路径**：`src/main/java/org/example/historyexpertai/controller/ChatController.java`

```java
package org.example.historyexpertai.controller;

import org.example.historyexpertai.app.HistoryExpertApp;
import org.example.historyexpertai.model.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 对外暴露 HTTP 接口，让前端/客户端可以调用 AI 对话。
 */
@RestController                     // 所有方法返回值自动序列化为 JSON
@RequestMapping("/api/chat")        // 统一路径前缀
@RequiredArgsConstructor            // Lombok：为 final 字段生成构造函数（构造注入）
public class ChatController {

    private final HistoryExpertApp historyExpertApp;

    /**
     * POST /api/chat/send
     *
     * 请求体示例：
     *   {
     *     "message": "诸葛亮是谁？",
     *     "chatId": "abc-123-def"
     *   }
     *
     * 响应体示例：
     *   {
     *     "code": 0,
     *     "data": {
     *       "reply": "诸葛亮，字孔明，三国时期...",
     *       "chatId": "abc-123-def"
     *     }
     *   }
     */
    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody ChatRequest request) {
        // 若无 chatId 则自动生成（新会话）
        String chatId = request.getChatId();
        if (chatId == null || chatId.isBlank()) {
            chatId = UUID.randomUUID().toString();
        }

        String reply = historyExpertApp.doChat(request.getMessage(), chatId);

        return Map.of(
                "code", 0,
                "data", Map.of(
                        "reply", reply,
                        "chatId", chatId
                )
        );
    }

    /**
     * GET /api/chat/health
     * 健康检查接口，用于确认服务是否正常运行。
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "历史专家 AI");
    }
}
```

---

### 步骤 7：配置类 `AppConfig.java`

**路径**：`src/main/java/org/example/historyexpertai/config/AppConfig.java`

如果把 `ChatMemory` 提升为全局 Bean，可以方便多处复用和统一切换存储方式：

```java
package org.example.historyexpertai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用配置类。
 *
 * 将 ChatMemory 声明为 Bean 的好处：
 *   1. 单例管理，整个应用共享同一份记忆存储
 *   2. 切换存储方式只需改一处（例如从 InMemory 改为 Jdbc）
 *   3. 其他组件可以直接 @Autowired ChatMemory 获取
 */
@Configuration
public class AppConfig {

    @Bean
    public ChatMemory chatMemory() {
        // 生产环境切换示例：
        // return new JdbcChatMemory(dataSource);              // JDBC 持久化
        // return new RedisChatMemory(redisConnectionFactory);  // Redis 缓存
        return new InMemoryChatMemory();                        // 开发/测试用内存
    }
}
```

> 如果使用了 `AppConfig`，`HistoryExpertApp` 构造函数的 `ChatMemory` 可以直接通过参数注入，省去手动 `new`：
>
> ```java
> public HistoryExpertApp(ChatModel chatModel, ChatMemory chatMemory) {
>     this.chatClient = ChatClient.builder(chatModel)
>             .defaultSystem(SYSTEM_PROMPT)
>             .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
>             .build();
> }
> ```

---

### 步骤 8：单元测试 `HistoryExpertAiApplicationTests.java`

**路径**：`src/test/java/org/example/historyexpertai/HistoryExpertAiApplicationTests.java`

```java
package org.example.historyexpertai;

import org.example.historyexpertai.app.HistoryExpertApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史专家 AI 的单元测试。
 *
 * @SpringBootTest 启动完整 Spring 上下文（实际调用大模型 API，非 Mock）。
 */
@SpringBootTest
class HistoryExpertAiApplicationTests {

    @Autowired
    private HistoryExpertApp historyExpertApp;

    @Test
    @DisplayName("AI 应能记住用户在之前的对话中提到的名字和偏好")
    void testMultiTurnMemory() {
        // 为本次测试生成唯一会话 ID
        String chatId = UUID.randomUUID().toString();

        // ===== 第 1 轮对话：告诉 AI 个人信息 =====
        String firstReply = historyExpertApp.doChat(
                "我叫朱元璋，我最喜欢的数字是42。", chatId);
        System.out.println("第 1 轮 AI 回复: " + firstReply);

        // ===== 第 2 轮对话：测试 AI 是否记得 =====
        String secondReply = historyExpertApp.doChat(
                "请问我叫什么名字？我最喜欢的数字是几？", chatId);
        System.out.println("第 2 轮 AI 回复: " + secondReply);

        // 断言：回复中应包含 "朱元璋" 和 "42"
        assertThat(secondReply).contains("朱元璋");
        assertThat(secondReply).contains("42");
    }

    @Test
    @DisplayName("不同 chatId 的会话应相互隔离，互不干扰")
    void testSessionIsolation() {
        // 用户 A
        String chatIdA = UUID.randomUUID().toString();
        historyExpertApp.doChat("我叫曹操，我是魏国的。", chatIdA);

        // 用户 B
        String chatIdB = UUID.randomUUID().toString();
        historyExpertApp.doChat("我叫刘备，我是蜀国的。", chatIdB);

        // 用户 A 再来问 —— 应该回答曹操、魏国，而不是刘备
        String replyA = historyExpertApp.doChat("我叫什么名字？我是哪国的？", chatIdA);
        System.out.println("用户 A 查询结果: " + replyA);

        assertThat(replyA)
                .contains("曹操")
                .doesNotContain("刘备");
    }
}
```

---

## 四、核心原理深入解析

### 4.1 Advisor 责任链工作流程

```
用户请求: "我叫什么名字？"
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│  Advisor.next(request)  责任链入口               │
│                                                  │
│  ┌────────────────────────────┐                  │
│  │ MessageChatMemoryAdvisor   │                  │
│  │                            │                  │
│  │  before(request):          │                  │
│  │    1. 从 request 取 chatId │                  │
│  │    2. chatMemory.get(      │                  │
│  │         chatId,            │                  │
│  │         retrieveSize=10    │                  │
│  │       )                    │                  │
│  │    3. 将历史消息列表       │                  │
│  │       注入 request.messages│                  │
│  │    4. return 修改后的请求  │                  │
│  └─────────────┬──────────────┘                  │
│                ▼                                  │
│  ┌────────────────────────────┐                  │
│  │  大模型 (GPT-4o / 通义等)  │                  │
│  │  接收完整 Prompt 并生成回复│                  │
│  └─────────────┬──────────────┘                  │
│                ▼                                  │
│  ┌────────────────────────────┐                  │
│  │ MessageChatMemoryAdvisor   │                  │
│  │                            │                  │
│  │  after(response):          │                  │
│  │    1. chatMemory.add(      │                  │
│  │         chatId,            │                  │
│  │         userMessage        │                  │
│  │       )                    │                  │
│  │    2. chatMemory.add(      │                  │
│  │         chatId,            │                  │
│  │         assistantMessage   │                  │
│  │       )                    │                  │
│  │    3. return 原始响应      │                  │
│  └────────────────────────────┘                  │
└─────────────────────────────────────────────────┘
                    │
                    ▼
              返回给用户
```

### 4.2 最终发给 LLM 的 Prompt 结构

```
┌──────────────────────────────────┐
│  [SYSTEM]                        │  ← defaultSystem() 固定在第一行
│  你是一位风趣幽默的历史知识专家…   │
├──────────────────────────────────┤
│  [USER]    我叫NEO，最喜欢的数字是7   │  ← 从 ChatMemory 取回的历史消息#1
├──────────────────────────────────┤
│  [ASSISTANT] 你好NEO！7是个很棒的数字… │  ← 从 ChatMemory 取回的历史消息#2
├──────────────────────────────────┤
│  [USER]    我叫什么名字？最喜欢的数字是几？ │  ← 当前用户输入
└──────────────────────────────────┘
```

### 4.3 ChatMemory 内部数据结构示意

```java
// InMemoryChatMemory 内部本质上是：
ConcurrentHashMap<String, List<Message>> conversations = new ConcurrentHashMap<>();

// 存入（after 阶段自动执行）：
conversations.computeIfAbsent("chat-abc", k -> new ArrayList<>())
              .add(new UserMessage("我叫NEO"));
conversations.computeIfAbsent("chat-abc", k -> new ArrayList<>())
              .add(new AssistantMessage("你好NEO！"));

// 读取（before 阶段自动执行）：
List<Message> history = conversations
    .getOrDefault("chat-abc", List.of())
    .subList(Math.max(0, size - 10), size);  // 取最近10条
```

### 4.4 `MessageChatMemoryAdvisor` vs `PromptChatMemoryAdvisor`

| 维度 | MessageChatMemoryAdvisor | PromptChatMemoryAdvisor |
|------|--------------------------|--------------------------|
| 注入方式 | 独立消息块（保留 USER/ASSISTANT 角色） | 拼成纯文本 String |
| 角色信息 | ✅ 完整保留 | ❌ 丢失 |
| 对大模型理解 | 更友好，结构化上下文 | 角色混淆，效果较差 |
| 推荐程度 | ⭐⭐⭐⭐⭐ | ⭐⭐ |

---

## 五、运行与测试

### 5.1 设置 API Key

```bash
# Windows PowerShell:
$env:OPENAI_API_KEY = "sk-your-key-here"

# Linux / macOS:
export OPENAI_API_KEY="sk-your-key-here"
```

### 5.2 启动项目

```bash
cd D:\idea\project\history-expert-ai

# 使用 Maven Wrapper（无需安装 Maven）
mvnw clean package -DskipTests
java -jar target/history-expert-ai-1.0.0.jar

# 或者直接运行
mvnw spring-boot:run
```

### 5.3 测试接口

```bash
# 第 1 轮对话
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"我叫朱元璋，我最喜欢的数字是42。\",\"chatId\":\"test-001\"}"

# 第 2 轮对话（验证记忆）
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"我叫什么名字？我最喜欢的数字是几？\",\"chatId\":\"test-001\"}"
```

---

## 六、生产环境升级建议

| 组件 | 开发环境 | 生产环境建议 |
|------|----------|---------------|
| ChatMemory | `InMemoryChatMemory` | `JdbcChatMemory`（MySQL）或 `RedisChatMemory` |
| API Key | 环境变量 | Vault / K8s Secret / 配置中心 |
| 日志 | DEBUG | INFO（避免泄露对话内容） |
| 并发控制 | 无 | 同一 chatId 加分布式锁（Redis） |

---

## 七、常见问题

### Q1: 如何对接国内大模型（如通义千问、DeepSeek）？

修改 `application.properties` 中的 `base-url` 和 `api-key`：

```properties
spring.ai.openai.api-key=${DASHSCOPE_API_KEY}
spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
spring.ai.openai.chat.options.model=qwen-plus
```

Spring AI 的 OpenAI Starter 兼容所有 OpenAI 接口格式的模型。

### Q2: 如何实现流式输出？

在 `HistoryExpertApp` 中新增流式方法，返回 `Flux<String>`：

```java
public Flux<String> doChatStream(String message, String chatId) {
    return this.chatClient
            .prompt()
            .user(message)
            .advisors(spec -> spec
                .param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                .param(MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
            .stream()                    // ← 改用 stream()
            .content();                  // ← 直接返回 Flux<String>
}
```

### Q3: ChatMemory 内存会不会撑爆？

- `InMemoryChatMemory` 默认无上限，长时间运行会 OOM
- 生产环境改用 `RedisChatMemory` 并设置 TTL 过期时间
- 或者在 `AppConfig` 中自定义包装类，加 LRU 淘汰策略