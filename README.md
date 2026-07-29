# 卖好车 · 智能汽车销售客服

> Spring AI 2.0 + ChromaDB + SQLite + DeepSeek，LLM 自主决策 Agent

---

## 快速启动

```bash
# 1. 配置 API Key
cp startup.sh start.sh
# 编辑 start.sh，填入真实 API Key

# 2. 启动 ChromaDB（Docker）
docker start chromadb

# 3. 启动应用
source start.sh
# 浏览器打开 http://localhost:8080/
```

---

## 架构

```
用户输入 → ChatController
  ├─ EntityResolver（实体识别：别名→车系）
  ├─ VagueQueryRouter（L1 精确车系匹配）
  ├─ HybridRetriever（BM25 关键词 + BGE-M3 语义 → RRF 融合）
  └─ ChatClient（LLM + 工具调用）
       ├─ searchInventory()  搜索库存
       ├─ getAllCars()       获取全部车源
       ├─ getStoreInfo()     门店地址/电话
       └─ getHotCars()       门店热门排行
```

### 检索链路

```
retrieveContext(query)
  ├─ 阶段一：父块混合召回（BM25 + BGE-M3，阈值 0.6）
  ├─ 主路径：命中车系 → 子块全量展开（sku_id 去重）
  └─ 回退路径：子块泛检 + 百科/话术补充
```

### 已实现的阶段

| 阶段 | 内容 | 状态 |
|------|------|------|
| 一 | 知识库框架（父子文档 + 原子事实 + 增量更新） | ✅ |
| 二 | GeoLocator + StoreLocator + HotCarRepository | ✅ |
| 三 | VagueQueryRouter（L1 精确车系匹配） | ✅ |
| 四 | 混合检索（BM25 + BGE-M3 + RRF 融合） | ✅ |

---

## 技术栈

| 组件 | 版本/说明 |
|------|----------|
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0 |
| Java | 17 |
| ChromaDB | Docker 容器，端口 8001 |
| SQLite | `company_inventory.db` |
| Embedding | BAAI/bge-m3（SiliconFlow API） |
| Chat | DeepSeek（主力） |

---

## 项目结构

```
src/main/java/org/example/ai/
├── agent/
│   ├── CarSalesAgent.java          # 核心 Agent
│   ├── prompt/PromptTemplates.java # System Prompt
│   └── tool/CarSalesTools.java     # 4 个 @Tool
├── config/
│   ├── AiConfig.java               # ChatClient Bean
│   ├── DatabaseInitializer.java    # 建表+种子数据
│   └── DynamicKeywordBuilder.java  # 动态关键词表
├── controller/
│   ├── ChatController.java         # REST API
│   └── KnowledgeDebugController.java
├── knowledge/
│   ├── entity/EntityResolver.java  # 实体归一化
│   ├── facts/                      # 原子事实提取
│   ├── hotness/AskCountTracker.java # 热度追踪
│   └── update/SkuVectorUpdater.java # 增量更新
├── location/
│   ├── GeoLocator.java            # IP→坐标
│   ├── StoreLocator.java          # Haversine 最近门店
│   └── HotCarRepository.java      # 门店×车系热度
├── routing/
│   ├── VagueQueryRouter.java      # 模糊语义路由
│   └── MatchResult.java           # 路由结果
└── search/
    ├── Bm25Index.java             # BM25 关键词引擎
    ├── Bm25Indexer.java           # 启动索引构建
    └── HybridRetriever.java       # 并行混合检索+RRF
```

---

## API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 聊天页面 |
| `/api/chat` | POST | 对话 `{"message":"...","userId":"..."}` |
| `/api/health` | GET | 健康检查 |
| `/api/kb/search?q=xxx` | GET | 知识库调试检索 |
| `/api/kb/sku/refresh?skuId=x` | POST | 触发 SKU 增量更新 |

---

## 环境变量

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `SILICONFLOW_API_KEY` | SiliconFlow API Key（BGE-M3 Embedding + Reranker） |
| `ANTHROPIC_API_KEY` | Anthropic/Claude（备用） |

---

## 配置要点

```properties
# ChromaDB
spring.ai.vectorstore.chroma.client.host=http://127.0.0.1
spring.ai.vectorstore.chroma.client.port=8001
spring.ai.vectorstore.chroma.collection-name=car-sales-kb

# Embedding
spring.ai.openai.api-key=${SILICONFLOW_API_KEY}
spring.ai.openai.base-url=https://api.siliconflow.cn/v1
spring.ai.openai.embedding.options.model=BAAI/bge-m3

# Chat
spring.ai.model.chat=deepseek
```
