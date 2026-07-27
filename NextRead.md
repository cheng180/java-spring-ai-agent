# NextRead — 本次会话变更总结

> 2026-07-23 ~ 2026-07-24，从"基础知识库构建"到"端到端 RAG 智能客服"的完整实施记录。
> 适合下次接手或回顾迭代轨迹时快速还原。

---

## 一、一句话概括

把 8 份销售语料 + 47 条公司真实车源（navicat-db 导出的 `aito_car_sku` 表）全部写入 Chroma 向量库，并将 RAG 检索注入 `CarSalesAgent` 对话链路——工具查实时车源、向量库补百科/话术，LLM 综合回答。

---

## 二、新增文件

| 文件 | 作用 |
|---|---|
| `src/main/resources/knowledge/*.md/*.txt` | 8 份知识库语料（从 Python demo 复制，约 131KB） |
| `src/main/resources/data/car_sku.csv` | 47 条真实车源（脚本从 navicat-db 解析导出） |
| `scripts/parse_navicat_db.py` | 解析工具：MySQL dump → CSV |
| `src/main/java/.../knowledge/KnowledgeBaseInitializer.java` | 启动时把 md/txt 写入 Chroma（@Order(2)） |
| `src/main/java/.../knowledge/CarSkuVectorIndexer.java` | 启动时把 car_sku 表上架车源写入 Chroma（@Order(3)） |
| `src/main/java/.../controller/KnowledgeDebugController.java` | `GET /api/kb/search?q=xxx` 检索调试接口 |

## 三、修改的文件

| 文件 | 改动 |
|---|---|
| `pom.xml` | +3 依赖：`spring-ai-starter-vector-store-chroma`、`spring-ai-markdown-document-reader`、`spring-ai-vector-store-advisor` |
| `application.properties` | Chroma 连接（127.0.0.1:8001）、硅基流动 bge-m3 embedding（base-url 带 /v1） |
| `DatabaseInitializer.java` | 全面重写：建 `car_sku` 表（对齐公司库）、从 CSV 加载 47 条数据、删旧 inventory 表 → @Order(1) |
| `CarSalesTools.java` | 查询对象从旧 inventory 改为 car_sku：品牌/车系/车型搜索、分→万价格展示、能源类型、只查上架车源 |
| `CarSalesAgent.java` | 构造器注入 VectorStore + QuestionAnswerAdvisor（topK=5, threshold=0.5）；RAG 自动在每轮对话前检索 |
| `PromptTemplates.java` | 新增一条规则：模型知道会有知识库上下文注入，但具体价格以工具查到的实时数据为准 |

## 四、Spring AI 1.x → 2.0 迁移（4 处 API 差异）

本项目用 Spring Boot 4.1.0 + Spring AI 2.0.0，RAG 指南按 1.x 编写，落地时踩了这些坑：

| # | 1.x 写法 | 2.0 正确写法 | 涉及文件 |
|---|---------|------------|---------|
| 1 | `spring-ai-markdown-document` | `spring-ai-markdown-document-reader` | `pom.xml` |
| 2 | `new TokenTextSplitter(500,350,5,10000,true)` | `TokenTextSplitter.builder().withChunkSize(500)…build()` | `KnowledgeBaseInitializer.java` |
| 3 | `new MarkdownDocumentReader(resource)` | `new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig())` | `KnowledgeBaseInitializer.java` |
| 4 | `spring.ai.openai.base-url=https://api.siliconflow.cn`（1.x 自动拼 `/v1`） | 必须带 `/v1`：`https://api.siliconflow.cn/v1`（2.0 透传给官方 SDK，不自动拼接） | `application.properties` |

此外 **QuestionAnswerAdvisor 在 2.0 被移到了独立 artifact**：`spring-ai-vector-store-advisor`（1.x 在 `spring-ai-advisors-vector-store` 里，该包没有 2.0 版本）。

## 五、环境问题：Windows 8000 端口冲突

