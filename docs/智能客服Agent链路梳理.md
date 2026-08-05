# 智能客服 Agent 链路梳理

> 基于源码的实际链路梳理（2026-08-04），非设计稿。所有结论均可在对应源码中验证。
>
> 核心类索引：入口 `controller/ChatController`、`controller/MacanChatController` → 契约 `ChatService` → 唯一实现 `impl/CarSalesAgent`。

## 0. 一句话总览

消息进来 → **实体识别 + 闲聊黑名单过滤** → 记热度 → **按查询粒度分级检索**（能少给就少给，细节问题才全量展开）→ 检索结果作为 system 上下文，连同会话记忆交给 LLM，LLM 自主决定直接答还是**调库存/门店工具**再答 → 输出回复并记录带级别归因的结构化日志。

两个贯穿全局的设计思想：

- **渐进式披露**：品牌级只推一个车系、车系级不列清单、问到细节才倒数据——控制单轮信息量，防回复过长。
- **防幻觉**：价格/库存/地址/电话一律以工具实时查询为准，system prompt 内置三条幻觉红线。

## 1. 入口层（三个入口，一个实现）

所有调用方都通过 `ChatService` 接口消费，不感知 RAG/实体/分级检索/工具调用等内部实现：

| 入口 | 端点 | 场景 | 状态模型 |
|---|---|---|---|
| `ChatController` | `GET /chat`、`POST /api/chat` | Web 前端 | 有状态：`userId` 关联 ChatMemory |
| `ChatController` | `POST /api/chat/stream`（SSE） | Web 前端流式 | 同上 |
| `MacanChatController` | `POST /api/chat/messages` | macan 企微客服平替千问 API | **无状态**（见下） |

- Web 入口从 `X-Forwarded-For` / `getRemoteAddr` 提取客户端 IP，供门店定位使用。
- macan 入口协议与 OpenAI chat completions 的 messages 字段一致：macan 每次请求自带 Redis 全量历史；AI 侧用**一次性会话 ID**（`userId-UUID`）灌入历史 → 生成回复 → `finally` 中立即清空 ChatMemory，不跨请求保留状态，防串话、防内存堆积。macan 传来的 system 消息被忽略（AI 侧使用自有人设 + RAG 上下文）。

## 2. 主链路：一条消息的处理顺序

以 `CarSalesAgent.chat()` 为准（`chatStream()` 大体一致，差异见 §7）。

```
用户消息
  │
  ├─① EntityResolver 实体识别
  ├─② IdleChatGate 闲聊门          ──► 闲聊分支（可能直接短路）
  ├─③ AskCountTracker 热度记录
  ├─④ RetrievalContextAssembler 分级检索（含 QueryLevelClassifier 粒度分类）
  ├─⑤ ChatClient 调 LLM（人设 system + RAG 上下文 + ChatMemory + 工具）
  ├─⑥ logConversation 结构化日志
  └─⑦ 返回回复
```

### ① EntityResolver 实体识别

- 数据源 `entity_mapping` 表，启动时载入内存别名索引（ConcurrentHashMap），**运行时零数据库访问**。
- 两层匹配：别名精确包含 / 去特殊字符后子串包含（处理中文无分词，如"比亚迪宋PLUS" ⊂ "比亚迪宋plusdmi"）。
- 长匹配优先（防"宋"先于"宋PLUS"命中），按 entity_id 去重。
- 产出 `matchedSeries`（`ResolvedEntity` 列表），贯穿后续所有环节。

### ② IdleChatGate 闲聊门（#29，黑名单制）

- **黑名单制**：只穷举"什么是明确闲聊"，命中才判闲聊；**其余一切消息默认进业务管线**。不确定性导向业务侧——漏网的最坏代价是多跑一次廉价检索，而不是丢销售线索。
- 黑名单两类：
  - 话题类（天气/笑话/问身份）：子串命中即判；
  - 寒暄类（问候/感谢/道别）：带长度守卫，仅当消息几乎只有寒暄（有效长度 ≤ 命中词长 + 4）才判，防"你好，我想买x3"误判。
- **实体保险丝**：判定式 = `isIdleChat(msg) && matchedSeries.isEmpty()`——提到车系实体的消息永不判闲聊。
- 闲聊分支行为：
  - 连续 ≤ 3 轮：只带 ChatMemory 调 LLM 简短回应（`chat()` 中不做检索）；
  - 连续 > 3 轮：直接返回固定送客语"买车的事随时找我，先不打扰您了～"，**不调 LLM**；
  - 一旦非闲聊：计数清零。

