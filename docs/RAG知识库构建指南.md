# RAG 知识库构建指南(Spring AI + Chroma)

> 适用项目:智能客服(Spring Boot 4 + Spring AI 2.0)
> 资料位置:`custom-agent/customer-try/knowledge_base`(6 个 txt + 2 个 md,共约 130KB)
> 向量库:Chroma(docker 部署)

---

## 0. 全局流程

```
资料文件 ──► [1] 解析 DocumentReader ──► [2] 切分 TextSplitter ──► [3] Embedding ──► [4] 写入向量库 Chroma
                                                                                         │
用户提问 ──► [5] 相似度检索(topK + 阈值 + 过滤) ◄─────────────────────────────────────────┘
              │
              ▼
            [6] 检索结果拼入 Prompt ──► Chat 模型生成回答
```

向量库只是链路的一环,**检索质量主要取决于:解析质量、切分策略、embedding 模型**。

---

## 1. Docker 部署 Chroma

### 方式一:手动部署(推荐,先跑通)

> ⚠️ Windows 用户注意:本文档命令按 **PowerShell** 编写(PowerShell 不认 bash 的 `\` 续行符,多行命令请写成一行,或用反引号 `` ` `` 续行)。Git Bash / Linux / macOS 下可用 `\` 续行。

```powershell
# 拉取镜像
docker pull chromadb/chroma:1.5.10.dev224

# 启动容器(一行写完;-v 用命名卷做持久化,容器删了数据还在)
docker run -d --name chroma -p 8000:8000 -v chroma-data:/data chromadb/chroma:1.5.10.dev224

# 验证(Spring AI 2.0 使用 v2 API,Chroma 1.x 服务端兼容)
curl.exe http://localhost:8000/api/v2/heartbeat
```

说明:
- 命名卷 `chroma-data` 由 Docker 管理;若想映射到本机目录,**必须写完整 Windows 路径**,如 `-v D:\data\chroma:/data`(不能写 `/data/chroma` 这种无盘符路径)
- 官方镜像默认启动命令就是 `chroma run --path /data`,**无需在 docker run 后追加任何参数**

常用维护命令:

```powershell
docker logs -f chroma        # 看日志
docker stop chroma           # 停止
docker start chroma          # 重启(数据保留在 chroma-data 卷中)
docker rm -f chroma; docker volume rm chroma-data   # 彻底清空重建
```

### 方式二:用项目自带的 compose.yaml(可选)

项目 pom 中已有 `spring-ai-spring-boot-docker-compose`,把 Chroma 写进 `compose.yaml` 后,**启动 Spring Boot 时会自动拉起容器并自动注入连接配置**,无需手动配 host/port:

```yaml
services:
  chroma:
    image: chromadb/chroma:1.5.10.dev224
    ports:
      - "8000:8000"
    volumes:
      - chroma-data:/data
volumes:
  chroma-data:
```

两种方式选一个即可。开发期推荐方式二(零配置),生产环境用方式一或独立部署。

---

## 2. Maven 依赖

```xml
<!-- Chroma 向量库 starter(必加) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
</dependency>

<!-- Markdown 文档解析(本项目有 2 个 md,建议加) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document</artifactId>
</dependency>

<!-- 其他格式按需引入:
     spring-ai-pdf-document     (PDF)
     spring-ai-tika-document    (Word/PPT/Excel 等万能解析)
     spring-ai-jsoup-document   (HTML) -->
