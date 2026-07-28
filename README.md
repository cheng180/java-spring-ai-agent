# 智能汽车销售客服 — 开发记录

> Spring AI 2.0 + SQLite + DeepSeek，从 Dify 分支流程重构为 LLM 自主决策 Agent

---

## 一、项目概述

基于 Spring Boot 4.1.0 + Spring AI 2.0.0 的智能汽车销售客服系统。  
核心思路：**LLM 自主决策，代码只提供工具**，摒弃 Dify 式的固定分支流程。

---

## 二、架构演进

### v1.0 — Dify 式分支流程（已废弃）

```
用户输入 → 库存搜索 → 意图分类器(5选1) → 路由 → 分支处理器 → 回复
```

**问题**：
- 分类器是瓶颈：人类表达无限，5 个桶装不下
- "法拉利你这又没有"被分类为 SPECIFIC_CAR → 硬编码回复"售罄"
- 售罄路径走了硬编码，LLM 根本没机会判断真实意图
- 分类器 + 路由 + 5 分支 + 3 Advisor = 14 个 Java 文件，维护成本高

### v2.0 — LLM 自主决策 Agent（当前版本）

```
用户输入 → ChatClient(system prompt + 3个@Tool) → LLM 自己判断 → 回复
```

**优势**：
- 没有分类器，LLM 自己理解意图
- LLM 自主决定何时调用哪个工具（searchInventory / getAllCars / getStoreInfo）
- 代码量从 20 个文件缩减到 8 个
- 灵活应对各种表达方式

---

## 三、最终文件结构

```
src/main/java/org/example/ai/
├── AiApplication.java              # Spring Boot 启动
├── agent/
│   ├── CarSalesAgent.java          # 核心 Agent（LLM + 工具 + 记忆）
│   ├── prompt/
│   │   └── PromptTemplates.java    # 唯一 System Prompt
│   └── tool/
│       └── CarSalesTools.java      # 3 个 @Tool 方法
├── config/
│   ├── AiConfig.java               # ChatClient.Builder Bean
│   └── DatabaseInitializer.java    # 启动时建库+种子数据
├── controller/
│   └── ChatController.java         # REST API（/api/chat）
└── model/
    └── CarInventory.java           # 车辆数据模型

src/main/resources/
├── application.properties          # SQLite + DeepSeek 配置
├── schema.sql                      # 数据库表结构（参考文档）
├── data.sql                        # 种子数据（参考文档）
└── static/
    └── index.html                  # 前端聊天页面

company_inventory.db                # SQLite 数据库文件（启动自动生成）
```

---

## 四、数据库

### 表结构

**inventory**（车辆库存，25 条数据）：

| 字段 | 类型 | 说明 |
|------|------|------|

| stock_code | TEXT | 库存编号（如 BYD-001） |
| brand | TEXT | 品牌 |
| model | TEXT | 车型 |
| variant | TEXT | 具体配置款 |
| price_guide | REAL | 指导价（万元） |
| price_sale | REAL | 实际售价（万元） |
| stock | INTEGER | 库存数量 |
| monthly_sales | INTEGER | 月销量 |
| popularity | REAL | 热度评分 0-10 |
| fuel_type | TEXT | 能源类型 |
| category | TEXT | 分类（轿车/SUV/MPV） |
| description | TEXT | 车辆简介 |

**store_config**（6 家门店）：北京朝阳/海淀、上海浦东、深圳南山、成都锦江、广州天河

### 初始化方式

`DatabaseInitializer.java` 在应用启动时自动执行：
1. 创建表（`CREATE TABLE IF NOT EXISTS`）
2. 检查数据是否为空
3. 空则插入种子数据，已有数据则跳过

---

## 五、Agent 设计

### System Prompt 核心逻辑

```
1. 闲聊 → 可回应，连续超 3 轮礼貌结束
2. 问地址/电话 → 直接从 prompt 里的公司信息回答
3. 买车相关 →
   3a. 提了具体车 → 调 searchInventory → 报价+库存+追问需求
   3b. 求推荐 → 调 getAllCars → 先问预算/用途 → 从库存推荐 2-3 款
   3c. 模糊表达 → 共情+提问缩小范围（后续接知识库）
4. 试驾/到店 → 问城市→告知门店→预留对接接口
```

### 工具（@Tool）