### ③ AskCountTracker 热度记录

- 命中的每个车系记入 `series_ask_count` 周桶（UPSERT +1）。
- 热度公式：`heat = Σ(最近8周每周询问次数 × 衰减系数[1.0→0.03]) + 销量代理`。
- 消费方：BRAND 级推荐排序（§5）。

### ④ RetrievalContextAssembler 分级检索（#4 / #23 / #24）

先由 `QueryLevelClassifier`（纯逻辑，零基础设施依赖）判定粒度，再按级别注入不同详细度的上下文。详见 §5。

### ⑤ ChatClient 调 LLM

组装结构：

- **system**：默认人设（`PromptTemplates.systemPrompt`，"小张"销售顾问：对话规则、渐进式披露、说话风格、幻觉红线、试驾到店指引）+ 本轮 RAG 上下文（`## 匹配车系` / `## 在售车型` / `## 相关知识` / `## 级别指令`）；
- **advisor**：`MessageChatMemoryAdvisor` → `MessageWindowChatMemory`（InMemory，窗口 40 条，按 `userId` / 会话 ID 隔离）；
- **tools**：`CarSalesTools`，LLM 自主决定何时调用哪个工具（代码不做流程编排）。

### ⑥ logConversation 结构化日志

JSON 单行日志：`ts / userId / msg / reply / idleCount / isIdle / terminated / matched（命中实体）/ level（分类级别归因）/ levelBrand`。

级别归因（NONE=闲聊或未分级；BRAND / FAMILY / SERIES / UNRESTRICTED）用于上线后排查"该给细节却给了摘要"类问题（《回复过长问题解决评估文档》加固建议 1）。

## 3. 什么时候跳过检索

| 跳过条件 | 检索 | LLM | 适用入口 |
|---|---|---|---|
| 闲聊 ≤ 3 轮 | ✗ | ✓（纯记忆简短回应） | 仅 `chat()`（见 §7 不对称） |
| 闲聊 > 3 轮 | ✗ | ✗（固定送客语） | 两者 |
| BRAND 级分类 | **不查向量库**（只算热度 + JDBC 销量表） | ✓ | 两者 |
| 其余一切 | ✓ | ✓ | 两者 |

## 4. 检索基础设施：HybridRetriever

混合检索引擎，用于 UNRESTRICTED 路径的父块召回与回退泛检索：

- **两路并行**（独立线程池，10s 超时）：
  - BM25 关键词检索（内存索引 `Bm25Indexer`，候选取 topK×2）；
  - BGE-M3 语义检索（Chroma 向量库，可带相似度阈值与元数据过滤）；
- **RRF 融合**：`RRF_score(d) = Σ 1/(60 + rank_i(d))`，按文本 SHA-256 指纹跨路去重，降序取 topK；
- 单路超时/失败静默降级为另一路结果。

## 5. 分级检索细节（RetrievalContextAssembler）

### 5.1 QueryLevelClassifier 粒度分类（判定序，先命中先返回）

1. 含**细节触发词**（多少钱/价格/优惠/万/预算/配置/参数/续航/油耗/详细/具体/区别/对比/哪个好/还是…）→ **UNRESTRICTED**（细节问题需全量数据，防幻觉手段不动）；
2. 实体命中：1 个 → **SERIES**；同品牌 ≥2 个 → 数量 >3 或 ≥ 品牌全部在售车系数 → **BRAND**（裸品牌名保险丝），否则 **FAMILY**；跨品牌 ≥2 个 → **UNRESTRICTED**（对比查询需两边完整数据）；
3. 无实体但品牌关键词命中：≥2 车系 → BRAND；1 车系 → SERIES；
4. 无信号 → UNRESTRICTED。

### 5.2 各级上下文组装

| 级别 | 上下文内容 | 级别指令要点 |
|---|---|---|
| **BRAND** | 不查向量库：在售车系数 + 近期最热门 top1（加权热度排序，并列时用 `store_car_hot` 销量全局求和兜底） | 只推最热门那一个车系，反问收尾；禁列车系清单；本轮禁调 searchInventory/getAllCars |
| **FAMILY** | 按 `series_id` 确定性取命中车系父块（不走混合检索，防 BM25 无过滤混入噪音），**截断"在售款型"段**防车型清单倒出 | 只介绍命中的几个车系，末尾问想深入哪个 |
| **SERIES** | 该车系完整父块（热度/价格区间/车型列表） | 只讲这个车系，末尾问是否深入了解 |
| **UNRESTRICTED** | 现状两阶段（见下） | 无 |