```

Embedding 模型**不需要新增 starter**——复用已有的 `spring-ai-starter-model-openai` 即可(可指向 OpenAI 或任何 OpenAI 兼容端点)。

---

## 3. 步骤一:文档解析 DocumentReader

| 资料格式 | Reader | 依赖 | 行为说明 |
|---|---|---|---|
| .txt | `TextReader` | 内置 | 整个文件读成 1 个 Document;可设置编码;metadata 自动带 `source`=文件名 |
| .md | `MarkdownDocumentReader` | spring-ai-markdown-document | **按标题层级拆成多个 Document**,标题信息写入 metadata,结构化程度最高 |
| .pdf | `PagePdfDocumentReader` / `ParagraphPdfDocumentReader` | spring-ai-pdf-document | 按页切 / 按段落目录切 |
| .docx/.pptx/.xlsx | `TikaDocumentReader` | spring-ai-tika-document | 万能解析,抽取纯文本 |
| .html | `JSoupDocumentReader` | spring-ai-jsoup-document | 去标签抽正文 |
| .json | `JsonReader` | 内置 | 按 JSON Pointer 抽取字段 |

**去哪里浏览全部 Reader:**
- 官方文档(ETL Pipeline 章节):https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- 源码:IDE 中 Ctrl+N 搜 `org.springframework.ai.reader` 包下的类,逐个看 Javadoc

**本项目选择:** 6 个 txt 用 `TextReader`;2 个 md 用 `MarkdownDocumentReader`(按标题天然成块)。

---

## 4. 步骤二:切分策略 TextSplitter

### 内置方案

**`TokenTextSplitter`(默认,最常用)**:按 token 数滑动窗口切分,分词器为 cl100k_base。构造参数:

```java
new TokenTextSplitter(
    chunkSize,            // 每块目标 token 数,默认 800
    minChunkSizeChars,    // 块最小字符数,默认 350
    minChunkLengthToEmbed,// 小于此长度的块丢弃,默认 5
    maxNumChunks,         // 单文档最大块数,默认 10000
    keepSeparator);       // 是否保留分隔符,默认 true
```

**`MarkdownDocumentReader` 按标题成块**:结构化 md(如"一款车一个 `###` 小节")可不再二次切分,保住语义完整。

**自定义切分**:继承 `TextSplitter` 或实现 `Function<List<Document>, List<Document>>`,按正则、双换行、业务标记(如"话术一/话术二")切。

### 切分参数经验值(中文客服语料)

| 场景 | chunkSize 建议 | 说明 |
|---|---|---|
| 短句话术 / FAQ | 200–400 token | 粒度细,命中准 |
| 产品/车型参数(本项目百科类) | 400–600 token | 一款车的信息尽量留在同一块 |
| 长文叙述 | 800(默认)+ 重叠 | 通用兜底 |

**注意事项:**
- cl100k_base 下中文约 1 字 ≈ 1 token,800 token ≈ 500–800 汉字
- chunk 太小 → 上下文不全,答案缺信息;太大 → 检索噪声大、重点被稀释
- **结构化内容优先按结构(标题/条目)切,不要被 token 窗口从中间切断**
- 批量入库时用 `TokenCountBatchingStrategy` 分批送 embedding(默认每批 8192 token),避免超限

**去哪里浏览:** 同上 ETL 文档页 "Text Splitters" 一节;源码包 `org.springframework.ai.transformer.splitter`。

---

## 5. 步骤三:Embedding 模型选择

**chat 模型 ≠ embedding 模型**,需单独配置。DeepSeek 官方没有 embedding API,现有 deepseek starter 只供 chat 用。

| 方案 | 推荐模型 | 配置方式 | 特点 |
|---|---|---|---|
| OpenAI 官方 | text-embedding-3-large / -small | `spring.ai.openai.embedding.options.model` | 省心,按量付费 |
| OpenAI 兼容端点(硅基流动、阿里百炼等) | **bge-m3** / text-embedding-v3 | `spring.ai.openai.base-url` 指向对应端点,复用 openai starter | 中文效果好,便宜或有免费额度 |
| 本地 Ollama | bge-m3 | 加 `spring-ai-starter-model-ollama` | 离线,吃本机资源 |

**中文资料首选 bge-m3 或 text-embedding-v3。**

⚠️ 关键约束:**embedding 模型一旦确定并入库,更换模型必须清空 collection 重建索引**(不同模型向量空间不可混用)。

配置示例(application.yml):

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      # 用兼容端点时加一行: base-url: https://api.siliconflow.cn
      embedding:
        options:
          model: bge-m3   # 或 text-embedding-3-small
