# 卖好车 · 智能汽车销售客服

> Spring Boot 4 + Spring AI 2.0 + ChromaDB + SQLite，LLM 自主决策 Agent

---

## 分支说明（重要）

| 分支 | 用途 | Chat 模型 | Chroma |
|------|------|-----------|--------|
| `main` / `new` | **线上发布** | 阿里百炼 `qwen-max`（`DASHSCOPE_API_KEY`） | 内网 `172.21.10.248:8001` |
| `test` | **日常开发** | DeepSeek `deepseek-chat`（`DEEPSEEK_API_KEY`） | 本地 Docker `127.0.0.1:8001` |

两个分支的 `src` 代码一致，仅 `src/main/resources/application.properties` 中的 Chat 模型与 Chroma 地址不同。
文档若未特殊说明，均以当前分支配置为准。

---

## 快速启动

```bash
# 1. 配置 API Key（start.sh 已 gitignore，含真实 Key；首次使用前核对）
#    编辑 start.sh，填入本分支对应的 API Key

# 2. 启动 ChromaDB（Docker，compose.yml 只编排 Chroma）
docker compose up -d chroma

# 3. 启动应用（start.sh 会等待 Chroma 就绪后执行 ./mvnw spring-boot:run）
source start.sh   # Windows: bash start.sh
# 浏览器打开 http://localhost:8080/chat
```

> 旧版流程 `cp startup.sh start.sh` 已废弃：`start.sh` 已在仓库工作区维护（被 gitignore，含本地 Key）。

---

## 架构

```
用户输入 → Controller（ChatController / MacanChatController）
  └─ ChatService（唯一业务契约）
      └─ CarSalesAgent（Agent 实现）
          ├─ IdleChatGate             闲聊门（黑名单制，3 轮送客）
          ├─ EntityResolver           实体识别（别名 → 车系）
          ├─ ConversationGuidanceBuilder  对话理解（指代承接 / 意图识别）
          ├─ RetrievalContextAssembler    分层检索上下文组装（BRAND/FAMILY/SERIES/UNRESTRICTED）
          │   └─ HybridRetriever      BM25 + BGE-M3 并行 → RRF 融合
          └─ ChatClient（LLM + 工具调用）
              ├─ searchInventory()    搜索库存（LIMIT 30）
              ├─ getAllCars()         全库存摘要（LIMIT 15）
              ├─ getStoreInfo(city)   最近门店（region 命中 / positionstack 兜底）
              └─ getHotCars(storeId)  门店热门排行
```

### 检索链路（分级，回复过长治理）

```
retrieveContext(query, matchedSeries)
  ├─ QueryLevelClassifier 粒度判定：
  │   细节词（多少钱/配置/对比…）→ UNRESTRICTED
  │   单实体 → SERIES / 同品牌多实体 → BRAND 或 FAMILY / 跨品牌 → UNRESTRICTED
  ├─ BRAND    ：不查向量库，只在售车系数量 + 热度 top1 + 级别指令
  ├─ FAMILY   ：命中车系父块（截断"在售款型"段）
  ├─ SERIES   ：单车系完整父块 + 级别指令（零子块）
  └─ UNRESTRICTED（现状主路径）
       ├─ 阶段一：父块混合召回（BM25 + BGE-M3，阈值 0.6，topK=3）
       ├─ 主路径：命中车系 → 子块展开（单车系 topK=20 / 多车系预算 9，sku_id 去重）
       └─ 回退路径：子块泛检 topK=3 + 百科/话术补充 topK=2
  价格门控：客户未问价 → 从注入上下文物理剥离价格片段（渐进式披露三层防线之一）
```

### 已实现的阶段

| 阶段 | 内容 | 状态 |
|------|------|------|
| 一 | 知识库框架（父子文档 + 原子事实 + 哈希增量更新） | ✅ |
| 二 | GeoLocator + StoreLocator + HotCarRepository + DynamicKeywordBuilder | ✅ |
| 三 | 模糊语义路由（#13–#15 初版，后并入对话理解链路，见下） | ✅ |
| 四 | 混合检索（BM25 + BGE-M3 + RRF 融合） | ✅ |
| 五 | 查询分级分层检索 + 渐进式披露 + 价格门控（#21–#27，回复过长治理） | ✅ |
| 六 | 对话理解并入主链路（ConversationGuidanceBuilder 替代 VagueQueryRouter） | ✅ |
| 七 | Langfuse 可观测（OTLP）、macan 企微客服平替千问、销售线索兜底 | ✅ |

> 注：`VagueQueryRouter` 三层路由（#36–#43）已回退并融入 `ConversationGuidanceBuilder` + `RetrievalContextAssembler` 正常链路，源码中该文件为遗留死代码，未参与装配。

---

## 技术栈

| 组件 | 版本/说明 |
|------|----------|
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0 |
| Java | 17（本地实测 JDK 21 亦可编译，target 17） |
| ChromaDB | Docker 容器，端口 8001 |
| SQLite | `company_inventory.db` |
| Embedding | BAAI/bge-m3（SiliconFlow API） |
| Chat | main=`qwen-max`（阿里百炼）/ test=`deepseek-chat` |
| 可观测 | Langfuse（OTLP/HTTP，`langfuse.enabled=true`） |
| 地理编码 | positionstack（可选，缺省降级"未找到门店"） |