任一级别数据缺失（品牌无在售车系 / 父块缺失）→ 降级回退路径。

### 5.3 UNRESTRICTED 两阶段检索（#4）

```
阶段一：父块相似度召回
   HybridRetriever（BM25 + BGE-M3 → RRF），过滤 type=车源 & level=parent
   阈值 0.6，topK=3 → 收集命中的 series_id
   ＋ EntityResolver 命中的车系（父块缺失时按 series_id 补取）

命中任何车系 → 主路径：
   阶段二：命中车系子块全量展开
   按 parent_series_id 过滤，topK=20/车系，按 sku_id 去重
   有车系锚点时跳过百科/话术（避免通用知识与具体车系混淆）

全未命中 → 回退路径（泛检索）：
   子块 topK=5（阈值 0.5）＋ 非车源文档（百科/话术）topK=3
```

上下文输出格式：`## 匹配车系`（父块全文）/ `## 在售车型`（子块列表）/ `## 相关知识`（带 type 前缀）。

## 6. 工具（Tool Calling）

`CarSalesTools`，注册为 `defaultTools`，LLM 自主决策调用时机与参数：

| 工具 | 用途 | 数据源 / 兜底 |
|---|---|---|
| `searchInventory(query)` | 查库存车源（品牌/车系/车型、颜色、全款/金融价、能源类型） | `car_sku`（仅上架未删除），DynamicKeywordBuilder 提关键词（≤3 个）拼 LIKE 查询 |
| `getAllCars()` | 全库存摘要（无目标推荐场景） | `car_sku` 全量在售 |
| `getStoreInfo(city)` | 最近一家门店（地址/电话/营业时间） | region LIKE 命中 → positionstack 地理编码 + Haversine 最近门店 → 未找到提示；**未传城市时反问而非猜测** |
| `getHotCars(storeId)` | 门店热门车系排行（销量/咨询量） | `store_car_hot` top5 |

金额单位换算：库内为分，展示转万元（`÷1,000,000`，两位小数）。

## 7. 已知不一致 / 注意点

1. **`chatStream()` 闲聊路径与 `chat()` 不对称**：`chat()` 中闲聊 ≤3 轮跳过检索直接调 LLM；`chatStream()` 只拦截 >3 轮，≤3 轮的闲聊会继续走检索 + LLM。若"闲聊不浪费检索"是设计意图，流式入口是遗漏。
2. **system prompt 工具箱只列了 3 个工具**（searchInventory / getAllCars / getStoreInfo），实际注册了 4 个（另有 `getHotCars`）——LLM 仍可调用，但 prompt 未引导。
3. **ChatMemory 为 InMemory**：重启丢失；macan 路径本就无状态不受影响。

## 8. 数据模型：实体 ≠ 原子事实

两个不同层次的概念——**实体是锚点，原子事实是挂在锚点上的知识碎片**。

### 8.1 实体（Entity）——归一化对象

| 实体 | ID 格式 | 说明 |
|---|---|---|
| 车系实体（主力） | `entity:car:<品牌slug>:<车系slug>`，如 `entity:car:byd:song-plus-dm-i` | 由 `car_sku` 去重 `brand_name+series_name` 构建，**仅在售车系**（下架车系不进实体映射，避免宣称无库存车系存在）；带别名（中英文互转、大小写、口语简称），EntityResolver 可识别 |
| 文档桶实体（仅溯源） | `entity:doc:encyclopedia` / `entity:doc:script` | 语料文件提取的事实统一挂靠，不在 entity_mapping 中，不可被 EntityResolver 命中 |

`entity_mapping` 表结构：`(entity_id, display_name, aliases_json)`，每次启动清空重建；车源变更广播后也会重建（新车系无需重启即可识别）。

`ResolvedEntity(entityId, displayName, brand, series)`，`seriesKey() = "品牌-车系"`（如"比亚迪-宋PLUS DM-i"），与热度统计 key、向量 metadata 的 `series_id` 一致。

### 8.2 原子事实（AtomicFact）——知识库最小语义单元