```

---

## 6. 步骤四:写入 Chroma

application.yml:

```yaml
spring:
  ai:
    vectorstore:
      chroma:
        client:
          host: http://localhost
          port: 8000
        collection-name: car-sales-kb   # 建议显式命名
        initialize-schema: true          # 启动时自动创建 collection
```

入库代码骨架(可作 CommandLineRunner 或一次性接口):

```java
List<Document> docs = new ArrayList<>();
for (Resource resource : knowledgeBaseFiles) {
    if (resource.getFilename().endsWith(".md")) {
        docs.addAll(new MarkdownDocumentReader(resource).get());
    } else {
        docs.addAll(new TextReader(resource).get());
    }
}

TokenTextSplitter splitter = new TokenTextSplitter(500, 350, 5, 10000, true);
List<Document> chunks = splitter.apply(docs);

vectorStore.add(chunks);   // 框架自动完成 embedding + 写入 Chroma
```

**要点:**
- metadata 至少保留 `source`(文件名),便于答案溯源和按文件维护
- **增量更新某个文件**:先按 metadata 过滤删除该文件的旧 chunk(`vectorStore.delete(...)` Filter 表达式,如 `source == '汽车百科知识库.md'`),再重新入库
- 大批量数据用 `TokenCountBatchingStrategy` 分批,避免一次请求过大

---

## 7. 步骤五:检索与问答接入

### 直接检索(调试用)

```java
List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("10 万预算家用第一辆车推荐")
        .topK(5)
        .similarityThreshold(0.6)
        .build());
```

### 接入对话(QuestionAnswerAdvisor,最简方式)

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder()
            .topK(5)
            .similarityThreshold(0.6)
            .build())
        .build())
    .build();
```

Advisor 会在每次请求前自动检索,把命中 chunk 注入 prompt。

### 进阶可选策略

| 策略 | 作用 | 实现 |
|---|---|---|
| metadata 过滤 | 限定检索范围(如只搜"百科类") | SearchRequest 的 filterExpression,如 `type == '百科'` |
| 相似度阈值 | 检索不到就不硬答,防幻觉 | similarityThreshold |
| rerank 二阶段精排 | 向量粗排 → 交叉编码器重排,提升准确率 | 自建或调 rerank API(bge-reranker) |
| 混合检索 | 关键词(BM25)+ 向量互补 | Chroma 侧或应用层组合 |
| 多 query 扩展 | 把一个问题改写成多个再检索 | `RetrievalAugmentationAdvisor`(spring-ai-rag 模块) |

---

## 8. 本项目落地清单(推荐顺序)

1. `docker pull chromadb/chroma:1.5.10.dev224` 并启动容器,heartbeat 验证通过
2. pom 加 `spring-ai-starter-vector-store-chroma` + `spring-ai-markdown-document`
3. 选定 embedding 模型(建议 bge-m3 兼容端点),配好 api-key
4. application.yml 配 Chroma 连接 + collection
5. 写入库逻辑:txt → TextReader;md → MarkdownDocumentReader(按标题成块);txt 用 TokenTextSplitter 400–500 token
6. metadata 打标:`source`=文件名,`type`=话术/百科
7. 执行入库,检查 Chroma 中 chunk 数量是否符合预期
8. **用真实客户问题验证检索命中**(如"10 万预算推荐什么车""客户说太贵了怎么接话"),不对再调 chunkSize / topK / threshold
9. 接 QuestionAnswerAdvisor 进客服对话链路,端到端验证

---

## 9. 参考链接

- ETL(Readers / Splitters):https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- Vector Stores 总览:https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Chroma 配置项:https://docs.spring.io/spring-ai/reference/api/vectordbs/chroma.html
- Embedding 模型:https://docs.spring.io/spring-ai/reference/api/embeddings.html
- RAG / Advisor:https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html
- Chroma 官方 docker 文档:https://docs.trychroma.com/production/containers/docker