---

## 项目结构

```
src/main/java/org/example/ai/
├── ChatService.java                 # 对话服务唯一契约（chat / chatStream / chatWithHistory）
├── AiApplication.java               # 启动类
├── config/
│   ├── AiConfig.java                # ChatClient.Builder Bean
│   ├── DatabaseInitializer.java     # SQLite 建表 + CSV 种子 + entity_mapping 重建
│   ├── DynamicKeywordBuilder.java   # 动态关键词表（entity_mapping + car_sku）
│   └── observability/               # Langfuse OTLP 装配（LangfuseProperties / LangfuseObservabilityConfig / ObservationSupport）
├── controller/
│   ├── ChatController.java          # /chat、/api/chat、/api/chat/stream、/api/health
│   ├── MacanChatController.java     # /api/chat/messages（企微客服平替千问）
│   └── KnowledgeController.java     # /api/kb/*（检索调试 + SKU/文档刷新）
├── impl/                            # Agent 实现层
│   ├── CarSalesAgent.java           # 核心 Agent（ChatService 唯一实现）
│   ├── context/RetrievalContextAssembler.java   # 分级检索上下文组装 + 价格门控
│   ├── conversation/                # ConversationGuidance(Builder) 对话理解
│   ├── location/                    # GeoLocator / FixedGeoLocator / PositionStackGeoLocator / StoreLocator / HotCarRepository
│   ├── prompt/PromptTemplates.java  # System Prompt（乔哈里窗口 + 渐进式披露）
│   ├── routing/                     # IdleChatGate（闲聊门）/ QueryLevelClassifier / QueryLevel / QueryClassification
│   ├── search/                      # Bm25Index / Bm25Indexer / HybridRetriever（RRF 融合）
│   └── tool/CarSalesTools.java      # 4 个 @Tool（searchInventory / getAllCars / getStoreInfo / getHotCars）
├── knowledge/
│   ├── KnowledgeBaseInitializer.java   # 知识文档增量同步（doc_sync_log 哈希对比）
│   ├── CarSkuVectorIndexer.java        # SKU 向量全量/增量索引
│   ├── entity/EntityResolver.java      # 实体归一化（entity_mapping 内存索引）
│   ├── facts/                          # 原子事实提取（SkuFactExtractor / LlmFactExtractor / SeriesParentBuilder）
│   ├── hotness/AskCountTracker.java    # 8 周衰减热度追踪
│   └── update/                         # SkuVectorUpdater / SkuChangeListener / InMemoryIndexRefresher
└── model/CarInventory.java
```

---

## API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat?message=xxx` | GET | 浏览器快速测试，返回纯文本 |
| `/api/chat` | POST | 对话 `{"message":"...","userId":"..."}` → `{"success":true,"data":{"reply":"..."}}` |
| `/api/chat/stream` | POST | SSE 流式对话（`data:` 逐字 + `data:[DONE]`） |
| `/api/chat/messages` | POST | macan 企微客服平替千问（OpenAI messages 格式，无状态） |
| `/api/health` | GET | 健康检查 |
| `/api/kb/search?q=xxx&topK=5` | GET | 知识库检索调试 |
| `/api/kb/sku/refresh?skuId=x` | POST | SKU 增量同步（不传 skuId = 全量哈希对比） |
| `/api/kb/doc/refresh` | POST | 百科/话术文档增量刷新 |

---

## 环境变量

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 阿里百炼 Key（main/new 线上 Chat） |
| `DEEPSEEK_API_KEY` | DeepSeek Key（test 本地开发） |
| `SILICONFLOW_API_KEY` | SiliconFlow Key（BGE-M3 Embedding） |
| `ANTHROPIC_API_KEY` | Anthropic/Claude（备用） |
| `POSITIONSTACK_API_KEY` | positionstack 地理编码（可选，缺省降级） |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_BASE_URL` | Langfuse 可观测（可选，未启动不阻塞） |

---

## 配置要点

```properties
# 数据库（SQLite）
spring.datasource.url=jdbc:sqlite:company_inventory.db

# Chat（main 分支：qwen-max；test 分支：deepseek-chat）
spring.ai.model.chat=openai
spring.ai.openai.api-key=${DASHSCOPE_API_KEY}
spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
spring.ai.openai.chat.model=qwen-max

# Embedding（硅基流动）
spring.ai.openai.embedding.api-key=${SILICONFLOW_API_KEY}
spring.ai.openai.embedding.base-url=https://api.siliconflow.cn/v1
spring.ai.openai.embedding.options.model=BAAI/bge-m3

# ChromaDB（main：内网 172.21.10.248；test：127.0.0.1 本地 Docker）
spring.ai.vectorstore.chroma.client.host=http://172.21.10.248
spring.ai.vectorstore.chroma.client.port=8001
spring.ai.vectorstore.chroma.collection-name=car-sales-kb

# Langfuse 可观测
langfuse.enabled=true
langfuse.endpoint=${LANGFUSE_BASE_URL:http://127.0.0.1:3000}
```

---

## 常用命令

```bash
./mvnw clean package -DskipTests   # 打包
cp target/AI-0.0.1-SNAPSHOT.jar app.jar   # 复制为 Dockerfile 需要的 app.jar
docker compose up -d chroma       # 本地 Chroma
```

详见 `docs/Maven打包指南.md`。