一条自包含、可独立检索的陈述，字段：`factId`（溯源）、`content`、`entityId`（归属实体）、`temporalType`（时效性）、`sourceDoc`、`sourceHash`（SHA-256 增量检测）、`metadata`。

三个生产者：

| 提取器 | 原料 | 挂靠实体 | 时效 |
|---|---|---|---|
| `SkuFactExtractor` | car_sku 每行 → SKU 事实 | 车系实体 | DYNAMIC（价格可变） |
| `SeriesParentBuilder` | 按车系聚合 → 父块事实 | 车系实体 | — |
| `LlmFactExtractor` | 语料 .md/.txt → LLM 命题化拆分 | 文档桶实体 | — |

### 8.3 向量库 metadata 约定

- 父块：`type=车源, level=parent, series_id=<车系key>`
- 子块：`type=车源, level=child, parent_series_id=<车系key>, sku_id=<SKU>`
- 百科/话术：`type≠车源`，`entity_id=<文档桶实体>`

## 9. 离线 / 启动链路与热更新

### 启动顺序（CommandLineRunner @Order）

1. **@Order(2) `KnowledgeBaseInitializer`**：`resources/knowledge/` 下 8 个语料文件 → LlmFactExtractor 原子事实提取 → Chroma。**增量模式（#6）**：首次全量重建；后续启动按文档 SHA-256 对比 `doc_sync_log`，只重建变化文档，删除的文档自动清理向量。
2. **@Order(3) `CarSkuVectorIndexer`**：`car_sku`（启动从 `data/car_sku.csv` 灌入 47 条真实车源）→ 父块 + 子块 → Chroma。
3. `DatabaseInitializer`：建表（car_sku / entity_mapping / series_ask_count / store_config / store_car_hot / doc_sync_log…）+ `rebuildEntityMapping()`。
4. 各内存索引初始化：EntityResolver 别名索引、DynamicKeywordBuilder 关键词表、Bm25Indexer。

### 热更新链路（车源变更广播，#3 决策8）

```
MQ 车源变更消息（当前为 Mock，REST 手动触发）
   │  SkuChangeListener.onSkuChanged(SkuChangeEvent)
   ▼
上半场：SkuVectorUpdater —— 变更写进 Chroma 向量库（实时）
下半场：InMemoryIndexRefresher.refreshAll() —— 按依赖顺序重建四份知识结构：
   entity_mapping 表（数据源）→ EntityResolver 别名索引
   → DynamicKeywordBuilder 关键词表 → Bm25Indexer 内存索引
```

修复背景：四份知识结构（Chroma 向量 / BM25 / 实体别名 / 关键词表）原先只有向量实时刷新，其余三个只在启动时构建，导致"知识库没更新，必须重启"。

## 10. 最终输出形态

| 场景 | 输出 |
|---|---|
| 同步接口 | `{"success":true,"data":{"reply":"..."}}`（Web）/ `{"reply":"..."}`（macan） |
| 流式接口 | SSE：`data:<chunk>` … `data:[DONE]` |
| 闲聊 >3 轮 | 固定送客语（不经 LLM） |

## 附：相关源码索引

| 环节 | 类 |
|---|---|
| 入口 | `controller/ChatController`、`controller/MacanChatController` |
| 契约 / 实现 | `ChatService`、`impl/CarSalesAgent` |
| 闲聊门 | `impl/routing/IdleChatGate` |
| 实体识别 | `knowledge/entity/EntityResolver`、`ResolvedEntity`、`config/DatabaseInitializer.rebuildEntityMapping()` |
| 粒度分类 | `impl/routing/QueryLevelClassifier`、`QueryLevel`、`QueryClassification` |
| 检索组装 | `impl/context/RetrievalContextAssembler` |
| 混合检索 | `impl/search/HybridRetriever`、`Bm25Index`、`Bm25Indexer` |
| 热度 | `knowledge/hotness/AskCountTracker` |
| 工具 | `impl/tool/CarSalesTools` |
| 人设 | `impl/prompt/PromptTemplates` |
| 知识构建 | `knowledge/KnowledgeBaseInitializer`、`knowledge/CarSkuVectorIndexer`、`knowledge/facts/*` |
| 热更新 | `knowledge/update/SkuChangeListener`、`SkuVectorUpdater`、`InMemoryIndexRefresher` |
| 门店定位 | `impl/location/*`（GeoLocator / StoreLocator / HotCarRepository / PositionStackGeoLocator） |
