# NextRead — 会话变更总结

> 2026-07-29，本次会话完成阶段二（IP定位+门店+热度+动态关键词，3 ticket）+ 阶段三（VagueQueryRouter模糊语义路由，3 ticket）。
> 知识库框架 + 门店感知 + 模糊路由三个基础设施全部到位。下一站：多问题拆分（#7/#8）或混合检索（阶段四）。

---

## 本次会话（2026-07-29 下午）：阶段二 + 阶段三

### 阶段二：IP定位 + 门店匹配 + 动态热度（#10 #11 #12）

| Ticket | 组件 | 状态 |
|--------|------|------|
| #10 | GeoLocator + StoreLocator | ✅ |
| #11 | HotCarRepository + store_car_hot | ✅ |
| #12 | DynamicKeywordBuilder | ✅ |

**Commit:** `24a8c45`

#### Ticket #10 — GeoLocator + StoreLocator：门店就近推荐

**GitHub:** [#10](https://github.com/cheng180/java-spring-ai-agent/issues/10)

| 新增文件 | 职责 |
|---------|------|
| `location/GeoLocation.java` | record(lat, lng, city) |
| `location/GeoLocator.java` | 接口：`GeoLocation locate(String userIp)` |
| `location/FixedGeoLocator.java` | @Component 假实现，固定返回杭州未来科技城(30.28, 120.02) |
| `location/StoreInfo.java` | record 含经纬度 |
| `location/StoreLocator.java` | @Component，Haversine 公式：`findNearest(lat,lng)` / `findNearest(lat,lng,topN)` / `findAllWithDistance(lat,lng)` |

| 重构文件 | 变更 |
|---------|------|
| `DatabaseInitializer.java` | `store_config` 表加 `latitude REAL, longitude REAL` 列 + ALTER TABLE 迁移；6 家门店种子数据补真实经纬度（北京朝阳39.9/116.5、海淀39.98/116.32、上海浦东31.24/121.51、深圳南山22.54/113.96、成都锦江30.65/104.08、广州天河23.13/113.33）；旧数据库 UPDATE 兜底 |
| `CarSalesTools.java` | 注入 StoreLocator + GeoLocator；`getStoreInfo()` 按距离排序返回门店并标注公里数 |

**Haversine 公式：** 纯 Java 实现，无外部依赖。`StoreLocator.haversineKm()` public static，CarSalesTools 复用。

**门店匹配链路：**
```
HttpServletRequest.getRemoteAddr() → userIp
  → GeoLocator.locate(userIp) → GeoLocation(lat, lng)
  → StoreLocator.findNearest(lat, lng) → StoreInfo
  → getStoreInfo() 按距离排序返回门店列表
```

#### Ticket #11 — HotCarRepository：门店×车系热度

**GitHub:** [#11](https://github.com/cheng180/java-spring-ai-agent/issues/11)

| 新增文件 | 职责 |
|---------|------|
| `location/HotCar.java` | record(seriesName, saleCount, inquiryCount) + totalHeat() |
| `location/HotCarRepository.java` | @Component，`getHotCars(storeId)` / `getHotCars(storeId, topN)`，按综合热度降序 |

| 重构文件 | 变更 |
|---------|------|
| `DatabaseInitializer.java` | `createTables()` 新增 `store_car_hot` 表 DDL（store_id + series_name + sale_count + inquiry_count + stat_date）；`seedStoreCarHot()` 插入 ~30 条种子数据（6 门店 × 5 车系，模拟区域偏好差异） |
| `CarSalesTools.java` | 新增 `getHotCars(storeId)` tool |

**热度表结构（决策7）：**
```sql
CREATE TABLE store_car_hot (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    store_id        INTEGER NOT NULL REFERENCES store_config(id),
    series_name     TEXT    NOT NULL,
    sale_count      INTEGER DEFAULT 0,
    inquiry_count   INTEGER DEFAULT 0,
    stat_date       DATE    NOT NULL,
    UNIQUE(store_id, series_name, stat_date)
);
```

**区域偏好差异设计：** 北京朝阳偏好比亚迪+理想，海淀偏好特斯拉+蔚来，上海偏好特斯拉+奥迪+宝马，深圳偏好比亚迪+问界，成都偏好吉利+长安+理想，广州偏好丰田+本田+大众。

#### Ticket #12 — DynamicKeywordBuilder：动态关键词替代硬编码

**GitHub:** [#12](https://github.com/cheng180/java-spring-ai-agent/issues/12)

| 新增文件 | 职责 |
|---------|------|
| `config/DynamicKeywordBuilder.java` | @Component + InitializingBean，启动时从 entity_mapping（displayName+aliases）和 car_sku（brand_name+series_name）构建内存关键词表；`containsAnyKeyword(text)` / `extractKeywords(text)` / `getSeriesKeys(keyword)` |

| 重构文件 | 变更 |
|---------|------|
| `CarSalesAgent.java` | 注入 DynamicKeywordBuilder；`isIdleChat()` 切换为 CAR_TOPIC_WORDS（通用话题词）+ DynamicKeywordBuilder + EntityResolver 三重检测；移除 75 行硬编码 CAR_KEYWORDS |
| `CarSalesTools.java` | `searchInventory()` 用 `keywordBuilder.extractKeywords()` 替代每次查库构建品牌/车系列表 |

**关键词结构：**
- `keywordToSeries`: Map<关键词, List<seriesKey>> —— 品牌名映射到该品牌所有车系，车系名映射到对应车系
- `allKeywords`: Set —— 全部归一化关键词
- `<2 字符` 自动跳过防误匹配

---

### 阶段三：VagueQueryRouter 模糊语义路由（#13 #14 #15）

| Ticket | 组件 | 状态 |
|--------|------|------|
| #13 | VagueQueryRouter 核心骨架 + L1 精确匹配 + userIp 链路 | ✅ |
| #14 | L2 品牌级匹配 + 热度引导 | ✅ |
| #15 | L3 级联兜底三步 | ✅ |

**Commits:** `0619f85` + `486f9da` + `6ee5c23`

#### VagueQueryRouter 架构

```
CarSalesAgent.chat(userId, message, userIp):

  1. EntityResolver.resolve(message) → matchedSeries
  2. isIdleChat() 判断
  3. IF not idle:
     a. VagueQueryRouter.route(message, historyText, userIp) → MatchResult
     b.
        ┌── EXACT:                       走现有两阶段检索（不改行为）
        ├── BRAND + needsConfirm=true:   直接返回 followUpText（零 LLM 成本）
        ├── VAGUE + needsConfirm=true:   直接返回 followUpText
        ├── VAGUE + needsInference=true: followUpText 注入 system prompt → 走 ChatClient
        └── null:                        走现有回退路径（子块泛检+百科）
```

**MatchResult 模型：**
```java
record MatchResult(
    MatchType type,          // EXACT | BRAND | VAGUE
    String carKey,           // EXACT 时的 seriesKey
    String brand,            // BRAND 时的品牌名
    List<String> hotModels,  // BRAND 时的热度排序车系列表
    boolean needsConfirm,    // 先追问确认再进入检索
    boolean needsInference,  // 引导文本注入 LLM prompt
    String followUpText      // 追问/引导文本
)
```

#### Ticket #13 — VagueQueryRouter 核心骨架 + L1 + userIp 链路

**GitHub:** [#13](https://github.com/cheng180/java-spring-ai-agent/issues/13)

| 新增文件 | 职责 |
|---------|------|
| `routing/MatchResult.java` | 路由结果 record + MatchType 枚举 |
| `routing/VagueQueryRouter.java` | @Component，`route(message, historyText, userIp)` → 三层匹配 |

| 重构文件 | 变更 |
|---------|------|
| `ChatController.java` | 注入 `HttpServletRequest`，提取客户端 IP（X-Forwarded-For 优先 → getRemoteAddr 兜底）；所有接口传入 `chat(userId, message, userIp)` |
| `CarSalesAgent.java` | `chat()` / `chatStream()` 加 `userIp` 参数；resolveEntities 之后、检索之前插入 `router.route()`；EXACT → 走检索；needsConfirm → 直接返回 followUpText；needsInference → 注入 system prompt |

**L1 精确匹配逻辑：**
```
EntityResolver.resolve(message) → top-1 entity
  → 父块相似度验证（阈值 0.7, filter: type=车源 AND level=parent AND series_id=xxx）
  → 通过 → EXACT MatchResult
  → 未通过 → null（下放 L2/L3）
```

**userIp 链路：**
```
HttpServletRequest → clientIp() 提取
  → CarSalesAgent.chat(userId, message, userIp)
  → VagueQueryRouter.route(message, historyText, userIp)（L3 Step2 用）
  → GeoLocator.locate(userIp) → StoreLocator.findNearest() → 门店 ID
```

#### Ticket #14 — L2 品牌级匹配

**GitHub:** [#14](https://github.com/cheng180/java-spring-ai-agent/issues/14)

**L2 品牌级匹配逻辑：**
```
DynamicKeywordBuilder.extractKeywords(message)
  → 找对应 >= 2 个不同车系的关键词（去重后判断，避免重复注册误判）
  → 该品牌下全部车系按 AskCountTracker 加权热度降序排列
  → 生成追问："我们{品牌}在售的有{top3}等{N}款。您想看轿车还是SUV？预算大概多少？"
  → BRAND MatchResult(needsConfirm=true)
```

**设计要点：**
- 追问文本由代码模板生成，不调 LLM（零 token 成本 + 风格可控）
- 品牌关键词通过 `getSeriesKeys(kw).size() >= 2` 判断（单系列不走 L2）
- 修复了 DynamicKeywordBuilder 重复注册导致的去重 bug

#### Ticket #15 — L3 级联兜底三步

**GitHub:** [#15](https://github.com/cheng180/java-spring-ai-agent/issues/15)

**L3 三步级联：**
```
Step 1: 对话历史提取
  buildHistoryText(userId) → 取最近 10 轮对话
  → DynamicKeywordBuilder.extractKeywords(historyText)
  → 命中 → "您之前聊过{seriesName}，是在关心这款车吗？"
  → needsConfirm=true

Step 2: 门店热度 top3 引导
  历史无匹配 → resolveStoreId(userIp)
  → GeoLocator.locate(userIp) → StoreLocator.findNearest() → storeId
  → HotCarRepository.getHotCars(storeId, 3)
  → 命中 → "我们最近卖得最好的是A、B、C，您想了解哪款？还是我帮您按预算推荐？"
  → needsConfirm=true

Step 3: 通用引导追问
  门店无热度 → "您大概预算多少？主要通勤还是家用？喜欢轿车还是SUV？我帮您精准推荐～"
  → needsInference=true（注入 system prompt，让 LLM 继续对话）
```

**新增注入：** VagueQueryRouter 注入 GeoLocator + StoreLocator + HotCarRepository

---

### 测试

**79 → 92 测试**（全部通过，零失败）

| 测试类 | 数量 | 变更 |
|--------|------|------|
| `StoreLocatorTest` | 10 | **新增**：Haversine(北京→上海~1068km、同点、对跖点)、最近门店、topN、空门店安全 |
| `DynamicKeywordBuilderTest` | 9 | **新增**：别名加载、新增品牌识别、删除品牌不识别、提取关键词长词优先、品牌映射 |
| `HotCarRepositoryTest` | 4 | **新增**：热度排序、topN 截断、门店隔离、空门店 |
| `VagueQueryRouterTest` | 13 | **新增**：L1 EXACT/L1 无匹配、L2 BRAND/单系列不走L2/无关键词、L3 历史命中/空历史/门店热度引导/通用追问 |
| 已有测试 | 56 | 无退化 |

---

### 设计决策

| # | 决策 | 选择 |
|---|------|------|
| 1 | L2 是否调 LLM | 不调——代码模板生成追问，零 token 成本 + 风格可控 |
| 2 | L3 Step2 门店 ID 来源 | GeoLocator + StoreLocator 动态计算，不做硬编码 storeId=1 |
| 3 | userIp 传递方式 | ChatController(HttpServletRequest) → agent.chat() → router.route()，全链路透传 |
| 4 | VagueQueryRouter 依赖 | 注入 EntityResolver + DynamicKeywordBuilder + AskCountTracker + VectorStore + GeoLocator + StoreLocator + HotCarRepository |
| 5 | BRAND/VAGUE + needsConfirm | 直接返回 followUpText，不进入 ChatClient 链（省 token + 快响应） |
| 6 | VAGUE + needsInference | followUpText 注入 system prompt，走 ChatClient（保留 LLM 自然对话能力） |

---

## 历史记录

### 2026-07-29（阶段一后半）：知识库重构收尾（3 ticket）

> EntityResolver + 文档级增量 + 两阶段检索。
> 已完成阶段一全部知识库重构项。三 Collection/蓝绿切换经核查暂缓（见 issue #1 评论）。

---

## 本次会话：知识库重构收尾（#4 #5 #6）

> 基于核查结论：`智能客服优化方案规格.md` 阶段一中父子文档/增量/热度已在上次完成，
> 本次补齐剩余 3 项——EntityResolver、文档级增量、两阶段检索。
> 三 Collection 拆分 + 蓝绿切换经评估暂缓（MQ 广播驱动下动机消解）。
> 多问题拆分暂未实施（ticket #7 #8 已建，见下文待办）。

### Ticket #5 — EntityResolver：实体归一化运行时应用 ✅ 完成

**GitHub:** [#5](https://github.com/cheng180/java-spring-ai-agent/issues/5)

| 新增文件 | 职责 |
|---------|------|
| `knowledge/entity/ResolvedEntity.java` | record：entityId + displayName + brand + series + seriesKey() |
| `knowledge/entity/EntityResolver.java` | @Component，启动时从 `entity_mapping` 表构建内存索引（ConcurrentHashMap）；`resolve(text)` 两层匹配——Layer1 别名包含、Layer2 去分隔符子串回退（处理中文无分词场景如"比亚迪宋PLUS"匹配"比亚迪-宋PLUS DM-i"）；`hasMatch(text)` 快速布尔判断 |
| `knowl`edge/entity/EntityResolverTest.java` | 14 测试：别名归一、大小写、中英文、口语别名、长匹配优先、空输入安全、seriesKey 格式 |

| 重构文件 | 变更 |
|---------|------|
| `CarSalesAgent.java` | 注入 `EntityResolver` 替代 `JdbcTemplate`+`cachedSeriesKeys`；`matchSeries()` → `resolveEntities()` 返回 `List<ResolvedEntity>`；移除 `refreshSeriesCache()`；`isIdleChat()` 增加实体命中判断 |

**匹配策略（防止"比亚迪宋PLUS"丢匹配）：**
- Layer 1：文本包含精确别名 → 直接命中
- Layer 2：去特殊字符后，别名包含用户输入的连续子串（str`ipSpecials(alias).contains(stripSpecials(text))`），最小 3 字符防误匹配
- 多实体时按匹配长度降序、entity_id 去重

### Ticket #6 — 百科/话术文档级增量更新 ✅ 完成

**GitHub:** [#6](https://github.com/cheng180/java-spring-ai-agent/issues/6)

| 修改文件 | 变更 |
|---------|------|
| `DatabaseInitializer.java` | `createTables()` 新增 `doc_sync_log(source TEXT PK, source_hash TEXT, last_synced_at TEXT)` |
| `KnowledgeBaseInitializer.java` | 重写 `run()` → `refreshDocs()`：集合为空则全量重建；否则遍历每个文件算 SHA-256 → 对比 `doc_sync_log` → 只重建变化的文档；新增文件自动入库；删除文件自动清理向量 + 日志；注入 `JdbcTemplate` |
| `KnowledgeRefreshController.java` | 新增 `POST /api/kb/doc/refresh` |
| `SkuFactExtractor.java` | `sha256()` 从 package-private 改为 public（跨包复用） |

**增量流程：**
```
启动/手动触发 → refreshDocs()
  ├── Collection 为空 → 全量重建（首次）
  └── 非空 → syncIncremental()
        ├── 遍历 knowledge/*.md *.txt
        │   ├── SHA-256 对比 doc_sync_log
        │   ├── 未变更 → 跳过（零 LLM 成本）
        │   └── 变更/新增 → deleteBySource(filter) → LLM 提取 → add → upsert log
        └── doc_sync_log 中已删除的文件 → deleteBySource + remove log
```

### Ticket #4 — 检索重构：父块优先 + 子块全量展开 ✅ 完成

**GitHub:** [#4](https://github.com/cheng180/java-spring-ai-agent/issues/4)

| 修改文件 | 变更 |
|---------|------|
| `CarSalesAgent.java` | 移除 `QuestionAnswerAdvisor`；新增 `retrieveContext()` 三层检索 + `buildContext()` 组装 |

**检索流程（2026-07-29，经 grill 决策对齐后最终版）：**

```
retrieveContext(userMessage, matchedSeries):

  阶段一 — 父块相似度召回（阈值 0.6，topK=3）
    filter: type=车源 AND level=parent
    → 用户问的是车系，父块语义最匹配
    → 收集 hitSeries: parent.series_id + EntityResolver.seriesKey

  若 hitSeries 为空（父块相似度零命中 + EntityResolver 无匹配）:
    ↓ 回退路径
    子块泛检索（type=车源 AND level=child，topK=5，阈值 0.5）
    + 百科/话术补充（type≠车源，topK=3，阈值 0.5）
    → "20万以内的混动SUV""怎么砍价"等非具体车系问题走此路径

  若 hitSeries 非空（主路径）:
    补充 EntityResolver 命中但相似度未搜到的父块（按 series_id 精准查）
    ↓
    阶段二 — 命中车系子块全量展开（topK=20）
      每个 hitSeries 按 parent_series_id 拉全部子块，sku_id 去重
      → LLM 看到该车系完整车型线，不依赖相似度抽奖
    ↓
    不注入百科/话术（有车系锚点时通用知识是噪音）
```

**上下文注入顺序：** 匹配车系（父块）→ 在售车型（子块）→ 相关知识（仅回退路径）

**三个设计决策（grill 对齐）：**

| # | 决策 | 选择 |
|---|------|------|
| 1 | 父块匹配置信度门槛 | 阈值 0.6——top-1 父块低于此值视为"非车系问题"，走泛检索。避免"20万以内的混动SUV"被硬拉到单个车系 |
| 2 | 命中多车系时子块展开范围 | 全量展开——每个命中车系的子块全拉。当前规模每车系≤5款，token 可控；对比问题需要完整车型线 |
| 3 | 有车系命中时是否注入百科/话术 | 不注入——有车系锚点时通用知识与具体车系拼合可能产生幻觉（如 Model Y 颜色+选色通用原则→误关联） |

**与旧版（初版 #4）的核心差异：**
- 父块从"被排除、后补"变为"搜索主入口"——用户问车系的概率远大于问具体配置
- 子块从"依赖相似度 topK 运气"变为"命中车系后全量拉取"——消除前后回答矛盾
- 百科/话术从"永远参与"变为"仅回退路径注入"——减少噪音
- 模糊语义（品牌级匹配、VagueQueryRouter）明确划给阶段三，不在此处硬塞

### 核查决策：三 Collection / 蓝绿暂缓

两项暂缓已在 [issue #1 评论](https://github.com/cheng180/java-spring-ai-agent/issues/1#issuecomment-5111939726) 记录：
- **三 Collection 拆分**：`SkuVectorUpdater` 已精准按 metadata 删除/写入，"更新互相干扰"动机消解；留待阶段四混合检索时顺手拆
- **蓝绿切换**：MQ 广播驱动下无批量重建场景；仅全库重 embedding 时有用，届时再实现

### 测试

**55 → 55 测试**（54 通过 + 1 已知 Spring context error，需 Chroma + API Key）

| 测试类 | 数量 | 变更 |
|--------|------|------|
| `EntityResolverTest` | 14 | **新增**：别名归一、token 回退、去重、空安全 |
| `DatabaseInitializerTest` | 5 | 无变更 |
| `AskCountTrackerTest` | 10 | 无变更 |
| `SkuVectorUpdaterTest` | 6 | 无变更 |
| `SeriesParentBuilderTest` | 6 | 无变更 |
| `KnowledgeFactsTest` | 4+5+4 | 无变更 |
| `AiApplicationTests` | 1 | 已知 fail（需 Chroma） |

---

## 待办：下一阶段路线图

### 优先级排序（建议）

```
已完成的  → 阶段一（知识库框架重构）✅
           ├── 父子文档 + 哈希增量 + 热度 + 两阶段检索 ✅
           ├── EntityResolver + 文档级增量 ✅
           └── 三 Collection / 蓝绿 → 暂缓

建议下一步 → 阶段二（IP定位 + 门店匹配 + 动态热度）
           ├── GeoLocator 接口（先假数据，后期换 IP 定位服务）
           ├── StoreLocator（Haversine 最近门店）
           ├── HotCarRepository（门店×车系热度）
           └── DynamicKeywordBuilder（从 entity_mapping 构建关键词表）
               ↑ 直接吃 EntityResolver 的 entity_mapping 数据

可插队    → 多问题拆分（Tier 1 #7 + Tier 2 #8）
           │  独立模块，不依赖阶段二，改动量小
           │  GitHub ticket 已建，ready-for-agent

后续      → 阶段三（VagueQueryRouter 模糊语义路由）
           └── 依赖阶段二的动态关键词表 + 阶段一的知识库

后续      → 阶段四（BM25 + RRF + BGE-Reranker 混合检索）
           └── 依赖三 Collection（届时顺手拆分）
```

### GitHub Issue 全景

| # | 标题 | 状态 |
|---|------|------|
| #1 | 智能客服系统优化方案（知识库+路由+混合检索） | OPEN — 阶段一完成，阶段二~四待拆 ticket |
| #2 | 多问题拆分+知识库增量更新 | OPEN — 知识库侧完成，多问题侧 #7/#8 待做 |
| #3 | AI幻觉防御三层体系 | OPEN — 未开始 |
| #4 | 检索重构：子块召回+父块确定性展开 | ✅ 完成 |
| #5 | EntityResolver：实体归一化运行时 | ✅ 完成 |
| #6 | 百科/话术文档级增量更新 | ✅ 完成 |
| #7 | 多问题拆分 Tier 1：规则拆分+串行+LLM合并 | OPEN — ready-for-agent |
| #8 | 多问题拆分 Tier 2：LLM兜底+指代消解 | OPEN — blocked by #7 |

### 阶段二待拆 ticket（尚未创建 GitHub issue）

基于 `智能客服优化方案规格.md` 决策6/7/8：

| Ticket | 组件 | 说明 |
|--------|------|------|
| GeoLocator | `location/GeoLocator.java` 接口 | IP→经纬度，先返回固定值（杭州未来科技城），后期换 IP 定位服务 |
| StoreLocator | `location/StoreLocator.java` | Haversine 公式最近门店，读 `store_config` 表 |
| HotCarRepository | `location/HotCarRepository.java` | 门店×车系热度，先读 `store_car_hot` 假数据 |
| DynamicKeywordBuilder | `config/DynamicKeywordBuilder.java` | 启动时从 `entity_mapping` + `car_sku` 构建动态关键词表，新增车系不需改代码 |
| store_car_hot 表 | `DatabaseInitializer.createTables()` | 新增 DDL + 种子假数据 |

---

## 环境

- **ChromaDB**：容器 `chromadb`，端口 8001
- **SQLite**：`company_inventory.db`（启动自动同步 CSV + 建表）
- **API Key**：`start.sh` 中 `SILICONFLOW_API_KEY`
- **Javamvn**：Spring Boot 4.1.0 + Spring AI 2.0.0 + Java 17

---

## 历史记录

### 2026-07-28（阶段一前半）：知识库增量 + 热度

**Blocked by:** #2

| 新增文件 | 职责 |
|---------|------|
| `knowledge/update/ChangeType.java` | INSERT / UPDATE / DELETE 枚举 |
| `knowledge/update/SkuChangeEvent.java` | 不可变事件模型（skId + type + changedFields），防御性拷贝 |
| `knowledge/update/SkuChangeListener.java` | MQ 接收接口 `onSkuChanged(event)`，后续对接真实 MQ |
| `knowledge/update/SkuVectorUpdater.java` | @Component：单条 SKU 增删改 Chroma 同步直写 + `vector_sync_log` 管理 + 级联父块哈希检查 |
| `controller/KnowledgeRefreshController.java` | `POST /api/kb/sku/refresh?skuId=` 手动触发 |

| 重构文件 | 变更 |
|---------|------|
| `CarSkuVectorIndexer.java` | 全量删光重建 → 委托 `SkuVectorUpdater.syncChangedSkus()` 做哈希增量对比 |
| `SkuFactExtractor.java` | `toChildFact()` / `buildEntityId()` 改为 public（跨包访问） |

**更新链路：**
```
启动 / POST refresh → syncChangedSkus()
  → car_sku 行哈希 vs vector_sync_log
  → 只重建变化行 + 清理已删除 SKU
  → 级联父块哈希检查 → 变化则重建父块
```

### Ticket #4 — 热度系统：询问追踪 + 时间衰减 ✅ 完成

**Blocked by:** #2

| 新增文件 | 职责 |
|---------|------|
| `knowledge/hotness/AskCountTracker.java` | @Component：`recordMention(seriesKey)` 当前周桶 UPSERT +1；`getWeightedHeat(seriesKey)` 8周衰减加权；`pruneOldData()` 清理超8周数据 |

**时间衰减系数（8 周）：** `[1.0, 0.8, 0.6, 0.4, 0.25, 0.15, 0.08, 0.03]`

**热度公式：** `heat = Σ(weekly_ask_count × decay[week]) + 在售车型数`

| 重构文件 | 变更 |
|---------|------|
| `SeriesParentBuilder.java` | 注入 `AskCountTracker`；父块内容含热度行；`buildAllParents()` 按热度降序 |
| `CarSalesAgent.java` | 注入 `AskCountTracker` + `JdbcTemplate`；`matchSeries()` 匹配 DB 车系名并追踪询问 |

### Bug 修复

| 问题 | 修复 |
|------|------|
| 启动报 `EmptyResultDataAccessException` | `SkuVectorUpdater.syncChangedSkus()` 中 `queryForObject` → `queryForList` 空结果安全处理 |
| `SkuChangeEvent` 未做防御性拷贝 | 构造时 `new HashMap<>(changedFields)` + `Collections.unmodifiableMap` |
| `SeriesParentBuilder` Javadoc 警告 | 变量名修复 |

### Agent 对话增强

| 改动 | 说明 |
|------|------|
| **JSON 对话日志** | `logConversation()` 输出完整 JSON：`{ts, userId, msg, reply, idleCount, isIdle, terminated, matched}` —— 替代旧的 `length=xxx` |
| **闲聊误判修复** | `isIdleChat()` 现在同时检查静态 `CAR_KEYWORDS` 和 DB 品牌/车系名；消息命中任一车系名即非闲聊。解决"杭州猛禽那台车能开增票吗？""卖得最好的车"等误判 |
| **`matchSeries()` 统一匹配** | 替代旧的 `trackMentions()`，同时服务于闲聊判断和热度追踪 |

### 数据库启动同步

| 改动 | 说明 |
|------|------|
| `INSERT INTO` → `INSERT OR IGNORE INTO` | car_sku 和 store_config 每次启动都跑 CSV 同步，已有 ID 跳过，新增自动插入 |
| 去掉 `if count>0 skip` 守卫 | 不再跳过种子数据加载，启动日志打印 `启动前 X 条 → 启动后 Y 条（新增 Z 条）` |

### 测试

**总计 40/41 通过**（`AiApplicationTests.contextLoads` 是预存的 Spring 上下文加载失败，需 Chroma + API Key）

| 测试类 | 数量 | 说明 |
|--------|------|------|
| `SkuVectorUpdaterTest` | 6 | 事件处理、防御性拷贝、upsert 写入 |
| `AskCountTrackerTest` | 10 | UPSERT、加权热度、过期清理、ISO 周格式 |
| `SeriesParentBuilderTest` | 6 | 新增热度内容断言、父块统计 |
| 已有测试 | 18 | AtomicFact、SkuFactExtractor、LlmFactExtractor、DatabaseInitializer |

---

## 未完成

### 多问题拆分（QuestionSplitter + AnswerMerger）

属于同一份规格中的 Agent 层功能，不属于知识库。按需后续实施：

| 模块 | 说明 |
|------|------|
| `QuestionSplitter` | Tier 1 正则拆分（标点/换行/连接词）+ Tier 2 LLM 兜底 + 指代消解 |
| `AnswerMerger` | N 段子答案 LLM 合并为自然回复，不含"第X个问题" |
| `CarSalesAgent` 集成 | `chat()` 入口插入拆分 → 串行处理 → 合并 |

---

## 环境

- **ChromaDB**：容器 `chromadb`，端口 8001
- **SQLite**：`company_inventory.db`（启动自动同步 CSV）
- **API Key**：`start.sh` 中 `SILICONFLOW_API_KEY`

---
---
## 历史记录

### 2026-07-28（阶段一）：知识库重构 + 增量更新

### Ticket #1 — 数据库扩展：种子数据 + 支撑表 ✅ 完成

**Blocked by:** 无

| 文件 | 变更 |
|------|------|
| `src/main/resources/data/car_sku.csv` | 47 → 105 条，新增特斯拉/理想/小鹏/蔚来/领克/极氪/长安/长城/丰田/吉利等（IDs 100-158），覆盖 31 品牌 / 66 车系 |
| `src/main/java/.../config/DatabaseInitializer.java` | 新增 `vector_sync_log`、`series_ask_count`、`entity_mapping` 三表 DDL + `seedEntityMapping()` 自动去重填充（中英文别名） |
| `src/test/.../config/DatabaseInitializerTest.java` | 5 测试：表读写、UPSERT、别名、去重、衰减系数 |

### Ticket #2 — 分块策略：父子文档结构 + 原子事实提取 ✅ 完成

**Blocked by:** #1

| 新增文件 | 职责 |
|---------|------|
| `knowledge/facts/TemporalType.java` | ATEMPORAL / STATIC / DYNAMIC 枚举 |
| `knowledge/facts/AtomicFact.java` | 原子事实模型（Builder + 不可变 metadata + SHA-256） |
| `knowledge/facts/SkuFactExtractor.java` | SQL+模板渲染：一条 SKU → 子块 AtomicFact |
| `knowledge/facts/SeriesParentBuilder.java` | SQL 聚合父块：车型数/价格区间/能源/颜色/列表 |
| `knowledge/facts/LlmFactExtractor.java` | LLM 命题提取（替代 TokenTextSplitter 盲切） |

| 重构文件 | 变更 |
|---------|------|
| `CarSkuVectorIndexer.java` | 接入 SkuFactExtractor + SeriesParentBuilder，输出父子文档（`level=child/parent` metadata） |
| `KnowledgeBaseInitializer.java` | 用 LlmFactExtractor 替代 MarkdownDocumentReader + TokenTextSplitter |

**分块策略：**

| 数据源 | 策略 | 粒度 |
|--------|------|------|
| `car_sku` 子块 | SQL+模板渲染，不用 LLM，不用切分器 | 一条 SKU = 一个原子事实 |
| `car_sku` 父块 | SQL GROUP BY + 百科匹配 + 询问次数占位 | 一个车系 = 一个父块 |
| `.md` 百科 | LLM 命题提取 | 一条命题 = 一个原子事实 |
| `.txt` 话术 | LLM 命题提取 | 一条命题 = 一个原子事实 |

**测试：** 23 个全部通过（5 Ticket #1 + 18 Ticket #2）

---



### Ticket #3 — 增量更新：哈希检测 + MQ Mock + 级联刷新 ❌ 未开始

**Blocked by:** #2

| 组件 | 职责 |
|------|------|
| `SkuChangeListener` 接口 | 接收 MQ 车源变更（首次 Mock） |
| `SkuChangeEvent` 模型 | skuId + ChangeType(INSERT/UPDATE/DELETE) + changedFields |
| `SkuVectorUpdater` | 单条 SKU 增删改 Chroma（同步直写，不攒批） |
| 哈希增量启动 | 启动时 car_sku 行哈希 vs `vector_sync_log` → 只重建变化行 |
| 级联父块检查 | 子块变更 → 哈希对比 → 父块车型列表/销量变则重建 |
| `KnowledgeRefreshController` | `POST /api/kb/sku/refresh` |

### Ticket #4 — 热度系统：询问追踪 + 时间衰减 ❌ 未开始

**Blocked by:** #2

| 组件 | 职责 |
|------|------|
| `AskCountTracker` | `recordMention(seriesName)` → `series_ask_count` 周桶 +1（UPSERT） |
| 时间衰减 | 8 周系数：`[1.0, 0.8, 0.6, 0.4, 0.25, 0.15, 0.08, 0.03]` |
| 热度公式 | `heat = Σ(weekly_ask_count × decay[week]) + sale_count` |
| 父块热度集成 | `SeriesParentBuilder.buildParent()` 写入加权热度 |

---

## 环境变更

- **ChromaDB**：容器 `chromadb` 已启动（`--restart=always`，端口 8001）
- 旧容器 `chroma` 已清理

---

## 未修改的现有文件

`CarSalesAgent.java`、`CarSalesTools.java`、`PromptTemplates.java`、`AiConfig.java`、`ChatController.java`、`application.properties` — 均未改，Agent 仍用单 `QuestionAnswerAdvisor` 检索。

---

## 历史记录

*以下为 2026-07-23 ~ 2026-07-24 首次实施记录，保留供参考。*

### 一句话概括

把 8 份销售语料 + 47 条公司真实车源写入 Chroma，注入 `CarSalesAgent` 对话链路——工具查实时车源、向量库补百科/话术，LLM 综合回答。

### 新增文件（历史）

| 文件 | 作用 |
|---|---|
| `src/main/resources/knowledge/*.md/*.txt` | 8 份知识库语料 |
| `src/main/resources/data/car_sku.csv` | 47 条真实车源（原版） |
| `scripts/parse_navicat_db.py` | MySQL dump → CSV 解析工具 |
| `controller/KnowledgeDebugController.java` | `GET /api/kb/search?q=xxx` 检索调试 |

### 修改的文件（历史）

| 文件 | 改动 |
|---|---|
| `pom.xml` | +3 依赖：chroma、markdown-reader、vector-store-advisor |
| `application.properties` | Chroma 127.0.0.1:8001、硅基流动 bge-m3 |
| `DatabaseInitializer.java` | 建 car_sku 表（对齐公司库）、从 CSV 加载 |
| `CarSalesTools.java` | 从旧 inventory 改为 car_sku |
| `CarSalesAgent.java` | VectorStore + QuestionAnswerAdvisor（topK=5） |
| `PromptTemplates.java` | 知识库上下文使用规则 |

### Spring AI 1.x → 2.0 迁移

| # | 1.x | 2.0 |
|---|-----|-----|
| 1 | `spring-ai-markdown-document` | `spring-ai-markdown-document-reader` |
| 2 | `new TokenTextSplitter(...)` | `TokenTextSplitter.builder()...` |
| 3 | `new MarkdownDocumentReader(resource)` | `new MarkdownDocumentReader(resource, config)` |
| 4 | base-url 自动拼 `/v1` | 必须显式带 `/v1` |

### 8000 端口冲突

Windows 8000 被 Docker/VMware/WSL 三进程抢占。解决：Chroma 映射 8001，后续避用 8000。

### 配置要点

```properties
spring.ai.openai.api-key=${SILICONFLOW_API_KEY}
spring.ai.openai.base-url=https://api.siliconflow.cn/v1
spring.ai.openai.embedding.options.model=BAAI/bge-m3
spring.ai.vectorstore.chroma.client.host=http://127.0.0.1
spring.ai.vectorstore.chroma.client.port=8001
spring.ai.vectorstore.chroma.collection-name=car-sales-kb
spring.ai.vectorstore.chroma.initialize-schema=true
spring.ai.model.chat=deepseek
```

### 启动步骤

```bash
docker start chromadb
export SILICONFLOW_API_KEY=sk-xxx
./mvnw spring-boot:run
# http://localhost:8080/ 聊天
# GET /api/kb/search?q=xxx 调试
```