| 工具 | 触发条件 | 说明 |
|------|---------|------|
| `searchInventory(query)` | 用户提具体车型 | 查库存、价格、热度 |
| `getAllCars()` | 用户求推荐 | 全部在库车辆（按热度排序） |
| `getStoreInfo()` | 用户问门店 | 所有门店地址电话 |

### 对话记忆

- `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`
- `MessageChatMemoryAdvisor` 自动注入历史消息
- 按 `userId` 隔离会话

---

## 六、前端

### 设计风格

仿 Moonshot/Kimi 暖色调：
- 奶油白底色 + 4 个浮动琥珀光晕（视差跟随鼠标）
- 网格纹理叠加
- 光标光晕追踪（RAF 平滑追赶，0.6s 滞后）
- 欢迎卡片 + 快捷问题标签
- 消息弹入动画

### 交互

- Enter 发送，Shift+Enter 换行
- 快捷问题一键发送
- 首次对话自动移除欢迎卡片

---

## 七、API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 对话（JSON 请求体） |
| `/chat` | GET | 浏览器快速测试 |
| `/api/health` | GET | 健康检查 |
| `/` | GET | 前端聊天页面 |
| `/h2-console` | — | 已移除（H2→SQLite） |

### 请求格式

```json
POST /api/chat
{
    "message": "比亚迪宋PLUS怎么样？",
    "userId": "web-user-xxx"
}
```

### 响应格式

```json
{
    "success": true,
    "data": {
        "reply": "宋PLUS DM-i 目前售价 15.88 万..."
    }
}
```

---

## 八、配置

### application.properties 关键项

```properties
# 数据库
spring.datasource.url=jdbc:sqlite:company_inventory.db
spring.datasource.driver-class-name=org.sqlite.JDBC

# LLM（当前用 DeepSeek）
spring.ai.model.chat=deepseek
spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY}
spring.ai.deepseek.chat.model=deepseek-chat

# 公司信息
company.name=卖好车汽车销售有限公司
company.phone=400-888-9999
```

### 环境变量

- `DEEPSEEK_API_KEY`：DeepSeek API Key（默认值已在配置中）
- `OPENAI_API_KEY`：OpenAI（备用）
- `ANTHROPIC_API_KEY`：Anthropic/Claude（备用）

---

## 九、启动

```bash
cd D:\idea\project\AI
./mvnw spring-boot:run
```

浏览器打开 `http://localhost:8080/`

---

## 十、依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 4.1.0 | 基础框架 |
| Spring AI | 2.0.0 | LLM 集成、Function Calling、ChatMemory |
| SQLite JDBC | 3.45.1.0 | 本地数据库 |
| DeepSeek | — | 主力模型（chat + tool calling） |
| OpenAI / Anthropic | — | 备用模型 |

---

## 十一、后续预留

| 功能 | 状态 | 说明 |
|------|------|------|
| 知识库检索 | 待接入 | Python demo 已有 4 个 .md 知识库 + 2849 款车数据 |
| 模糊语义排序 | 待实现 | 设计文档第5章的四维排序算法 |
| 双模型验证 | 待实现 | 防 AI 幻觉 |
| 闲聊计数器 | prompt 已写，代码未跟踪 | 需在 CarSalesAgent 加计数逻辑 |
| 试驾/到店对接 | 接口预留 | 需对接企微 RPA |
| 流式输出 | 待实现 | ChatClient 支持 `.stream()` |

---

## 十二、开发时间线

| 时间 | 内容 |
|------|------|
| 会话开始 | 阅读设计文档、Python demo、Dify DSL |
| 第1步 | H2 内存数据库 → 用户要求改 SQLite |
| 第1步修正 | SQLite + DatabaseInitializer，25 款车 + 6 门店 |
| 第2步 | Dify 式 5 分支架构（14 个文件） |
| 前端 v1 | 暗紫渐变主题 |
| 前端 v2 | 暖色调 Moonshot 风格 + 鼠标光晕追踪 + 光斑视差 |
| 提示词优化 | 去除生硬规则，合并分支2的两步LLM为一步 |
| 架构重构 | 删除分支流程，改为 LLM 自主决策 Agent（20→8 文件） |
| API 适配 | Spring AI 2.0 API：MessageWindowChatMemory、MessageChatMemoryAdvisor.builder() |
| README | 整理完整开发记录 |