本机 8000 端口被三个进程同时监听——Docker（`com.docker.backend`）、VMware（`vmnat`）、WSL（`wslrelay`）。Windows 允许多进程绑同一端口，连接被随机分发：curl 恰好连到 Docker 能通，Java 被分到 vmnat 则 Connection reset。

**解决**：Chroma 容器从 `-p 8000:8000` 改为 `-p 8001:8000`，配置里端口同步改 8001。后续本机 Docker 服务一律避开 8000。

## 六、架构决策：车源数据怎么入向量库

**navicat-db 是 47 条 `aito_car_sku` 表数据**（每行：品牌/车系/车型/颜色/指导价/裸车价/销售价/能源/车商/备注等）。

选择了一条一行、不做切分的策略：

- **SQLite** 存原始结构化数据 → `@Tool` 精确查询（"奥迪A4L多少钱"）
- **Chroma** 存文本化后的车源 → 语义检索兜底（"二十来万的豪华轿车"）
- 每行由 `CarSkuVectorIndexer.render()` 渲染成一句话描述（如"奥迪A4L 25款 40 TFSI…，水晶银，燃油车，指导价28.98万，全款23.33万，中规/国产，店内保险，可开增票，车商：山东易驾通"）再 embedding
- 不经过 TextSplitter——避免把一条车源从中间切断

启动时按 `type='车源'` 删旧后全量写入，保证向量库与数据库一致。

## 七、最终知识库全景

| 数据面 | 存储位置 | 条数 | 检索方式 |
|---|---|---|---|
| 车源 SKU | SQLite `car_sku` | 47（29 上架） | `@Tool` → JdbcTemplate 精确查询 |
| 车源 SKU（文本化） | Chroma `car-sales-kb`（type=车源） | 29 | 向量语义检索 |
| 汽车百科 | Chroma（type=百科） | 72 块 | 向量语义检索 |
| 销售话术 + 情绪处理 | Chroma（type=话术） | 112 块 | 向量语义检索 |
| **总计** | | **213 块** | 同一 collection，按 metadata type 区分 |

## 八、配置要点

```properties
# Embedding：硅基流动 bge-m3（OpenAI 兼容，免费额度）
spring.ai.openai.api-key=${SILICONFLOW_API_KEY}
spring.ai.openai.base-url=https://api.siliconflow.cn/v1          # ⚠️ 必须带 /v1
spring.ai.openai.embedding.options.model=BAAI/bge-m3

# Chroma
spring.ai.vectorstore.chroma.client.host=http://127.0.0.1        # ⚠️ 不能用 localhost
spring.ai.vectorstore.chroma.client.port=8001                    # ⚠️ 避开 8000
spring.ai.vectorstore.chroma.collection-name=car-sales-kb
spring.ai.vectorstore.chroma.initialize-schema=true

# Chat 模型仍走 DeepSeek
spring.ai.model.chat=deepseek
```

## 九、启动步骤

```bash
# 1. 确保 Docker Desktop 运行中，Chroma 容器在跑
docker ps | grep chroma || docker start chroma

# 2. 设环境变量（硅基流动 key）
export SILICONFLOW_API_KEY=sk-你的key

# 3. 启动
./mvnw spring-boot:run

# 4. 浏览器打开 http://localhost:8080/ 聊天
#    GET /api/kb/search?q=xxx&topK=5 调试检索
#    POST /api/chat 对话接口
```

- 第二次启动时知识库已入库，自动跳过重复写入（幂等检查）
- Chroma 数据存 Docker 卷 `chroma-data`，容器即使删了重建也不丢
- 如需重建索引：删 Chroma collection 后重启即可

## 十、后续可做

- [ ] 流式输出对接前端（`/api/chat/stream` 已有，前端未用）
- [ ] 车源变动时的增量同步（目前全量刷新 29 条足够快，SKU 上万后再改）
- [ ] 把 `car_sku` 的其他成本字段（物流、利息、其他成本）也暴露给工具
- [ ] `store_config` 门店表也接真实公司数据
- [ ] 检索质量调优：topK/threshold 根据线上真实问答调参