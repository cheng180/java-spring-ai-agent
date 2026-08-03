# Spring AI 智能汽车客服 —— 完整设计方案

> 从 Dify + Python demo 迁移到 Spring AI 2.0 + Java 的技术方案，
> 涵盖知识库构建、模糊语义处理、多问题命中、AI幻觉双模型验证、动态配置等核心问题。

---

## 目录

1. [现有系统分析](#1-现有系统分析)
2. [整体架构设计](#2-整体架构设计)
3. [Dify → Spring AI 概念映射（快速上手）](#3-dify--spring-ai-概念映射)
4. [知识库构建与检索方案](#4-知识库构建与检索方案)
5. [模糊语义问题 —— 排序算法设计](#5-模糊语义问题--排序算法设计)
6. [多问题命中处理方案](#6-多问题命中处理方案)
7. [AI幻觉与双模型验证方案](#7-ai幻觉与双模型验证方案)
8. [动态配置与多仓店支持](#8-动态配置与多仓店支持)
9. [RPA 企业微信消息发送方案](#9-rpa-企业微信消息发送方案)
10. [Agent 框架骨架 —— Dify DSL → Spring AI 完整实现](#10-agent-框架骨架--dify-dsl--spring-ai-完整实现)
11. [分层实施路线图](#11-分层实施路线图)

---

## 1. 现有系统分析

### 1.1 现有项目结构

```
AI/
├── pom.xml                          # Spring Boot 4.1.0 + Spring AI 2.0.0
├── src/main/java/.../               # 当前只有 ChatController（基础对话）
├── custom-agent/
│   ├── Customer-Agent-main/         # PyQt6 拼多多桌面客服（完整工程）
│   │   ├── Agent/CustomerAgent/     # Agent 核心循环（LLM + Tool Calling）
│   │   ├── Message/                 # 消息队列 + Handler 责任链
│   │   ├── Channel/pinduoduo/       # 拼多多渠道（WebSocket）
│   │   ├── database/                # SQLite + jieba 分词检索
│   │   └── 改造方案.md              # PDD → 视频号+Dify 迁移方案
│   └── customer-try/                # Dify Chatflow Demo（FastAPI + MCP）
│       ├── main.py                  # FastAPI 代理 + 聊天 UI
│       ├── mcp_server.py            # MCP 库存查询工具（3个）
│       ├── init_company_db.py       # SQLite 车辆数据库（8条）
│       ├── knowledge_base/          # 销售话术 + 模糊语义 + 汽车百科
│       ├── scrape/car_data.json     # 网易汽车爬虫（2849款车）
│       └── photos/                  # 车辆实拍照片
├── 流程.md                          # 业务流程文档
├── 进度/进度.md                     # 开发进度，包含待解决问题
└── 智能客服框架流程.png             # 流程架构图
```

### 1.2 现有系统的关键设计

**Agent 核心循环（customer_agent.py 第197-273行）：**
```
while loop_count < max_loops:
    1. LLMClient.chat(messages)         → 调 LLM
    2. 检查是否有 tool_calls            → 没有就返回答案
    3. 并行执行所有工具                  → ToolExecutor
    4. 把工具结果追加到 messages         → 回传 LLM
    5. loop_count += 1                  → 继续循环
```

**知识库检索（knowledge_service.py）：**
- jieba 分词 → SQL LIKE 匹配
- 两张核心表：`ProductKnowledge` + `CustomerServiceKnowledge`
- 简单的关键词匹配，没有向量化，没有语义相似度

**Dify Chatflow 架构：**
```
用户 → FastAPI(:7860) → Dify API(:80/v1) → LLM + 知识库 + MCP工具
                                      ↑
                              MCP Server(:9020)
                           search_inventory / check_stock / get_price
                                      ↑
                              SQLite (company_inventory.db)
```

### 1.3 待解决的核心问题（来自进度.md）

| # | 问题 | 现象 |
|---|------|------|
| 1 | **模糊语义** | 客户说"还有吗那款车"，无法定位是哪个品牌的车 |
| 2 | **多问题命中** | 客户一口气问好几个问题，回复只回一个 |
| 3 | **AI幻觉** | 编造价格参数、答非所问 |
| 4 | **上下文断裂** | 同一段对话中明明聊过某车，却说"无法回复" |
| 5 | **动态配置** | 公司地址需要根据客户IP定位最近门店 |
| 6 | **闲聊终止** | 闲聊超过3次要自动终止 |

---

## 2. 整体架构设计

### 2.1 目标架构（Spring AI 版）

```
                        ┌─────────────────────────────────────┐
                        │          Spring AI Layer             │
                        │                                      │
   ┌──────────┐        │  ┌─────────────────────────────────┐ │
   │ 前端聊天UI │──────▶│  │ ChatController (REST API)        │ │
   │ (已实现)   │        │  │ /api/chat  /api/chat/stream      │ │
   └──────────┘        │  └──────────────┬──────────────────┘ │
                        │                 │                     │
                        │  ┌──────────────▼──────────────────┐ │
                        │  │ CustomerServiceAgent (核心Agent) │ │
                        │  │  · System Prompt 构建             │ │
                        │  │  · Tool Calling 编排              │ │
                        │  │  · 对话历史管理                   │ │
                        │  └──────┬──────────────┬───────────┘ │
                        │         │              │              │
                        │  ┌──────▼──────┐ ┌─────▼──────────┐ │
                        │  │ Knowledge    │ │ Tool Registry   │ │
                        │  │ Retriever    │ │  · search_cars  │ │
                        │  │ (RAG引擎)    │ │  · check_stock  │ │
                        │  │              │ │  · get_price    │ │
                        │  └──────┬──────┘ │  · find_store   │ │
                        │         │        └────────────────┘ │
                        └─────────┼──────────────────────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │             │              │
              ┌─────▼────┐ ┌─────▼────┐  ┌─────▼────┐
              │ Vector    │ │ SQLite   │  │ External │
              │ Store     │ │ 库存数据  │  │ APIs     │
              │ (PGVector │ │          │  │ (IP定位)  │
              │  /Redis)  │ │          │  │          │
              └──────────┘ └──────────┘  └──────────┘
```

### 2.2 包结构设计

```
src/main/java/org/example/ai/
├── AiApplication.java
├── config/
│   ├── CompanyInfoProperties.java      # 公司信息配置
│   ├── StoreConfigProperties.java      # 多门店配置
│   └── AiConfig.java                   # ChatClient Bean 配置
├── agent/
│   ├── CustomerServiceAgent.java       # 核心 Agent（编排层）
│   ├── ToolRegistry.java               # 工具注册中心
│   └── ConversationManager.java        # 对话历史 + 闲聊计数
├── knowledge/
│   ├── KnowledgeRetriever.java         # 知识检索接口（策略模式）
│   ├── VectorKnowledgeRetriever.java   # 向量检索实现
│   ├── HybridKnowledgeRetriever.java   # 混合检索（向量+关键词+热度）
│   ├── CarRankingService.java          # 车辆排序算法（解决模糊语义）
│   └── KnowledgeDocument.java          # 知识文档模型
├── tools/
│   ├── SearchInventoryTool.java        # 搜索库存工具
│   ├── CheckStockTool.java             # 精确查库存工具
│   ├── GetPriceTool.java               # 获取报价工具
│   ├── FindNearestStoreTool.java       # 查找最近门店
│   └── TransferToHumanTool.java        # 转人工工具
├── verification/
│   ├── DualModelVerifier.java          # 双模型验证器
│   └── HallucinationDetector.java      # 幻觉检测器
├── controller/
│   └── ChatController.java             # REST API
└── model/
    ├── ChatRequest.java
    ├── ChatResponse.java
    └── CarInventory.java
```

---

## 3. Dify → Spring AI 概念映射

### 3.1 核心概念对应表

| Dify 概念 | Spring AI 等价物 | 说明 |
|-----------|-----------------|------|
| **Chatflow / Workflow** | `ChatClient` + Advisor 链 | ChatClient 是核心入口，Advisor 链实现流程编排 |
| **知识库** | `VectorStore` (PGVector/Redis Stack) | Spring AI 内置支持多种向量数据库 |
| **MCP 工具** | `@Tool` 注解的方法 | Spring AI 的 Function Calling 机制 |
| **System Prompt** | `.system()` 调用 | ChatClient 的 fluent API |
| **对话记忆 (conversation_id)** | `ChatMemory` / `MessageChatMemoryAdvisor` | 内置支持多轮对话 |
| **节点分支 (if-else)** | Java 代码逻辑 + Advisor 链 | 用代码实现分支，比 Dify 画布更灵活 |
| **LLM 节点** | `chatClient.prompt().call()` | 直接调用 |
| **HTTP 请求节点** | Spring `RestClient` / `WebClient` | 标准 Spring 生态 |
| **代码节点** | 普通 Java 方法 | 直接写代码 |
| **变量/参数传递** | 方法参数 + ThreadLocal / Context 对象 | 类型安全 |

### 3.2 快速上手指南

**Dify 中你做了这些事 → Spring AI 中这样做：**

```java
// Dify: Chatflow → 知识检索节点 → LLM节点 → 回复
// Spring AI 等价写法：

@RestController
public class ChatController {
    
    private final ChatClient chatClient;
    private final KnowledgeRetriever knowledgeRetriever;
    
    @PostMapping("/api/chat")
    public String chat(@RequestBody ChatRequest request) {
        // 1. 检索知识库（相当于 Dify 的知识检索节点）
        String knowledge = knowledgeRetriever.search(request.message());
        
        // 2. 调用 LLM（相当于 Dify 的 LLM 节点）
        return chatClient.prompt()
            .system(s -> s
                .text("你是汽车销售客服。以下是相关知识：\n{knowledge}")
                .param("knowledge", knowledge)
            )
            .user(request.message())
            .call()
            .content();
    }
}
```

```java
// Dify: MCP 工具 → Spring AI Function Calling
// 定义一个工具方法，用 @Tool 注解（相当于 MCP tool）

@Component
public class CarInventoryTools {
    
    private final JdbcTemplate jdbc;
    
    @Tool(description = "搜索公司车辆库存，支持品牌、车型、价格范围")
    public String searchInventory(String query) {
        // 数据库查询逻辑
        return formatResults(jdbc.query(...));
    }
    
    @Tool(description = "精确查询某款车的库存和价格")
    public String checkStock(String brand, String model) {
        // ...
    }
}
```

---

## 4. 知识库构建与检索方案

### 4.1 知识库三层架构

```
┌─────────────────────────────────────────────────────────┐
│  第1层：结构化数据（SQLite/MySQL）                        │
│  · 车辆库存表（品牌、车型、配置、价格、库存、照片）          │
│  · 门店表（名称、地址、经纬度、电话）                       │
│  · 用户画像表（偏好、历史对话）                             │
│  检索方式：SQL 精确查询 + LIKE 模糊匹配                     │
├─────────────────────────────────────────────────────────┤
│  第2层：向量化知识库（PGVector / Redis Stack）             │
│  · 销售话术文档（购车引导话术.md）                         │
│  · 模糊语义处理文档（模糊语义情绪处理话术.md）              │
│  · 汽车百科（汽车百科知识库.md + 2849款车数据）            │
│  · 客服FAQ                                                 │
│  检索方式：向量相似度 (Cosine Similarity)                  │
├─────────────────────────────────────────────────────────┤
│  第3层：实时/动态数据                                      │
│  · 各仓店库存热度（销售量排行）                             │
│  · 促销活动                                                │
│  · 用户地理位置 → 最近门店                                  │
│  检索方式：业务逻辑计算                                     │
└─────────────────────────────────────────────────────────┘
```

### 4.2 向量知识库实现方案

**Spring AI 支持的开箱即用 VectorStore：**

| 方案 | 适用场景 | 优缺点 |
|------|---------|--------|
| **PGVector** | 已有 PostgreSQL | 推荐：Spring AI 内置支持好，性能高，运维简单 |
| **Redis Stack** | 已有 Redis | 轻量，适合中小规模 |
| **SimpleVectorStore** | 开发/测试 | 内存存储，无需外部依赖，适合快速原型 |

**推荐使用 PGVector**，因为你的车辆数据（2849款）需要持久化存储且需要复杂查询。

### 4.3 知识库构建流程

```
原始文档 (.md / .json) 
    │
    ▼
┌──────────────────┐
│ 1. 文档分割        │  ← TokenTextSplitter（按语义边界切 chunk）
│    chunk_size=500  │
│    overlap=50      │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ 2. 向量化 Embedding│  ← 用 DeepSeek / OpenAI embedding
│    1536维向量      │     spring.ai.deepseek.embedding.*
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ 3. 存入 VectorStore│  ← PGVector / Redis
│    + metadata     │     每条记录带：来源、分类、标签、热度分
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ 4. 检索时混合排序   │  ← 向量相似度 + 关键词匹配 + 热度加权
└──────────────────┘
```

### 4.4 Spring AI 知识库代码骨架

```java
@Component
public class KnowledgeBaseInitializer {
    
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;  // DeepSeek Embedding
    
    /**
     * 应用启动时，将知识库文档向量化并存入 VectorStore
     */
    @PostConstruct
    public void initKnowledgeBase() {
        // 1. 读取知识库文件
        Resource[] resources = {
            new ClassPathResource("knowledge/购车引导话术.md"),
            new ClassPathResource("knowledge/模糊语义情绪处理话术.md"),
            new ClassPathResource("knowledge/汽车百科知识库.md"),
        };
        
        // 2. 分割 + 向量化 + 存储
        for (Resource resource : resources) {
            List<Document> docs = new TokenTextSplitter(500, 50, 10, 1000, true)
                .apply(List.of(new Document(resource.getContentAsString())));
            vectorStore.add(docs);
        }
    }
}
```

### 4.5 混合检索引擎（核心）

```java
@Component
public class HybridKnowledgeRetriever implements KnowledgeRetriever {
    
    private final VectorStore vectorStore;          // 语义相似度
    private final JdbcTemplate jdbcTemplate;         // 结构化数据查询
    private final CarRankingService rankingService;  // 排序加权
    
    /**
     * 混合检索：向量相似度 + 关键词匹配 + 热度排序
     */
    public KnowledgeResult search(String query, StoreLocation store) {
        // ====== 第1路：向量语义检索 ======
        List<Document> semanticResults = vectorStore.similaritySearch(
            SearchRequest.query(query).withTopK(10)
        );
        
        // ====== 第2路：SQL 精确匹配 ======
        List<CarInventory> sqlResults = jdbcTemplate.query(
            "SELECT * FROM inventory WHERE brand LIKE ? OR model LIKE ? OR variant LIKE ?",
            ps -> { /* 参数绑定 */ },
            new CarInventoryRowMapper()
        );
        
        // ====== 第3路：关键词提取 + 品牌识别 ======
        ExtractedKeywords keywords = extractKeywords(query);
        // 从 query 中识别品牌（比亚迪、特斯拉、小鹏...）
        // 从 query 中识别车型（SUV、轿车、纯电、混动...）
        // 从 query 中识别预算（10万、20万、30万...）
        
        // ====== 合并 + 加权排序 ======
        return rankingService.rank(
            semanticResults,   // 语义相似度得分
            sqlResults,        // 精确匹配结果
            keywords,          // 提取的关键词
            store              // 门店热销数据
        );
    }
}
```

---

## 5. 模糊语义问题 —— 排序算法设计

### 5.1 问题场景还原

> 顾客："还有吗那款车"  
> 问题：query 中没有明确的品牌/车型，单纯相似度匹配不到任何车。

### 5.2 根本原因分析

| 层次 | 问题 |
|------|------|
| **检索层** | "还有吗那款车" 和 "比亚迪宋PLUS DM-i" 的向量相似度极低 |
| **上下文层** | 如果上一轮聊过某款车，应该从对话历史推断指代 |
| **热度层** | 没有利用"这个门店最近什么车卖得最火"的业务知识 |

### 5.3 解决方案：四维排序算法

```
最终得分 = α × 语义相似度 + β × 上下文关联 + γ × 热度权重 + δ × 关键词匹配

其中：
  α = 0.30  语义相似度（向量 Cosine）
  β = 0.35  上下文关联（对话历史中提到的车权重翻倍）
  γ = 0.25  热度权重（门店/区域热销排行）
  δ = 0.10  关键词匹配（品牌名、车型名直接命中）
```

### 5.4 上下文关联实现（解决"那款车"指代问题）

```java
@Component
public class ContextAwareRetriever {
    
    /**
     * 从对话历史中提取"最近提到过的车"
     * 用于解决 "那款车" "刚才说的那个" 等指代问题
     */
    public List<String> extractRecentlyMentionedCars(
            List<Message> conversationHistory) {
        
        // 品牌 + 车型关键词列表
        Set<String> knownBrands = Set.of(
            "比亚迪", "特斯拉", "小鹏", "理想", "蔚来", "问界", "极氪", "领克"
        );
        Set<String> knownModels = Set.of(
            "宋PLUS", "秦PLUS", "Model 3", "Model Y", "P7", "G7",
            "L6", "L8", "ET5", "汉EV", "海鸥", "M9"
        );
        
        List<String> mentionedCars = new ArrayList<>();
        
        // 从最近的对话往旧的遍历
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            Message msg = conversationHistory.get(i);
            String content = msg.getContent();
            
            // 检测品牌+车型组合
            for (String brand : knownBrands) {
                for (String model : knownModels) {
                    if (content.contains(brand) || content.contains(model)) {
                        mentionedCars.add(brand + " " + model);
                    }
                }
            }
            
            if (mentionedCars.size() >= 3) break; // 最近3款车即可
        }
        
        return mentionedCars;
    }
}
```

### 5.5 热度排序实现（解决"卖得最好的车"推断）

```java
@Component
public class CarRankingService {
    
    /**
     * 综合排序
     * 
     * 关键设计：当语义相似度全部很低（模糊语义场景）时，
     * 热度权重自动提升，用"门店热销"兜底推荐
     */
    public List<RankedCar> rank(
            List<DocWithScore> semanticResults,
            List<CarInventory> exactMatches,
            ExtractedKeywords keywords,
            StoreLocation store,
            List<String> recentlyMentionedCars  // 上下文关联
    ) {
        List<RankedCar> candidates = new ArrayList<>();
        
        // 1. 融合所有候选车辆
        Set<String> seen = new HashSet<>();
        
        for (var doc : semanticResults) {
            String carKey = extractCarKey(doc);
            if (seen.add(carKey)) {
                double semanticScore = normalizeScore(doc.score); // 0~1
                
                // 模糊语义检测：如果最高相似度 < 0.5，说明可能是模糊表达
                // 此时提升上下文关联和热度的权重
                boolean isVague = semanticScore < 0.5;
                
                double contextBoost = recentlyMentionedCars.contains(carKey) ? 0.35 : 0;
                double hotScore = getStoreHotScore(store.id(), carKey);
                
                // 动态调整权重
                double alpha = isVague ? 0.10 : 0.30;  // 模糊时降低语义权重
                double beta  = isVague ? 0.50 : 0.35;  // 模糊时提升上下文权重
                double gamma = isVague ? 0.35 : 0.25;  // 模糊时提升热度权重
                
                double finalScore = alpha * semanticScore 
                                  + beta * contextBoost 
                                  + gamma * hotScore;
                
                candidates.add(new RankedCar(carKey, finalScore, semanticScore, hotScore));
            }
        }
        
        // 2. 按最终得分降序排列
        candidates.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        
        return candidates;
    }
    
    /**
     * 获取某款车在某个门店的热度得分（0~1）
     * 数据来源：门店销售统计表，每天更新
     */
    private double getStoreHotScore(Long storeId, String carKey) {
        // 查询该门店最近30天的车辆咨询量/成交量
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(inquiry_count, 0) / MAX_INQUIRY FROM store_car_hot "
            + "WHERE store_id = ? AND car_key = ? AND stat_date >= DATE('now', '-30 days')",
            Double.class, storeId, carKey
        );
    }
}
```

### 5.6 关键设计：当全部都匹配不上时

```java
/**
 * 兜底策略：当所有检索得分都低于阈值时
 * 不直接说"找不到"，而是给出引导式追问
 */
public String handleLowConfidence(String userQuery, List<RankedCar> results) {
    if (results.isEmpty() || results.get(0).finalScore < 0.3) {
        // 无法命中 → 不是直接放弃，而是用 LLM 生成引导追问
        return chatClient.prompt()
            .system("""
                你是汽车销售客服。用户的问题没有直接匹配到库存中的车辆。
                请根据用户的模糊表达，只问1个问题来缩小范围。
                例如：
                - 用户说"还有吗" → 问"您上次看的是哪款车？"
                - 用户说"太贵了" → 问"您预算大概多少？我帮您找找更合适的"
                - 用户说"推荐一款" → 问"您更关注轿车还是SUV？"
                禁止说"找不到"、"无法匹配"等否定词汇。
                """)
            .user(userQuery)
            .call()
            .content();
    }
    // 有结果，正常返回
    return formatCarResults(results);
}
```

---

## 6. 多问题命中处理方案

### 6.1 问题场景

> 顾客："比亚迪宋怎么样，有没有现车，多少钱，能试驾吗"  
> 一口气问了4个问题。

### 6.2 技术方案：三层处理策略

```
用户输入（长文本，含多个问题）
    │
    ▼
┌─────────────────────────────┐
│ 第1层：问题拆分              │
│ LLM 识别/拆分语义独立问题     │
│ 输入："比亚迪宋怎么样..."     │
│ 输出：["比亚迪宋PLUS配置如何",│
│        "有没有现车",         │
│        "价格多少",          │
│        "可以试驾吗"]         │
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│ 第2层：问题分类 + 优先级排序   │
│ · 核心问题（信息查询类）→ 先答 │
│ · 次要问题（闲聊/负面情绪）→ 后答│
│ · 动作类（试驾/到店）→ 最后引导│
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│ 第3层：逐条回复 or 合并回复   │
│ · 相关的问题合并成一气回答    │
│ · 不相关的问题分条回复        │
│ · 最多拆成3条消息发出         │
└─────────────────────────────┘
```

### 6.3 实现方式：提示词 + Function Calling 混合

**方案A（推荐）：用一次 LLM 调用完成问题拆分+回答**

```java
public String handleMultiQuestion(String userMessage, String knowledge) {
    return chatClient.prompt()
        .system("""
            你是汽车销售客服。如果用户一次问了多个问题：
            
            1. 先识别用户问了几个独立问题
            2. 核心问题（车辆信息、价格、库存）优先详细回答
            3. 引导类问题（试驾、到店）合并到最后作为钩子
            4. 所有回答整合在一个回复中，用换行分隔不同问题
            5. 禁止说"你的第X个问题"这种话——自然过渡
            
            格式示例：
            "比亚迪宋PLUS是15.88万，现在店里有现车。
            这车特别省油，亏电油耗4.5L，满油满电跑1200公里。
            您想试驾的话，周末可以过来，我帮您预约～"
            
            相关知识：
            {knowledge}
            """)
        .param("knowledge", knowledge)
        .user(userMessage)
        .call()
        .content();
}
```

**方案B（高级）：问题拆分 + 并行检索 + 合并回答**

```java
@Component
public class MultiQuestionHandler {
    
    private final ChatClient chatClient;
    private final KnowledgeRetriever retriever;
    
    /**
     * 多问题处理流程
     */
    public String handle(String userMessage) {
        // Step 1: LLM 拆分问题
        List<String> subQuestions = splitQuestions(userMessage);
        
        if (subQuestions.size() <= 1) {
            // 单问题，走正常流程
            return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
        }
        
        // Step 2: 每个子问题单独检索知识库
        List<String> knowledgeList = subQuestions.stream()
            .map(retriever::search)
            .toList();
        
        // Step 3: 合并所有知识 + 原始问题，一次 LLM 调用回答全部
        String mergedKnowledge = String.join("\n---\n", knowledgeList);
        
        return chatClient.prompt()
            .system("""
                用户一次问了{count}个问题。
                请用一段自然的回复，依次回答这些问题。
                如果问题之间有相关性，合并在一起回答会更自然。
                
                相关知识：
                {knowledge}
                """)
            .param("count", subQuestions.size())
            .param("knowledge", mergedKnowledge)
            .user(userMessage)
            .call()
            .content();
    }
    
    /**
     * 用 LLM 拆分多问题
     * 关键：用 JSON 结构化输出，确保格式可靠
     */
    private List<String> splitQuestions(String userMessage) {
        // Spring AI 支持结构化输出
        record SplitResult(List<String> questions) {}
        
        // 用低成本小模型做拆分（比如 deepseek-chat）
        SplitResult result = chatClient.prompt()
            .system("""
                把用户输入拆分成独立的子问题。
                只拆分，不要回答。返回 JSON。
                如果用户只问了一件事，返回单个问题即可。
                """)
            .user(userMessage)
            .call()
            .entity(SplitResult.class); // Spring AI 自动解析 JSON
        
        return result.questions();
    }
}
```

### 6.4 选择建议

| 场景 | 推荐方案 | 理由 |
|------|---------|------|
| 简单多问题（2-3个） | **方案A（纯提示词）** | LLM 足够聪明，拆了反而增加延迟 |
| 复杂多问题（4+个，跨领域） | **方案B（拆分+检索+合并）** | 每个子问题需要针对性检索 |

**实际推荐**：从方案A开始，发现 LLM 回答不够好时再升级到方案B。

---

## 7. AI幻觉与双模型验证方案

### 7.1 AI幻觉的具体表现

| 类型 | 示例 |
|------|------|
| **编造信息** | "比亚迪海鸥 5.98 万"（实际知识库里没有海鸥） |
| **答非所问** | 问"宋PLUS油耗"却回答"宋PLUS是插混SUV..." |
| **数值错误** | 知识库售价15.88万，回答却说18万 |
| **品牌混淆** | 问"小鹏P7"，回答里混入了特斯拉的信息 |

### 7.2 三层幻觉防御体系

```
┌──────────────────────────────────────────────────────┐
│  第1层：提示词约束（低成本，基础防护）                   │
│  · System Prompt 中硬约束"只回答知识库里的信息"          │
│  · 不知道就说不知道，编造=严重违规                       │
│  · 引用知识库内容时保留原始数值                          │
├──────────────────────────────────────────────────────┤
│  第2层：事实校验（中等成本，规则引擎）                    │
│  · 回答中出现价格/参数 → 对比知识库原始数据              │
│  · 回答中出现品牌名 → 检查是否在库存表中                  │
│  · 数值偏差 > 5% → 标记为疑似幻觉                       │
├──────────────────────────────────────────────────────┤
│  第3层：双模型验证（高成本，关键场景）                    │
│  · 另一个模型（不同厂商/不同温度）独立审查                 │
│  · 两个模型回答交叉对比                                   │
│  · 有冲突 → 触发人工审核 or 采信更保守的版本             │
└──────────────────────────────────────────────────────┘
```

### 7.3 第1层：提示词约束（必须做，零成本）

```java
public String buildAntiHallucinationSystemPrompt() {
    return """
        ## 严格规则（违反=严重错误！）
        
        1. **数据来源**：只能使用下方「公司信息」和「知识库检索结果」中的数据。
           绝不能编造任何价格、参数、库存、优惠、门店地址。
           
        2. **价格严格**：报价精确到知识库中的数字。知识库说15.88万就是15.88万，
           不能说"大概15-16万"或"十几万"。
           
        3. **不知道就说不知道**：如果知识库没有相关信息，直接说
           "这个我暂时不太清楚，我帮您转接门店销售给您详细解答～"
           不要猜测，不要类比。
           
        4. **库存实事求是**：知识库显示库存为0就说没货，不要说"应该有"。
           
        5. **禁止品牌对比**：不主动和其他品牌对比，不说"比XX好"。
           用户主动提到竞品时，只说自家车的特点。
        """;
}
```

### 7.4 第2层：规则引擎事实校验

```java
@Component
public class HallucinationDetector {
    
    /**
     * 校验 LLM 回答中的关键事实是否与知识库一致
     */
    public HallucinationReport verify(String llmAnswer, String sourceKnowledge) {
        HallucinationReport report = new HallucinationReport();
        
        // 1. 提取回答中的价格数字
        List<PriceClaim> claims = extractPriceClaims(llmAnswer);
        // 正则: (\\d+(\\.\\d+)?)\\s*万  匹配 "15.88万" "16万"
        
        for (PriceClaim claim : claims) {
            // 2. 在知识库中查找对应的车
            CarInventory car = findCarInKnowledge(claim.carName, sourceKnowledge);
            
            if (car == null) {
                // 回答中提到的车不在知识库里 → 幻觉！
                report.addIssue(new HallucinationIssue(
                    Severity.HIGH,
                    "编造车辆信息",
                    "回答中提到「" + claim.carName + "」但知识库中不存在"
                ));
            } else if (Math.abs(claim.price - car.priceSale()) / car.priceSale() > 0.05) {
                // 价格偏差超过5% → 幻觉！
                report.addIssue(new HallucinationIssue(
                    Severity.MEDIUM,
                    "价格错误",
                    "回答报" + claim.price + "万，实际售价" + car.priceSale() + "万"
                ));
            }
        }
        
        // 3. 检查品牌名
        List<String> mentionedBrands = extractBrands(llmAnswer);
        List<String> knownBrands = extractBrands(sourceKnowledge);
        for (String brand : mentionedBrands) {
            if (!knownBrands.contains(brand)) {
                report.addIssue(new HallucinationIssue(
                    Severity.MEDIUM,
                    "品牌不在知识库",
                    "回答中提到「" + brand + "」但知识库中无此品牌"
                ));
            }
        }
        
        return report;
    }
}
```

### 7.5 第3层：双模型验证

```java
@Component
public class DualModelVerifier {
    
    private final ChatClient primaryClient;   // DeepSeek（主力）
    private final ChatClient reviewerClient;  // 另一个模型做验证，或同一个模型用低温度
    
    /**
     * 双模型验证流程
     * 
     * 不是每次对话都双模型验证（成本高），只在以下场景触发：
     * 1. 回答中包含具体价格/参数
     * 2. 用户表现出强烈的购买意向（"我要订"、"能试驾吗"）
     * 3. 知识库置信度 < 0.5
     */
    public VerifiedResponse verify(String userQuery, String primaryAnswer, 
                                    String knowledge) {
        // 判断是否需要验证
        if (!needsVerification(primaryAnswer)) {
            return new VerifiedResponse(primaryAnswer, false, null);
        }
        
        // Reviewer 从另一个角度审查
        String reviewPrompt = """
            你是AI回答审核员。以下是客服AI给用户的回答，请审查：
            
            == 知识库原始数据 ==
            {knowledge}
            
            == 客服AI的回答 ==
            {answer}
            
            请检查：
            1. 价格、参数是否与知识库一致？
            2. 是否有知识库中没有的信息（幻觉）？
            3. 是否有误导性的表述？
            
            返回JSON格式：
            {
              "has_issues": true/false,
              "issues": ["问题1", "问题2"],
              "corrected_answer": "修正后的回答（如果有问题的话）"
            }
            """;
        
        record ReviewResult(boolean hasIssues, List<String> issues, String correctedAnswer) {}
        
        ReviewResult review = reviewerClient.prompt()
            .system(reviewPrompt)
            .param("knowledge", knowledge)
            .param("answer", primaryAnswer)
            .user(userQuery)
            .call()
            .entity(ReviewResult.class);
        
        if (review.hasIssues() && review.correctedAnswer() != null) {
            return new VerifiedResponse(review.correctedAnswer(), true, review.issues());
        }
        
        return new VerifiedResponse(primaryAnswer, false, null);
    }
    
    private boolean needsVerification(String answer) {
        // 包含具体数字 → 需要验证
        if (answer.matches(".*\\d+(\\.\\d+)?\\s*万.*")) return true;
        // 包含购买信号 → 需要验证
        if (answer.contains("试驾") || answer.contains("到店") || answer.contains("订购")) return true;
        return false;
    }
}
```

### 7.6 双模型验证是否只写在提示词里？

**答案：不完全是。** 提示词是第1层基础防御，但要真正有效需要多层：

| 层级 | 实现方式 | 适用场景 |
|------|---------|---------|
| 提示词约束 | `system()` 方法 | 所有场景，基础规则 |
| 规则校验 | Java 代码 + 正则 | 价格、品牌等可结构化检查的 |
| 双模型 | 另一个 ChatClient 审查 | 关键回答（涉及价格/下单） |

**核心原则：能用代码规则检查的，不要用 AI。AI验证只用于无法用规则检查的语义层面。**

---

## 8. 动态配置与多仓店支持

### 8.1 配置层级设计

```properties
# ========== application.properties ==========

# 公司基础信息（所有门店共享的默认值）
company.name=XX汽车销售有限公司
company.phone=400-888-9999
company.email=support@example.com
company.website=https://www.example.com

# 多门店配置（数据库存储，配置只在 properties 留默认值）
# 门店信息在 store_config 表中
```

### 8.2 门店数据模型

```sql
CREATE TABLE store_config (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    store_name    VARCHAR(100)  NOT NULL,       -- 门店名称
    store_code    VARCHAR(50)   UNIQUE NOT NULL, -- 仓店编码
    address       VARCHAR(255)  NOT NULL,        -- 地址
    latitude      DOUBLE        NOT NULL,        -- 纬度（IP定位用）
    longitude     DOUBLE        NOT NULL,        -- 经度
    phone         VARCHAR(20),                   -- 门店电话
    working_hours VARCHAR(100),                  -- 营业时间
    region        VARCHAR(100),                  -- 覆盖区域（如"朝阳区"）
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE store_car_hot (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    store_id       BIGINT NOT NULL,
    car_brand      VARCHAR(50) NOT NULL,         -- 品牌
    car_model      VARCHAR(100) NOT NULL,         -- 车型
    inquiry_count  INT DEFAULT 0,                -- 咨询量（反映热度）
    sale_count     INT DEFAULT 0,                -- 成交量
    stat_date      DATE NOT NULL,                -- 统计日期
    UNIQUE(store_id, car_brand, car_model, stat_date)
);
```

### 8.3 根据城市定位最近门店

门店查询按 #16 改为"先确认城市 → 返回最近一家"的两轮对话：LLM 不知道用户城市时先反问，拿到城市后调用 `getStoreInfo(city)`，工具只返回一家门店（微信短消息约束）。

`getStoreInfo(String city)` 内部流程：

1. `store_config` 按 `region LIKE %city% AND is_active=1` 命中 → 返回第一家
2. 未命中 → `GeoLocator.locateByCity(city)` 取城市坐标（positionstack 地理编码）→ `StoreLocator.findNearest(lat, lng)` 返回最近一家
3. 仍无 → 返回"未找到该城市的门店，请确认城市名"

城市→坐标走 positionstack forward geocoding（`FixedGeoLocator` 的 IP 定位路径暂不改造，保留固定坐标；positionstack 不提供 IP 定位）：

```java
@Component
public class CarSalesTools {

    private final StoreLocator storeLocator;
    private final GeoLocator geoLocator;   // locateByCity 走 positionstack

    /**
     * 按城市返回最近一家门店（#16：region LIKE 优先 + positionstack 兜底 + 单店）
     */
    public String getStoreInfo(String city) {
        // 1. region LIKE 命中
        StoreInfo regionMatch = storeLocator.findByRegion(city);
        if (regionMatch != null) return formatStore(regionMatch);

        // 2. positionstack 兜底：城市→坐标→最近门店
        GeoLocation loc = geoLocator.locateByCity(city);
        if (loc != null) {
            StoreInfo nearest = storeLocator.findNearest(loc.lat(), loc.lng());
            if (nearest != null) return formatStore(nearest);
        }

        // 3. 仍无
        return "未找到该城市的门店，请确认城市名";
    }
}

// locateByCity 实现（PositionStackGeoLocator）：
// GET https://api.positionstack.com/v1/forward?access_key=${POSITIONSTACK_API_KEY}&query=<city>&limit=1
// → data[0].latitude / data[0].longitude；未配 key 或失败返回 null
```

**配置**：`positionstack.api-key=${POSITIONSTACK_API_KEY}`，真实 key 走环境变量注入，不进仓库（仓库 public，与 `${DASHSCOPE_API_KEY}` 同约定）。未配置 key 或调用失败时 `locateByCity` 返回 null，工具降级为"未找到该城市的门店"。
### 8.4 动态 System Prompt 注入

```java
@Component
public class DynamicPromptBuilder {
    
    private final CompanyInfoProperties company;    // 公司基础信息
    private final StoreLocator storeLocator;         // 门店定位
    private final KnowledgeRetriever retriever;      // 知识检索
    
    /**
     * 根据用户上下文动态构建 System Prompt
     */
    public String build(ChatContext ctx) {
        StringBuilder prompt = new StringBuilder();
        
        // 1. 角色设定
        prompt.append("你是").append(company.getName()).append("的智能销售顾问。\n\n");
        
        // 2. 门店信息（根据城市定位）
        StoreInfo nearestStore = storeLocator.findNearest(
            ctx.userLat(), ctx.userLng()
        );
        prompt.append("## 客户所在区域最近门店\n");
        prompt.append("- 门店：").append(nearestStore.storeName()).append("\n");
        prompt.append("- 地址：").append(nearestStore.address()).append("\n");
        prompt.append("- 电话：").append(nearestStore.phone()).append("\n");
        prompt.append("- 营业时间：").append(nearestStore.workingHours()).append("\n\n");
        
        // 3. 该门店的热销车（解决模糊语义问题）
        List<HotCar> hotCars = getStoreHotCars(nearestStore.id());
        prompt.append("## 本门店近期热销车型\n");
        for (int i = 0; i < hotCars.size(); i++) {
            HotCar hc = hotCars.get(i);
            prompt.append((i+1)).append(". ").append(hc.brand()).append(" ")
                  .append(hc.model()).append("（本月咨询").append(hc.inquiryCount())
                  .append("次，成交").append(hc.saleCount()).append("台）\n");
        }
        prompt.append("\n");
        
        // 4. 知识库检索结果
        String knowledge = retriever.search(ctx.userQuery(), nearestStore);
        prompt.append("## 相关知识库信息\n").append(knowledge).append("\n\n");
        
        // 5. 防幻觉规则
        prompt.append(buildAntiHallucinationRules());
        
        return prompt.toString();
    }
}
```

---

## 9. RPA 企业微信消息发送方案

### 9.1 背景：企微 API 的限制

企业微信原本提供「发送应用消息」「获取客户列表」等 API 可以实现程序化触达外部联系人，但从 2024 年起微信生态逐步收紧：

```
❌ 被封堵：服务端 API 直接给外部联系人发消息（需人工确认环节）
✅ 可用的：企微内部群消息、应用消息（非营销场景）
⚠️ 灰色地带：企微客户群的"群发助手"（有人工确认）
```

因此，**智能客服 → 微信消息触达** 这条链路的最后一步无法通过 API 完成，必须用 RPA 模拟人工操作企微客户端来发送。

### 9.2 RPA 方案对比

| 方案 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| **A. 企微 Web 版自动化** | Playwright/Selenium 操控网页版企业微信 | 跨平台、好调试、API成熟 | 企微 Web 版功能受限 |
| **B. 企微 Windows 客户端 + UI 自动化** | pywinauto / pyautogui 操控桌面客户端 | 功能完整，所有消息类型都支持 | 需 Windows 环境，版本升级可能失效 |
| **C. 微信 Windows 客户端直控** | 跳过企微，直接操控微信 PC 客户端 | 减少一层跳转 | 封号风险更高 |
| **D. ADB 控制安卓模拟器** | 在模拟器里跑企微/微信，ADB 模拟点击 | 隔离性好 | 延迟高，维护成本高 |

**推荐方案 B：企微 Windows 客户端 + 桌面 UI 自动化**，理由：你的系统本来就是 Windows 桌面场景（参考 Python Customer-Agent 的 PyQt6 架构），企微客户端功能完整，且比微信管控松。

### 9.3 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│  Spring AI 智能客服（Java — 服务器）                           │
│                                                               │
│  当 AI 判断需要主动给客户发消息时：                               │
│  1. 调用 sendWechatMessage 工具                                │
│  2. 写入 pending_messages 表（状态=pending）                     │
│  3. RPA Agent 轮询到任务 → 执行发送 → 回写状态=done               │
└──────────────────────┬──────────────────────────────────────┘
                       │ 数据库共享(同一网络)
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  RPA Agent（Python，独立进程 — 销售办公电脑）                    │
│                                                               │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────────┐   │
│  │ Task Poller  │───▶│  Task Queue   │───▶│  WeCom RPA     │   │
│  │ (每2秒轮询DB)│    │ (内存优先级队列)│    │  (操控企微客户端) │   │
│  └─────────────┘    └──────────────┘    └────────────────┘   │
│                                                    │          │
│  ┌─────────────┐                                  │          │
│  │ Health Check│◀─────────────────────────────────┘          │
│  │ (心跳+告警) │                                              │
│  └─────────────┘                                              │
└──────────────────────┬──────────────────────────────────────┘
                       │ 操控（pyautogui / pywinauto）
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  企业微信 Windows 客户端                                        │
│  ┌─────────────────────────────────────────────┐             │
│  │ 搜索框: "张先生"  →  找到联系人              │             │
│  │ 输入框: "您好！您之前咨询的比亚迪宋PLUS..."    │             │
│  │ [发送按钮]  →  click                         │             │
│  └─────────────────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────┘
```

### 9.4 RPA 核心实现（Python — 基于图像识别方案）

选用 **pyautogui + 图像识别** 而非 pywinauto 控件识别，原因是：
- 企微客户端用 Electron/CEF 渲染，Win32 控件树不稳定
- 图像识别跨版本更稳定，只需偶尔更新模板截图
- 不依赖控件层级结构

```python
"""
企微 RPA — 基于图像识别的自动化发送
依赖: pip install pyautogui pyperclip opencv-python
"""
import pyautogui
import pyperclip
import time
import random
from pathlib import Path

# 全局安全开关：鼠标移到屏幕左上角立即终止
pyautogui.FAILSAFE = True

class WeComRPA:
    """企微 Windows 客户端 RPA 控制器"""
    
    def __init__(self):
        self.template_dir = Path(__file__).parent / "templates"
        # 预先截好的定位模板图片（放在 templates/ 目录下）：
        #   search_icon.png     — 企微搜索图标
        #   chat_input.png      — 聊天输入框区域
        #   contact_first.png   — 搜索结果第一个
        #   send_btn.png        — 发送按钮（备选，通常用 Enter）
    
    # ==================== 基础操作 ====================
    
    def _find_and_click(self, template_name: str, confidence=0.85, timeout=5):
        """在屏幕上找模板图片并点击中心"""
        template_path = self.template_dir / template_name
        start = time.time()
        
        while time.time() - start < timeout:
            try:
                pos = pyautogui.locateCenterOnScreen(
                    str(template_path), confidence=confidence
                )
                if pos:
                    self._human_click(pos.x, pos.y)
                    return True
            except Exception:
                pass
            time.sleep(0.5)
        return False
    
    def _human_click(self, x: int, y: int):
        """带随机偏移的点击，模拟真人"""
        offset_x = random.randint(-8, 8)
        offset_y = random.randint(-5, 5)
        pyautogui.moveTo(x + offset_x, y + offset_y, 
                         duration=random.uniform(0.15, 0.4))
        time.sleep(random.uniform(0.08, 0.2))
        pyautogui.click()
    
    def _human_delay(self, min_sec=0.3, max_sec=1.5):
        """随机延迟，模拟人手速"""
        time.sleep(random.uniform(min_sec, max_sec))
    
    # ==================== 核心流程 ====================
    
    def send_message(self, contact_name: str, message: str) -> bool:
        """
        完整发送流程：
        1. Ctrl+F 打开搜索 → 输入联系人名字 → 回车
        2. 点击聊天输入框 → 粘贴消息 → 回车发送
        """
        try:
            # Step 1: 打开搜索（Ctrl+F）
            pyautogui.hotkey('ctrl', 'f')
            self._human_delay(0.3, 0.6)
            
            # Step 2: 输入联系人名字
            pyperclip.copy(contact_name)
            pyautogui.hotkey('ctrl', 'v')
            self._human_delay(0.6, 1.0)  # 等待搜索结果
            
            # Step 3: 回车选中第一个搜索结果
            pyautogui.press('enter')
            self._human_delay(0.5, 0.8)
            
            # Step 4: 点击输入区域（用图像定位或直接 Tab）
            if not self._find_and_click("chat_input.png", confidence=0.8, timeout=3):
                # 降级：尝试用 Tab 键切换到输入框
                pyautogui.press('tab')
                self._human_delay(0.2, 0.4)
            
            # Step 5: 粘贴消息（剪贴板方式，避免中文输入法问题）
            pyperclip.copy(message)
            pyautogui.hotkey('ctrl', 'v')
            self._human_delay(0.2, 0.5)
            
            # Step 6: 发送
            pyautogui.press('enter')
            self._human_delay(0.2, 0.4)
            
            return True
            
        except Exception as e:
            # 鼠标移到左上角会触发 FAILSAFE → pyautogui.FailSafeException
            raise RPAException(f"发送失败: {contact_name} — {e}")
    
    # ==================== 拟人发送（长消息专用） ====================
    
    def send_message_humanlike(self, contact_name: str, message: str) -> bool:
        """
        逐字输入版本（更安全但更慢）
        适用于敏感场景或长消息，模拟真人打字节奏
        """
        self.search_contact(contact_name)
        
        # 点击输入框
        if not self._find_and_click("chat_input.png", confidence=0.8, timeout=3):
            pyautogui.press('tab')
        
        # 短消息(<20字)：直接粘贴（真人也会复制粘贴）
        if len(message) < 20:
            pyperclip.copy(message)
            pyautogui.hotkey('ctrl', 'v')
        else:
            # 长消息：逐字输入，随机速度 50-200ms/字
            for char in message:
                pyautogui.typewrite(char, interval=random.uniform(0.05, 0.2))
                # 偶尔停顿（模拟思考）
                if random.random() < 0.05:
                    time.sleep(random.uniform(0.5, 1.5))
        
        pyautogui.press('enter')
        return True
    
    def search_contact(self, name: str):
        """仅搜索并打开联系人对话框"""
        pyautogui.hotkey('ctrl', 'f')
        self._human_delay(0.3, 0.6)
        pyperclip.copy(name)
        pyautogui.hotkey('ctrl', 'v')
        self._human_delay(0.8, 1.2)
        pyautogui.press('enter')
        self._human_delay(0.5, 0.8)


class RPAException(Exception):
    pass
```

### 9.5 RPA Agent 任务调度器

```python
"""
RPA Agent 主进程 — 常驻运行，轮询数据库任务并执行
"""
import sqlite3
import time
import logging
from datetime import datetime
from enum import Enum

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logger = logging.getLogger("RPA-Agent")

class TaskStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    DONE = "done"
    FAILED = "failed"

class RateLimiter:
    """
    频率控制器 ← 防止触发企微风控
    
    关键参数：
    - max_per_minute=5: 每分钟最多5条（模拟人手速上限）
    - cooldown_after=3: 连续发3条后强制休息
    - cooldown_seconds=15: 休息15秒
    - work_hours_only: 只在工作时间发送（9:00-12:00, 14:00-20:00）
    """
    
    def __init__(self, max_per_minute=5, cooldown_after=3, cooldown_seconds=15):
        self.max_per_minute = max_per_minute
        self.cooldown_after = cooldown_after
        self.cooldown_seconds = cooldown_seconds
        self.window = []      # 过去60秒的发送时间戳
        self.consecutive = 0  # 连续发送计数
    
    def wait_if_needed(self):
        now = time.time()
        
        # 清理过期时间戳
        self.window = [t for t in self.window if now - t < 60]
        
        # 频率限制
        if len(self.window) >= self.max_per_minute:
            wait = 60 - (now - self.window[0]) + random.randint(2, 5)
            logger.info(f"⏳ 频率限制，等待 {wait:.0f} 秒...")
            time.sleep(wait)
        
        # 连续发送冷却
        self.consecutive += 1
        if self.consecutive >= self.cooldown_after:
            cooldown = self.cooldown_seconds + random.randint(-3, 5)
            logger.info(f"😴 连续发送 {self.consecutive} 条，休息 {cooldown} 秒...")
            time.sleep(cooldown)
            self.consecutive = 0
    
    def tick(self):
        self.window.append(time.time())
    
    @staticmethod
    def is_work_hours() -> bool:
        """检查当前是否在工作时间"""
        hour = datetime.now().hour
        return (9 <= hour < 12) or (14 <= hour < 20)


class RPAAgent:
    """RPA 任务调度器"""
    
    def __init__(self, db_path: str):
        self.db_path = db_path
        self.rpa = WeComRPA()
        self.rate_limiter = RateLimiter()
        self.running = True
    
    def run(self):
        """主循环"""
        logger.info("🚀 RPA Agent 启动，开始轮询任务...")
        
        while self.running:
            try:
                # 非工作时间休眠
                if not RateLimiter.is_work_hours():
                    logger.debug("非工作时间，休眠 300 秒...")
                    time.sleep(300)
                    continue
                
                # 1. 取待发送任务
                tasks = self._fetch_pending_tasks(limit=10)
                
                if not tasks:
                    time.sleep(2)
                    continue
                
                logger.info(f"📋 获取到 {len(tasks)} 个待发送任务")
                
                # 2. 逐条处理
                for task in tasks:
                    if not self.running:
                        break
                    
                    self.rate_limiter.wait_if_needed()
                    self._update_status(task['id'], TaskStatus.PROCESSING)
                    
                    try:
                        success = self.rpa.send_message(
                            task['contact_name'],
                            task['message']
                        )
                        if success:
                            self._update_status(task['id'], TaskStatus.DONE)
                            logger.info(f"✅ 发送成功: {task['contact_name']}")
                        else:
                            raise RPAException("send_message 返回 False")
                            
                    except Exception as e:
                        retry_count = task.get('retry_count', 0) + 1
                        if retry_count >= 3:
                            self._update_status(task['id'], TaskStatus.FAILED)
                            logger.error(f"❌ 发送失败(已重试3次): {task['contact_name']} — {e}")
                        else:
                            # 重置为 pending，等待下次重试
                            self._reset_for_retry(task['id'], retry_count)
                            logger.warning(f"⚠️ 发送失败，将重试({retry_count}/3): {task['contact_name']} — {e}")
                    
                    self.rate_limiter.tick()
                    
            except Exception as e:
                logger.error(f"RPA Agent 异常: {e}")
                time.sleep(5)
    
    def _fetch_pending_tasks(self, limit=10):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        cur = conn.execute("""
            SELECT * FROM pending_messages 
            WHERE status = 'pending' 
            ORDER BY priority ASC, created_at ASC 
            LIMIT ?
        """, (limit,))
        rows = [dict(r) for r in cur.fetchall()]
        conn.close()
        return rows
    
    def _update_status(self, task_id: int, status: TaskStatus):
        conn = sqlite3.connect(self.db_path)
        conn.execute(
            """UPDATE pending_messages 
               SET status = ?, updated_at = datetime('now') 
               WHERE id = ?""",
            (status.value, task_id)
        )
        conn.commit()
        conn.close()
    
    def _reset_for_retry(self, task_id: int, retry_count: int):
        conn = sqlite3.connect(self.db_path)
        conn.execute(
            """UPDATE pending_messages 
               SET status = 'pending', retry_count = ?, updated_at = datetime('now') 
               WHERE id = ?""",
            (retry_count, task_id)
        )
        conn.commit()
        conn.close()


if __name__ == "__main__":
    import random
    agent = RPAAgent(db_path="D:/idea/project/AI/pending_messages.db")
    agent.run()
```

### 9.6 防封策略

企微/微信有反自动化检测，以下策略分层防护：

| 层级 | 策略 | 实现方式 |
|------|------|----------|
| **频率层** | 发送间隔随机化 | 3-7条/分钟随机，间隔8-30秒随机 |
| **行为层** | 打字速度模拟 | 长消息逐字输入（50-200ms/字），短消息粘贴 |
| **时间层** | 工作时间限制 | 只在 9:00-12:00, 14:00-20:00 发送 |
| **波动层** | 消息量自然波动 | 周一多、周末少，不每天恒定 |
| **动作层** | 光标随机偏移 | 点击 ±8px 随机偏移，鼠标移动速度随机 |
| **会话层** | 不连续刷同一人 | 插入搜索/浏览操作，模拟多任务切换 |
| **版本层** | 企微版本固定 | 关闭企微自动更新，防止控件变化 |

```python
# 防封辅助函数
def human_like_typing(message: str):
    """逐字输入（模拟打字），用于敏感消息"""
    for char in message:
        pyautogui.typewrite(char, interval=random.uniform(0.05, 0.2))
        # 2%概率停顿（模拟思考或被打断）
        if random.random() < 0.02:
            time.sleep(random.uniform(1.0, 3.0))

def random_browse_action():
    """随机执行一个浏览动作（模拟真实使用）"""
    actions = [
        lambda: pyautogui.scroll(random.randint(-5, 5)),  # 滚轮
        lambda: pyautogui.press('down'),                    # 按下键
        lambda: time.sleep(random.uniform(1, 3)),           # 纯粹停顿
    ]
    random.choice(actions)()
```

### 9.7 Spring AI 侧集成（Java）

在 Java 侧只需要写入任务表，定义一个 Tool 让 AI 可以调用：

```java
/**
 * pending_messages 表结构（SQLite / MySQL 通用）
 */
/*
CREATE TABLE pending_messages (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    contact_name      TEXT NOT NULL,          -- 客户微信昵称/备注
    contact_wechat_id TEXT NOT NULL,          -- 企微外部联系人ID
    message           TEXT NOT NULL,          -- 待发送的消息内容
    priority          INTEGER DEFAULT 2,      -- 1=紧急 2=普通 3=低
    status            TEXT DEFAULT 'pending', -- pending/processing/done/failed
    retry_count       INTEGER DEFAULT 0,
    created_at        TEXT DEFAULT (datetime('now')),
    updated_at        TEXT DEFAULT (datetime('now'))
);
*/

@Component
public class RpaMessageService {
    
    private final JdbcTemplate jdbc;
    
    /**
     * 创建 RPA 发送任务
     */
    public void enqueueMessage(String contactName, String wechatId, 
                                String message, int priority) {
        jdbc.update(
            "INSERT INTO pending_messages (contact_name, contact_wechat_id, "
            + "message, priority, status) VALUES (?, ?, ?, ?, 'pending')",
            contactName, wechatId, message, priority
        );
    }
}

// ===== Tool 定义：让 Agent 可以调用 =====
@Component
public class WechatMessageTool {
    
    private final RpaMessageService rpaService;
    
    @Tool(description = """
        将消息加入RPA发送队列，自动通过企业微信发送给客户。
        参数说明：
        - contactName: 客户的微信昵称或备注名
        - wechatId: 企微外部联系人ID（从上下文获取）
        - message: 要发送的消息内容
        - priority: 1=紧急(到店确认/试驾预约) 2=普通(购车咨询) 3=低(回访关怀)
        """)
    public String sendWechatMessage(
            @ToolParam(description = "客户昵称") String contactName,
            @ToolParam(description = "企微外部联系人ID") String wechatId,
            @ToolParam(description = "消息内容") String message,
            @ToolParam(description = "优先级1-3") int priority) {
        
        rpaService.enqueueMessage(contactName, wechatId, message, priority);
        return "✅ 消息已加入发送队列，稍后将通过企业微信自动发送给「" 
               + contactName + "」。";
    }
}
```

### 9.8 部署架构

```
┌──────────────────────────────────────────────────────────┐
│  服务器（Linux / 云服务器）                                 │
│  · Spring AI 智能客服（Java）                               │
│  · MySQL/PostgreSQL（含 pending_messages 表）              │
│  · PGVector 知识库                                        │
└──────────────────┬───────────────────────────────────────┘
                   │ 数据库连接（内网/公网）
                   ▼
┌──────────────────────────────────────────────────────────┐
│  销售办公电脑（Windows 10/11）  ← RPA 运行环境              │
│  · 企业微信客户端（保持登录、不关机）                        │
│  · RPA Agent（Python 常驻进程，systemd / nssm 守护）       │
│  · 屏幕保持亮屏（电源选项：从不睡眠）                        │
│                                                          │
│  ⚠️ 这台电脑就是"机器人"，需要：                            │
│  - 工作时间开机，屏幕常亮不锁屏                              │
│  - 专人每天检查企微是否掉线                                 │
│  - 不要在这台电脑上做其他操作（避免干扰RPA）                 │
│  - 企微客户端关闭自动更新                                   │
└──────────────────────────────────────────────────────────┘
```

### 9.9 异常处理与监控

| 异常场景 | 处理方式 |
|----------|----------|
| **企微掉线/被踢** | 健康检查每30秒截屏检测企微窗口是否存在，消失则钉钉/企微告警 |
| **搜索不到联系人** | 3次重试后标记 failed，通知人工处理 |
| **发送失败** | 最多重试3次，间隔递增(30s/60s/120s)，仍失败则标记 failed |
| **窗口被遮挡** | 发送前将企微窗口置顶（`pyautogui.getWindowsWithTitle`） |
| **任务积压** | pending > 20 条时告警，可能频率限制导致 |
| **RPA 进程崩溃** | 用 nssm (Windows) 或 systemd (Linux) 守护，崩溃自动重启 |

---

## 10. Agent 框架骨架 —— Dify DSL → Spring AI 完整实现

> 本章将 `customer-agent.yml` 中的 Dify Chatflow **完整逆向**为 Spring AI Java 代码骨架。
> 读完本章你将理解：Dify 画布上的每个节点和连线，在 Spring AI 中对应什么代码。

### 10.1 Dify 流程完整解析

从 DSL 的 `graph.edges` + `graph.nodes` 逆向出的完整流程：

```
                                    ┌─────────────────────────────┐
                                    │   Start（开始）               │
                                    └──────────────┬──────────────┘
                                                   │
                                    ┌──────────────▼──────────────┐
                                    │  MCP Tool: search_inventory │  ← 查车辆库存
                                    └──────────────┬──────────────┘
                                                   │
                                    ┌──────────────▼──────────────┐
                                    │  Knowledge Retrieval        │  ← 向量+关键词混合检索
                                    │  (dataset: 汽车知识库)        │     rerank top_k=4
                                    └──────────────┬──────────────┘
                                                   │
                                    ┌──────────────▼──────────────┐
                                    │  Question Classifier        │  ← moonshot-v1-8k
                                    │  ┌───────────────────────┐  │     5路分类
                                    │  │ 1. 具体车型咨询         │  │
                                    │  │ 2. 买车意向/推荐        ├──┤
                                    │  │ 3. 情绪化/模糊问题      │  │
                                    │  │ 4. 打招呼              │  │
                                    │  │ 5. 不当言论            │  │
                                    │  └───────────────────────┘  │
                                    └──────┬──────────┬────────────┘
                                           │          │          │
              ┌────────────────────────────┘          │          └──────────────────┐
              ▼                                       ▼                             │
   ┌──────────────────────┐             ┌──────────────────────┐                    │
   │ 分支 1: 具体车型咨询   │             │ 分支 2: 买车意向/推荐   │    分支3/4/5...    │
   │                      │             │                      │                    │
   │ If-Else: stock空?    │             │ Knowledge Retrieval 4 │                    │
   │  ├true→"售罄"        │             │  (购车引导话术)        │                    │
   │  └false→             │             │         ↓             │                    │
   │   LLM2: 生成图片提示词│             │ LLM4: 车辆推荐        │                    │
   │   Stability: 生成图片 │             │         ↓             │                    │
   │   LLM: 综合回复       │             │ LLM3: 客服引导+话术   │                    │
   │         ↓             │             │         ↓             │                    │
   │   Classifier2: 分新老 │             │ → 回复                │                    │
   │   → 回复 (带/不带图片) │             └──────────────────────┘                    │
   └──────────────────────┘                                                          │
```

### 10.2 Dify 节点 → Spring AI 组件映射表

| Dify 节点 (ID) | 节点类型 | Spring AI 等价 | 包路径 |
|:--|:--|:--|:--|
| `1784515086112` | Start | Controller 入口 | `controller/ChatController.java` |
| `1784531554232` | MCP Tool | `@Tool` 方法 | `tool/SearchInventoryTool.java` |
| `1784515682023` | 知识检索 | `VectorStore.similaritySearch()` | `advisor/KnowledgeRetrievalAdvisor.java` |
| `1784515817637` | 问题分类器 | LLM 分类 + Router | `advisor/IntentClassifierAdvisor.java` + `router/IntentRouter.java` |
| `1784533933929` | If-Else | Java `if` 语句 | `branch/SpecificCarHandler.java` |
| `1784534272489` | 直接回复 7 | 方法返回字符串 | 同上 |
| `1784516317265` | LLM 2 | `ChatClient.prompt().call()` | `branch/SpecificCarHandler.java` |
| `1784516286955` | Stability工具 | `@Tool` 方法 | `tool/ImageGenerationTool.java` |
| `llm` | LLM (主回复) | `ChatClient.prompt().call()` | `branch/SpecificCarHandler.java` |
| `1784516904489` | 问题分类器 2 | Java `if` 语句 | `branch/SpecificCarHandler.java` |
| `1784519498352` | 知识检索 4 | `VectorStore.similaritySearch()` | `branch/CarRecommendHandler.java` |
| `1784517243420` | LLM 4 | `ChatClient.prompt().call()` | `branch/CarRecommendHandler.java` |
| `1784517018675` | LLM 3 | `ChatClient.prompt().call()` | `branch/CarRecommendHandler.java` |
| `1784517520625` | 知识检索 3 | `VectorStore.similaritySearch()` | `branch/EmotionalHandler.java` |
| `1784517538865` | LLM 5 | `ChatClient.prompt().call()` | `branch/EmotionalHandler.java` |
| `1784517760445` | 直接回复 5 | 方法返回字符串 | `branch/GreetingHandler.java` |
| `1784517836238` | 直接回复 6 | 方法返回字符串 | `branch/UnsafeContentHandler.java` |

### 10.3 包结构（完整骨架）

```
src/main/java/org/example/ai/
├── AiApplication.java
│
├── agent/
│   ├── AgentOrchestrator.java          # 🔑 主编排器（整个 Dify Graph 的入口）
│   │
│   ├── advisor/                        # Spring AI Advisor 链（对应 Dify 节点链）
│   │   ├── McpToolAdvisor.java         # 起始 → 调用 search_inventory
│   │   ├── KnowledgeRetrievalAdvisor.java  # 知识库检索 + 混合排序
│   │   └── IntentClassifierAdvisor.java    # 问题分类器（5路LLM分类）
│   │
│   ├── router/                         # 意图路由（对应 Dify Question Classifier 出口）
│   │   ├── IntentType.java             # 5种意图枚举
│   │   └── IntentRouter.java           # 意图 → BranchHandler 映射
│   │
│   ├── branch/                         # 分支处理器（对应 Dify 的5条分支）
│   │   ├── BranchHandler.java          # 分支处理器接口
│   │   ├── SpecificCarHandler.java     # 分支1: 具体车型咨询 (最复杂)
│   │   ├── CarRecommendHandler.java    # 分支2: 买车意向推荐
│   │   ├── EmotionalHandler.java       # 分支3: 情绪化/模糊问题
│   │   ├── GreetingHandler.java        # 分支4: 打招呼
│   │   └── UnsafeContentHandler.java   # 分支5: 不当言论
│   │
│   ├── tool/                           # 工具定义（对应 Dify MCP Tool + Stability）
│   │   ├── SearchInventoryTool.java    # search_inventory MCP 工具
│   │   ├── ImageGenerationTool.java    # Stability Diffusion 图片生成
│   │   └── WechatMessageTool.java      # RPA 企微消息工具（第9章）
│   │
│   └── prompt/                         # 集中管理所有 System Prompt
│       └── PromptTemplates.java        # 每个 LLM 节点的提示词模板
│
├── model/                              # 数据模型
│   ├── CarInventory.java
│   ├── StoreInfo.java
│   └── ChatContext.java
│
├── controller/
│   └── ChatController.java             # REST API 入口
│
└── config/
    ├── AiConfig.java                   # ChatClient Bean 配置
    └── CompanyInfoProperties.java
```

### 10.4 核心骨架代码

#### 10.4.1 意图枚举（对应问题分类器的 5 个出口）

```java
package org.example.ai.agent.router;

/**
 * 对应 Dify Question Classifier (1784515817637) 的 5 个分类
 */
public enum IntentType {

    /** 分支1 & 分支2出口: 用户询问具体品牌/车型 */
    SPECIFIC_CAR,

    /** 分支3出口: 用户有买车意图，但没提具体车型，想寻求推荐 */
    CAR_RECOMMEND,

    /** 分支4出口: 用户语气化、情绪化、模糊不清的问题 */
    EMOTIONAL_VAGUE,

    /** 分支5出口: 用户打招呼 */
    GREETING,

    /** 分支6出口: 不当言论 / 超出范围 */
    UNSAFE_CONTENT
}
```

#### 10.4.2 分支处理器接口

```java
package org.example.ai.agent.branch;

import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;

/**
 * 分支处理器接口
 * Dify 中的每个 answer 节点 = 一个 BranchHandler 的实现
 */
public interface BranchHandler {

    /** 此处理器处理的意图类型 */
    IntentType supportedIntent();

    /**
     * 处理用户消息并返回回复
     * @param ctx 聊天上下文（含用户消息、知识库结果、MCP结果、门店信息等）
     */
    String handle(ChatContext ctx);
}
```

#### 10.4.3 聊天上下文（贯穿整个链的数据载体）

```java
package org.example.ai.model;

import org.example.ai.agent.router.IntentType;
import java.util.List;
import java.util.Map;

/**
 * 贯穿整个 Agent 链的上下文对象
 * 对应 Dify 中 {{#sys.query#}} {{#context#}} {{#1784531554232.text#}} 等变量
 */
public class ChatContext {

    // ===== 输入 =====
    private String userQuery;               // {{#sys.query#}} 用户原始输入
    private String userId;                  // 用户标识
    private String userIp;                  // 用户IP（用于定位门店）

    // ===== MCP 工具结果 =====
    private String inventoryResult;         // {{#1784531554232.text#}} search_inventory 返回
    private boolean stockEmpty;             // 库存是否为空（对应 If-Else 条件）

    // ===== 知识检索结果 =====
    private String carKnowledge;            // {{#1784515682023.result#}} 汽车知识库检索
    private String salesScript;             // {{#1784519498352.result#}} 销售话术检索
    private String emotionalScript;         // {{#1784517520625.result#}} 情绪处理话术检索

    // ===== 分类结果 =====
    private IntentType intent;              // 问题分类器结果

    // ===== 门店信息 =====
    private StoreInfo nearestStore;         // 最近门店

    // ===== 对话历史 =====
    private List<Map<String, String>> conversationHistory;

    // ===== 工具调用中间结果 =====
    private String generatedImageUrl;       // {{#1784516286955.files#}} 生成的图片URL

    // getters & setters 省略...
}
```

#### 10.4.4 提示词模板（集中管理对应所有 LLM 节点的 system prompt）

```java
package org.example.ai.agent.prompt;

/**
 * 所有 System Prompt 集中管理
 * 对应 Dify 中每个 LLM 节点的 prompt_template.text
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    // ===== 问题分类器 (1784515817637) — moonshot-v1-8k =====
    public static final String INTENT_CLASSIFIER = """
        你是一个客服意图分类器。分析用户输入，判断属于以下哪一类：

        1. SPECIFIC_CAR — 用户询问具体品牌/车型（如"比亚迪宋怎么样""特斯拉Model 3多少钱"）
        2. CAR_RECOMMEND — 用户想买车但没指定车型，寻求推荐（如"20万推荐什么车""我想买SUV"）
        3. EMOTIONAL_VAGUE — 用户表达情绪/模糊/犹豫（如"太贵了""再看看""感觉一般"）
        4. GREETING — 用户打招呼（如"你好""在吗"）
        5. UNSAFE_CONTENT — 不当言论或超出汽车销售范围的问题

        只输出分类名称（如 SPECIFIC_CAR），不要输出任何其他内容。
        """;

    // ===== LLM2 (1784516317265) — 图片提示词生成 =====
    public static final String IMAGE_PROMPT_GENERATOR = """
        You are a car model illustration expert. Based on the knowledge base
        retrieval result and user query, generate detailed English painting
        prompts for different angles of the car model.

        Output JSON:
        {
          "exterior": "Detailed exterior prompt including body shape, color, wheels...",
          "interior": "Detailed interior prompt including dashboard layout, seats...",
          "cockpit": "Driver's perspective prompt showing steering wheel...",
          "rear": "Rear angle prompt showing taillights, trunk, exhaust..."
        }

        Rules:
        - Be specific about dimensions, materials, design language
        - Include brand-specific design elements if known
        - Style: photorealistic automotive photography, studio lighting, 8K
        - Each prompt must describe ONE clear angle/viewpoint
        """;

    // ===== LLM (主回复) — 具体车型介绍 =====
    public static final String SPECIFIC_CAR_RESPONSE = """
        你现在是一个资深汽车销售员，你熟知以下汽车信息：
        {car_knowledge}

        库存信息：
        {inventory_result}

        {image_section}

        规则：
        - 直接以汽车销售的口吻回复客户，不要说"好的我理解您的需求"等开场白
        - 只输出给客户的回复内容，不要输出思考过程
        - 回复要自然、口语化，像真人聊天一样
        - 结合库存信息给出报价和现车情况
        - 如果库存为0，诚实告知并建议等待或替代车型
        """;

    // ===== LLM4 (1784517243420) — 车辆推荐 =====
    public static final String CAR_RECOMMENDATION = """
        你是一个资深汽车销售员，你熟知汽车的相关知识：
        {car_knowledge}

        用户想买车但还没确定具体车型：{user_query}

        规则：
        - 先了解用户预算、用途、偏好（油车/电车）
        - 根据回复推荐不超过3款车，每款一句话亮点
        - 结合库存{inventory_result}推荐，不能推荐已售罄的车型
        - 直接以汽车销售的口吻回复，自然口语化
        """;

    // ===== LLM3 (1784517018675) — 客服引导成交 =====
    public static final String SALES_GUIDANCE = """
        你是一个资深客服，熟悉聊天话术。
        销售话术参考：
        {sales_script}

        上一步推荐结果：
        {recommendation_result}

        规则：
        - 基于推荐结果，一步步引导客户到线下店提车
        - 示例对话只是话术参考，不是顾客真实信息
        - 绝不假设顾客身份——先确认预算、用途、偏好
        - 遇到客户犹豫时用话术库的技巧挽留
        - 直接以汽车销售的口吻回复，自然口语化
        """;

    // ===== LLM5 (1784517538865) — 情绪处理 =====
    public static final String EMOTIONAL_HANDLING = """
        你是一个资深的处理客户情绪的客服。
        情绪处理话术参考：
        {emotional_script}

        用户表达了情绪化或模糊的言语：{user_query}

        规则：
        - 先稳住客户情绪，表达理解和共情
        - 不假设顾客身份——通过提问确认预算、用途、偏好
        - 用话术库的技巧一步步拉回对话正轨
        - 引导客户关注我们公司的车辆优势
        - 直接以汽车销售的口吻回复，自然口语化
        """;
}
```

#### 10.4.5 意图分类器 Advisor（核心路由逻辑）

```java
package org.example.ai.agent.advisor;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 意图分类器
 * 对应 Dify 问题分类器节点 (1784515817637)
 * 用 moonshot-v1-8k 做分类，输出5个意图之一
 */
@Component
public class IntentClassifierAdvisor {

    private final ChatClient classifierClient;

    public IntentClassifierAdvisor(ChatClient.Builder builder) {
        // 分类用低成本模型（对应 Dify 用的 moonshot-v1-8k）
        // 实际上用 deepseek-chat 也可以，成本差不多
        this.classifierClient = builder.build();
    }

    /**
     * 分类用户意图
     */
    public IntentType classify(String userQuery) {
        String result = classifierClient.prompt()
            .system(PromptTemplates.INTENT_CLASSIFIER)
            .user(userQuery)
            .call()
            .content();

        // 解析分类结果
        String trimmed = result.trim().toUpperCase();
        try {
            return IntentType.valueOf(trimmed);
        } catch (IllegalArgumentException e) {
            // 无法分类时默认走具体车型咨询（最可能的分支）
            return IntentType.SPECIFIC_CAR;
        }
    }
}
```

#### 10.4.6 意图路由器

```java
package org.example.ai.agent.router;

import org.example.ai.agent.branch.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 意图路由器
 * 根据分类结果，找到对应的 BranchHandler
 * 对应 Dify 中 Question Classifier 的 5 条出口边
 */
@Component
public class IntentRouter {

    private final Map<IntentType, BranchHandler> handlerMap;

    /** 注入所有 BranchHandler 实现，自动建立映射 */
    public IntentRouter(List<BranchHandler> handlers) {
        this.handlerMap = handlers.stream()
            .collect(Collectors.toMap(
                BranchHandler::supportedIntent,
                Function.identity()
            ));
    }

    /** 根据意图获取对应处理器 */
    public BranchHandler route(IntentType intent) {
        BranchHandler handler = handlerMap.get(intent);
        if (handler == null) {
            // 兜底：无法分类 → 打招呼
            return handlerMap.get(IntentType.GREETING);
        }
        return handler;
    }
}
```

#### 10.4.7 MCP 工具 Advisor（第一步预处理）

```java
package org.example.ai.agent.advisor;

import org.example.ai.agent.tool.SearchInventoryTool;
import org.example.ai.model.ChatContext;
import org.springframework.stereotype.Component;

/**
 * MCP 工具调用 Advisor
 * 对应 Dify 中 MCP Tool 节点 (1784531554232)
 * 在进入分类器之前，先调用 search_inventory 查库存
 */
@Component
public class McpToolAdvisor {

    private final SearchInventoryTool searchInventoryTool;

    public McpToolAdvisor(SearchInventoryTool searchInventoryTool) {
        this.searchInventoryTool = searchInventoryTool;
    }

    /**
     * 执行预检索
     * 对应 Dify 中调用 search_inventory({"query": "{{#sys.query#}}"})
     */
    public void execute(ChatContext ctx) {
        String result = searchInventoryTool.searchInventory(ctx.getUserQuery());
        ctx.setInventoryResult(result);

        // 对应 Dify 中 If-Else 节点 (1784533933929)
        // 检查是否包含 [STOCK:EMPTY]
        ctx.setStockEmpty(result != null && result.contains("[STOCK:EMPTY]"));
    }
}
```

#### 10.4.8 知识检索 Advisor

```java
package org.example.ai.agent.advisor;

import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识检索 Advisor
 * 对应 Dify 中的知识检索节点 (1784515682023)
 * 混合检索：向量相似度(0.7) + 关键词匹配(0.3)，top_k=4，开启重排序
 */
@Component
public class KnowledgeRetrievalAdvisor {

    private final VectorStore carKnowledgeStore;     // 汽车知识库
    private final VectorStore salesScriptStore;      // 销售话术库
    private final VectorStore emotionalScriptStore;  // 情绪处理话术库

    public KnowledgeRetrievalAdvisor(
            VectorStore carKnowledgeStore,
            VectorStore salesScriptStore,
            VectorStore emotionalScriptStore) {
        this.carKnowledgeStore = carKnowledgeStore;
        this.salesScriptStore = salesScriptStore;
        this.emotionalScriptStore = emotionalScriptStore;
    }

    /**
     * 根据意图类型加载对应的知识库
     * 对应 Dify 中不同分支走不同的知识检索节点
     */
    public void retrieve(ChatContext ctx) {
        IntentType intent = ctx.getIntent();
        String query = ctx.getUserQuery();

        switch (intent) {
            case SPECIFIC_CAR:
                // 对应知识检索 (1784515682023): 汽车知识库
                ctx.setCarKnowledge(search(carKnowledgeStore, query, 4));
                break;

            case CAR_RECOMMEND:
                // 对应知识检索 (1784515682023) + 知识检索4 (1784519498352)
                ctx.setCarKnowledge(search(carKnowledgeStore, query, 4));
                ctx.setSalesScript(search(salesScriptStore, query, 4));
                break;

            case EMOTIONAL_VAGUE:
                // 对应知识检索3 (1784517520625): 情绪处理话术库
                ctx.setEmotionalScript(search(emotionalScriptStore, query, 4));
                break;

            case GREETING:
            case UNSAFE_CONTENT:
                // 打招呼和不当言论不需要知识检索
                break;
        }
    }

    private String search(VectorStore store, String query, int topK) {
        List<Document> docs = store.similaritySearch(
            SearchRequest.query(query).withTopK(topK)
        );
        return docs.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n---\n"));
    }
}
```

#### 10.4.9 分支1：具体车型咨询处理器（最复杂）

```java
package org.example.ai.agent.branch;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.router.IntentType;
import org.example.ai.agent.tool.ImageGenerationTool;
import org.example.ai.model.ChatContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分支1: 用户询问具体品牌/车型
 * 对应 Dify 中最复杂的那条分支链：
 * 
 * 问题分类器(1) → If-Else(库存判断) → LLM2(图片提示词) → Stability(生成图片) → LLM(综合回复) → 分类器2(新老手) → 回复
 */
@Component
public class SpecificCarHandler implements BranchHandler {

    private final ChatClient chatClient;
    private final ImageGenerationTool imageTool;

    public SpecificCarHandler(ChatClient.Builder builder, ImageGenerationTool imageTool) {
        this.chatClient = builder.build();
        this.imageTool = imageTool;
    }

    @Override
    public IntentType supportedIntent() {
        return IntentType.SPECIFIC_CAR;
    }

    @Override
    public String handle(ChatContext ctx) {
        // ========== 对应 Dify If-Else (1784533933929) ==========
        if (ctx.isStockEmpty()) {
            // 对应 直接回复7 (1784534272489)
            return "不好意思，这款车目前售罄了。如果您愿意等待，我们加紧进货，到货后第一时间通知您！";
        }

        // ========== 对应 LLM2 (1784516317265) → 生成图片提示词 ==========
        String imagePromptJson = chatClient.prompt()
            .system(PromptTemplates.IMAGE_PROMPT_GENERATOR)
            .user(ctx.getUserQuery())
            .call()
            .content();

        // ========== 对应 Stability Diffusion (1784516286955) → 生成图片 ==========
        String imageUrl = imageTool.generate(imagePromptJson);
        ctx.setGeneratedImageUrl(imageUrl);

        // ========== 对应 LLM (主回复) ==========
        String imageSection = imageUrl != null
            ? "你可以引用生成的汽车图片帮助介绍：" + imageUrl
            : "";

        String response = chatClient.prompt()
            .system(s -> s
                .text(PromptTemplates.SPECIFIC_CAR_RESPONSE)
                .param("car_knowledge", ctx.getCarKnowledge())
                .param("inventory_result", ctx.getInventoryResult())
                .param("image_section", imageSection))
            .user(ctx.getUserQuery())
            .call()
            .content();

        // ========== 对应 问题分类器2 (1784516904489) + 回复 ==========
        // 1: 用户新手/想看照片 → 回复含图片URL
        // 2: 用户对车了解 → 纯文字回复
        // 简化处理：如果有图片就带上，没有就纯文字
        if (imageUrl != null) {
            return response + "\n\n📷 参考图片：\n![](" + imageUrl + ")";
        }
        return response;
    }
}
```

#### 10.4.10 分支2：买车意向推荐

```java
package org.example.ai.agent.branch;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分支2: 用户想买车但没指定车型
 * 对应 Dify 分支链：
 * 问题分类器(2) → 知识检索4 → LLM4(车辆推荐) → LLM3(客服引导+话术) → 回复
 */
@Component
public class CarRecommendHandler implements BranchHandler {

    private final ChatClient chatClient;

    public CarRecommendHandler(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public IntentType supportedIntent() {
        return IntentType.CAR_RECOMMEND;
    }

    @Override
    public String handle(ChatContext ctx) {
        // ========== 对应 LLM4 (1784517243420) — 车辆推荐 ==========
        String recommendation = chatClient.prompt()
            .system(s -> s
                .text(PromptTemplates.CAR_RECOMMENDATION)
                .param("car_knowledge", ctx.getCarKnowledge())
                .param("user_query", ctx.getUserQuery())
                .param("inventory_result", ctx.getInventoryResult()))
            .user(ctx.getUserQuery())
            .call()
            .content();

        // ========== 对应 LLM3 (1784517018675) — 客服引导 + 话术 ==========
        String finalResponse = chatClient.prompt()
            .system(s -> s
                .text(PromptTemplates.SALES_GUIDANCE)
                .param("sales_script", ctx.getSalesScript())
                .param("recommendation_result", recommendation))
            .user(ctx.getUserQuery())
            .call()
            .content();

        // 对应 直接回复3 (1784517367723)
        return finalResponse;
    }
}
```

#### 10.4.11 分支3：情绪化/模糊问题

```java
package org.example.ai.agent.branch;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分支3: 用户表达情绪/模糊/犹豫
 * 对应 Dify 分支链：
 * 问题分类器(1784515922520) → 知识检索3 → LLM5(情绪处理) → 回复
 */
@Component
public class EmotionalHandler implements BranchHandler {

    private final ChatClient chatClient;

    public EmotionalHandler(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public IntentType supportedIntent() {
        return IntentType.EMOTIONAL_VAGUE;
    }

    @Override
    public String handle(ChatContext ctx) {
        // 对应 LLM5 (1784517538865) + 知识检索3 (1784517520625)
        return chatClient.prompt()
            .system(s -> s
                .text(PromptTemplates.EMOTIONAL_HANDLING)
                .param("emotional_script", ctx.getEmotionalScript())
                .param("user_query", ctx.getUserQuery()))
            .user(ctx.getUserQuery())
            .call()
            .content();
    }
}
```

#### 10.4.12 分支4 & 5：打招呼 / 不当言论

```java
package org.example.ai.agent.branch;

import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.stereotype.Component;

/**
 * 分支4: 用户打招呼
 * 对应 Dify 直接回复5 (1784517760445)
 */
@Component
public class GreetingHandler implements BranchHandler {

    @Override
    public IntentType supportedIntent() {
        return IntentType.GREETING;
    }

    @Override
    public String handle(ChatContext ctx) {
        return "您好，我是卖好车的客服，请问有什么需要帮助的吗？";
    }
}

// ==================== 分支5 ====================

@Component
class UnsafeContentHandler implements BranchHandler {

    @Override
    public IntentType supportedIntent() {
        return IntentType.UNSAFE_CONTENT;
    }

    @Override
    public String handle(ChatContext ctx) {
        // 对应 Dify 直接回复6 (1784517836238)
        return "您的问题我无法回复，抱歉！";
    }
}
```

#### 10.4.13 工具定义

```java
package org.example.ai.agent.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 库存搜索工具
 * 对应 Dify MCP Tool (1784531554232) → search_inventory
 * Spring AI 中通过 @Tool 注解暴露给 LLM
 */
@Component
public class SearchInventoryTool {

    private final JdbcTemplate jdbc;

    public SearchInventoryTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 搜索车辆库存
     * 在 AgentOrchestrator 的预处理阶段被调用，
     * 也可以作为 @Tool 让 LLM 主动调用
     */
    public String searchInventory(String query) {
        // 1. 取出所有品牌和车型名
        var brands = jdbc.queryForList(
            "SELECT DISTINCT brand FROM inventory", String.class);
        var models = jdbc.queryForList(
            "SELECT DISTINCT model FROM inventory", String.class);

        // 2. 关键词匹配（长词优先）
        var allTerms = new java.util.ArrayList<String>();
        allTerms.addAll(brands);
        allTerms.addAll(models);
        allTerms.sort((a, b) -> Integer.compare(b.length(), a.length()));

        var matched = new java.util.ArrayList<String>();
        for (String term : allTerms) {
            if (query.toLowerCase().contains(term.toLowerCase()) && !matched.contains(term)) {
                matched.add(term);
            }
            if (matched.size() >= 3) break;
        }

        // 3. SQL 查询
        java.util.List<java.util.Map<String, Object>> rows;
        if (matched.isEmpty()) {
            rows = jdbc.queryForList(
                "SELECT * FROM inventory WHERE brand LIKE ? OR model LIKE ? OR variant LIKE ? "
                + "ORDER BY stock DESC LIMIT 20",
                "%" + query + "%", "%" + query + "%", "%" + query + "%");
        } else {
            var conditions = new StringBuilder();
            var params = new java.util.ArrayList<String>();
            for (int i = 0; i < matched.size(); i++) {
                if (i > 0) conditions.append(" OR ");
                conditions.append("(brand LIKE ? OR model LIKE ? OR variant LIKE ?)");
                params.add("%" + matched.get(i) + "%");
                params.add("%" + matched.get(i) + "%");
                params.add("%" + matched.get(i) + "%");
            }
            rows = jdbc.queryForList(
                "SELECT * FROM inventory WHERE " + conditions + " ORDER BY stock DESC",
                params.toArray());
        }

        if (rows.isEmpty()) {
            return "[STOCK:EMPTY] 公司库存中没有找到相关车辆。换个说法试试～";
        }

        var sb = new StringBuilder("[STOCK:AVAILABLE] 公司库存匹配结果（" + rows.size() + " 款）：\n");
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            sb.append(String.format("%d. %s %s %s\n   指导价: %s万 | 售价: %s万 | 库存: %s台 | %s\n\n",
                i + 1, r.get("brand"), r.get("model"), r.get("variant"),
                r.get("price_guide"), r.get("price_sale"), r.get("stock"), r.get("fuel_type")));
        }
        return sb.toString();
    }
}
```

```java
package org.example.ai.agent.tool;

import org.springframework.stereotype.Component;

/**
 * 图片生成工具
 * 对应 Dify Stability Diffusion 节点 (1784516286955)
 * 实际部署时对接 Stability API 或替换为其他图片生成服务
 */
@Component
public class ImageGenerationTool {

    /**
     * 根据提示词生成汽车图片
     * @param promptJson LLM2 生成的 JSON 格式图片提示词
     * @return 生成的图片 URL
     */
    public String generate(String promptJson) {
        // TODO: 对接 Stability AI API 或替代服务
        // Stability API: POST https://api.stability.ai/v2beta/stable-image/generate/sd3
        // 参数: prompt, model=sd3-turbo, aspect_ratio=16:9

        // 当前返回占位，实际对接时替换
        return null;
    }
}
```

#### 10.4.14 🔑 核心：AgentOrchestrator（整个 Dify Graph 的 Java 实现）

```java
package org.example.ai.agent;

import org.example.ai.agent.advisor.IntentClassifierAdvisor;
import org.example.ai.agent.advisor.KnowledgeRetrievalAdvisor;
import org.example.ai.agent.advisor.McpToolAdvisor;
import org.example.ai.agent.branch.BranchHandler;
import org.example.ai.agent.router.IntentRouter;
import org.example.ai.agent.router.IntentType;
import org.example.ai.model.ChatContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 🔑 Agent 主编排器
 * 
 * 这是整个 Dify Chatflow 的 Java 等价实现。
 * Dify 画布上的节点和边，映射为以下处理链：
 *
 *   User Input
 *     │
 *     ▼
 *   ① McpToolAdvisor.execute()          ← MCP Tool: search_inventory
 *     │
 *     ▼
 *   ② IntentClassifierAdvisor.classify() ← Question Classifier: 5路分类
 *     │
 *     ▼
 *   ③ IntentRouter.route(intent)         ← 路由到对应的 BranchHandler
 *     │
 *     ▼
 *   ④ KnowledgeRetrievalAdvisor.retrieve() ← 按意图加载对应知识库
 *     │
 *     ▼
 *   ⑤ BranchHandler.handle(ctx)          ← 分支处理（LLM 链 + 回复）
 *     │
 *     ▼
 *   Response → Controller → 用户
 */
@Service
public class AgentOrchestrator {

    private final ChatClient chatClient;
    private final McpToolAdvisor mcpToolAdvisor;
    private final IntentClassifierAdvisor classifierAdvisor;
    private final KnowledgeRetrievalAdvisor knowledgeAdvisor;
    private final IntentRouter router;

    public AgentOrchestrator(
            ChatClient.Builder builder,
            McpToolAdvisor mcpToolAdvisor,
            IntentClassifierAdvisor classifierAdvisor,
            KnowledgeRetrievalAdvisor knowledgeAdvisor,
            IntentRouter router) {
        this.chatClient = builder.build();
        this.mcpToolAdvisor = mcpToolAdvisor;
        this.classifierAdvisor = classifierAdvisor;
        this.knowledgeAdvisor = knowledgeAdvisor;
        this.router = router;
    }

    /**
     * 处理用户消息的完整流程
     * 这等价于 Dify Chatflow 从 Start → ... → Answer 的完整执行
     */
    public String process(String userQuery, String userId, String userIp) {
        // 1. 构建上下文（对应 Dify 的 {{#sys.query#}} 等变量初始化）
        ChatContext ctx = new ChatContext();
        ctx.setUserQuery(userQuery);
        ctx.setUserId(userId);
        ctx.setUserIp(userIp);

        // 2. 【Dify节点: MCP Tool】调用 search_inventory 查库存
        mcpToolAdvisor.execute(ctx);

        // 3. 【Dify节点: 问题分类器】判断用户意图（5分类）
        IntentType intent = classifierAdvisor.classify(userQuery);
        ctx.setIntent(intent);

        // 4. 【Dify边: 分类器出口 → 分支】路由到对应处理器
        BranchHandler handler = router.route(intent);

        // 5. 【Dify节点: 知识检索】按意图加载对应知识库
        knowledgeAdvisor.retrieve(ctx);

        // 6. 【Dify分支: LLM链 → 回复】执行分支处理
        return handler.handle(ctx);
    }
}
```

#### 10.4.15 Controller 入口

```java
package org.example.ai.controller;

import org.example.ai.agent.AgentOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final AgentOrchestrator orchestrator;

    public ChatController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 同步对话 — 对应 Dify response_mode: blocking
     */
    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String reply = orchestrator.process(
            request.message(),
            request.userId(),
            request.userIp()
        );
        return new ChatResponse(reply);
    }

    /** 普通对话 GET 版本（浏览器快速测试） */
    @GetMapping("/chat")
    public String chatGet(@RequestParam(defaultValue = "你好") String message) {
        return orchestrator.process(message, "web-user", "127.0.0.1");
    }

    public record ChatRequest(String message, String userId, String userIp) {}
    public record ChatResponse(String content) {}
}
```

#### 10.4.16 ChatClient Bean 配置

```java
package org.example.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置
 * 注入 ChatClient.Builder，供 Agent 各组件使用
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient.Builder chatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
```

### 10.5 Dify 节点 → Spring AI 代码对照速查表

| Dify 画布元素 | Spring AI 代码位置 | 行数参考 |
|:--|:--|:--|
| Start 节点 | `ChatController.chat()` | `@PostMapping("/api/chat")` |
| MCP Tool `search_inventory` | `McpToolAdvisor.execute()` + `SearchInventoryTool.searchInventory()` | Advisor 第1步 |
| 知识检索（混合检索+重排序） | `KnowledgeRetrievalAdvisor.retrieve()` | Advisor 第5步 |
| 问题分类器（5路LLM） | `IntentClassifierAdvisor.classify()` | Advisor 第3步 |
| 分类器5个出口边 | `IntentRouter.route()` | Router |
| If-Else（库存判断） | `SpecificCarHandler.handle()` 中的 `if (ctx.isStockEmpty())` | 分支1 |
| LLM2（图片提示词） | `SpecificCarHandler.handle()` 中第1次 `chatClient.prompt()` | 分支1 |
| Stability工具 | `ImageGenerationTool.generate()` | 分支1 |
| LLM（主回复） | `SpecificCarHandler.handle()` 中第2次 `chatClient.prompt()` | 分支1 |
| 问题分类器2 | 简化为 `if (imageUrl != null)` 判断 | 分支1结尾 |
| 知识检索4 + LLM4 + LLM3 | `CarRecommendHandler.handle()` | 分支2 |
| 知识检索3 + LLM5 | `EmotionalHandler.handle()` | 分支3 |
| 直接回复5（打招呼） | `GreetingHandler.handle()` | 分支4 |
| 直接回复6（拒绝回复） | `UnsafeContentHandler.handle()` | 分支5 |
| 整个 Graph 编排 | `AgentOrchestrator.process()` | 核心 🔑 |

### 10.6 关键设计决策

| 决策 | Dify 做法 | Spring AI 做法 | 理由 |
|:--|:--|:--|:--|
| 问题分类 | moonshot-v1-8k 专用小模型 | 复用 deepseek-chat + 结构化 prompt | 减少模型依赖 |
| 知识检索时机 | 在分类器之前（全局检索） | 在分类器之后（按意图检索） | 按需加载，减少无关知识注入 |
| 图片生成 | Stability Diffusion API | `ImageGenerationTool`（可替换） | 对接成本可控 |
| 多轮对话 | Dify conversation_id | Spring AI `ChatMemory` + Advisor | 第6阶段实现 |
| 分支间数据传递 | `{{#node_id.text#}}` 变量引用 | `ChatContext` 对象字段 | 类型安全 |

---

## 11. 分层实施路线图

### 第1阶段：基础迁移（1-2天）✅ 当前已完成

- [ ] Spring Boot 项目搭建
- [ ] ChatController 基础对话
- [ ] 前端聊天界面
- [ ] DeepSeek API 配置

### 第2阶段：知识库构建（2-3天）

- [ ] 将知识库 .md 文件复制到 `src/main/resources/knowledge/`
- [ ] 集成 PGVector 或 Redis Stack（二选一）
- [ ] 实现 `KnowledgeBaseInitializer`（启动时向量化文档）
- [ ] 实现 `HybridKnowledgeRetriever`（混合检索）
- [ ] 车辆库存表从 SQLite 迁移到 MySQL/PostgreSQL
- [ ] 实现 `SearchInventoryTool`、`CheckStockTool`、`GetPriceTool` 三个工具

### 第3阶段：模糊语义 + 多问题处理（2-3天）

- [ ] 实现 `CarRankingService`（四维排序算法）
- [ ] 实现 `ContextAwareRetriever`（对话历史中提取车辆指代）
- [ ] 实现 `MultiQuestionHandler`（多问题拆分+回答）
- [ ] 实现门店热度统计逻辑

### 第4阶段：幻觉防御 + 双模型验证（1-2天）

- [ ] System Prompt 防幻觉规则
- [ ] `HallucinationDetector`（规则引擎）
- [ ] `DualModelVerifier`（关键场景双模型验证）

### 第5阶段：动态配置 + 多门店（1-2天）

- [ ] 多门店数据库表设计 + 初始化
- [ ] `StoreLocator`（IP定位 + 最近门店）
- [ ] `DynamicPromptBuilder`（动态 System Prompt）
- [ ] 闲聊计数器 → 3次自动终止

### 第6阶段：RPA 企微消息发送（2-3天）

- [ ] 截取企微客户端定位模板图（search_icon.png, chat_input.png 等）
- [ ] 实现 `WeComRPA`（图像识别 + pyautogui 操控）
- [ ] 实现 `RPAAgent` 任务调度器（轮询 + 频率控制 + 重试）
- [ ] Spring AI 侧 `RpaMessageService` + `sendWechatMessage` 工具
- [ ] 防封策略配置（频率、时间、随机延迟）
- [ ] 异常监控：nssm 守护进程 + 企微掉线告警

### 第7阶段：完善与测试（2-3天）

- [ ] 对话记忆（ChatMemory + MessageChatMemoryAdvisor）
- [ ] 转人工（→ 写入 pending_messages 触发 RPA 通知销售）
- [ ] 照片返回功能
- [ ] 集成测试 + 端到端测试（用户提问 → AI回复 → RPA发送 → 企微收到）

---

## 附录：技术选型对比

### A. 向量数据库选择

| 特性 | PGVector | Redis Stack | SimpleVectorStore |
|------|----------|-------------|-------------------|
| 部署复杂度 | 需要 PostgreSQL | 需要 Redis | 零依赖（内存） |
| 查询性能 | 百万级 <100ms | 十万级 <50ms | 千级 <10ms |
| Spring AI 支持 | ✅ 一流 | ✅ 一流 | ✅ 内置 |
| 混合查询（向量+SQL） | ✅ 天然支持 | ❌ 需额外设计 | ❌ 不支持 |
| 推荐场景 | **生产环境** | 小规模生产 | 开发/原型 |

**推荐：PGVector**，因为你需要同时做 SQL 结构化查询 + 向量语义查询。

### B. Embedding 模型选择

| 模型 | 维度 | 成本 | 推荐场景 |
|------|------|------|---------|
| DeepSeek Embedding | 1536 | DeepSeek 计费 | 与对话模型同一厂商 |
| OpenAI text-embedding-3-small | 1536 | ~$0.02/1M token | 精度高 |
| BGE-M3 (本地) | 1024 | 免费 | 数据敏感、离线场景 |

**推荐：先用 DeepSeek Embedding**（你已有 API Key），后续可考虑 BGE-M3 本地部署降低成本。