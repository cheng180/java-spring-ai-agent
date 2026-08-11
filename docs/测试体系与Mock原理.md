# 测试体系与 Mock 原理——本项目如何"模拟运行"整个智能客服栈

> 本文回答两个问题：
> 1. **Mock 的原理**——假的组件凭什么能代替真的 LLM / 向量库 / HTTP API 参与测试？
> 2. **Java 测试为什么能"模拟运行"**——不起 Chroma、不花 token、不联网，测试凭什么能验证一个依赖这么多外部系统的智能客服？
>
> 全文所有例子均取自本仓库真实代码（`src/test/java` 与 `scripts/`）。

---

## 1. 根本原理：测试能模拟运行，靠的是"依赖可替换"

### 1.1 测试的本质

测试不是"运行了一遍系统"，而是**在同 JVM 里运行的一段验证程序**。JUnit 做的事很朴素：

```
启动 JVM → 加载被测类 → 构造对象 → 调方法 → 断言返回值符合预期
```

没有任何魔法。真正的难点不在"怎么跑"，而在**被测代码依赖的东西跑不起来**：

| 生产依赖 | 测试里的问题 |
|---|---|
| DeepSeek / Anthropic / OpenAI 大模型 API | 要 key、花钱、每次输出不确定、慢 |
| Chroma 向量库（`172.21.10.248:8001`） | 要先 `docker compose up`，打包机没有 |
| positionstack 地理编码 HTTP API | 免费档限速 2 req/s，无网环境直接失败 |
| 预置数据的 `company_inventory.db` | 打包机上不存在 |

`AiApplicationTests` 就是这个困境的活标本——它加载完整 Spring 上下文，因此被 `@Disabled`：

```java
// src/test/java/org/example/ai/AiApplicationTests.java
@SpringBootTest
@Disabled("需要完整运行环境（内网 Chroma / 预置 SQLite / API key），打包机上必然超时失败；业务逻辑由其余单元测试覆盖")
class AiApplicationTests { void contextLoads() {} }
```

**这个被禁用的测试恰好解释了其余 30+ 个测试为什么存在：为了让验证不依赖完整环境，必须把外部依赖换成可控的假货。**

### 1.2 让替换成为可能的机制：接缝（Seam）+ 依赖注入

"模拟"的前提是生产代码留了**可替换的接缝**——外部依赖不从内部 new 出来，而是从构造器/参数传进来。本项目有两种接缝形态：

**形态一：接口/对象注入（Spring DI 风格）**

```java
// RetrievalContextAssembler 的依赖全部从构造器进入：
//   HybridRetriever、VectorStore、AskCountTracker、JdbcTemplate……
// 生产环境由 Spring 注入真实现；测试里想换谁就换谁。
```

**形态二：函数式接缝（最彻底的解耦）**

```java
// src/test/java/org/example/ai/impl/location/PositionStackGeoLocatorTest.java
private PositionStackGeoLocator locator(String apiKey, String responseBody) {
    return new PositionStackGeoLocator(apiKey, BASE, url -> responseBody);
    //                                                 ↑ httpGet 是个函数参数
}
```

`PositionStackGeoLocator` 把"发 HTTP 请求"抽象成一个 `url -> 响应体` 的函数。生产构造器传入真实的 JDK HttpClient 调用；测试构造器传入一个 **lambda**——网络层整个消失了，测试想给什么响应就给什么响应：

```java
locator("test-key", """
    {"data":[{"latitude":39.9042,"longitude":116.4074,"name":"Beijing","country":"China"}]}
    """).locateByCity("北京");                      // 正常解析
locator("test-key", "not-a-json").locateByCity("北京");  // 非法 JSON → null
new PositionStackGeoLocator("test-key", BASE,
    url -> { throw new IllegalStateException("network down"); });  // 模拟断网 → null
```

**结论：测试能"模拟运行"，不是测试技术有多神奇，而是生产代码把"不可控部分"隔离到了接缝之外。** 这也是为什么新组件设计时就要考虑可测性（#16-T1 的规格里明确要求 seam）。

---

## 2. Mock 的原理：测试替身五件套

"Mock" 在日常口语里泛指一切假依赖，严格来说是一个光谱（Meszaros 分类）：

| 替身类型 | 作用 | 本项目实例 |
|---|---|---|
| **Dummy** | 只占位、从不被调用 | 少用（Mockito 可生成） |
| **Fake** | 轻量真实实现，行为与生产等价 | **临时 SQLite 文件库**（见 §4.2） |
| **Stub** | 预编程应答："被问 X 就答 Y" | `when(vectorStore.similaritySearch(...)).thenReturn(docs)`；lambda `url -> responseBody` |
| **Spy** | 真实现 + 记录调用 | Mockito `spy()`（本项目少用） |
| **Mock** | 预编程应答 + **验证是否被按预期调用** | `verify(hybridRetriever, never()).子块检索(...)` |

关键区别：**Stub 验证"状态"（返回值对不对），Mock 验证"行为"（该调的调了没有、不该调的有没有调）。**

本项目一个典型的行为验证场景：#24 车系级分层要求"SERIES 级别只取父块、禁止子块检索"——这没法从返回值看出来，必须验证调用本身：

```java
// RetrievalContextAssemblerTest（#24 用例）
verify(vectorStore, never()).similaritySearch(argThat(子块过滤条件));
```

### Mockito 底层是怎么做到的

1. **动态生成子类**：Mockito 用 ByteBuddy 在运行时为被 mock 的类生成一个子类（对接口则生成实现类），所有方法被拦截——这也是为什么 `final` 类/方法默认不能被 mock（无法继承覆写）。
2. **打桩（Stubbing）**：`when(mock.method(args)).thenReturn(x)` 把 `(方法, 参数匹配器) → 返回值` 记入内部桩表。
3. **调用分派**：生成的子类拦截到调用后，用参数匹配器（`any()` / `eq()` / `argThat()`）查桩表，命中则返回桩值，未命中返回默认值（null / 0 / false）。
4. **调用录制（Verification）**：每次拦截到的调用都被记录在案；`verify(mock, times(n) / never())` 就是拿录制清单对账。

`@Mock` + `@ExtendWith(MockitoExtension.class)` 只是把"生成 mock 并注入字段"这步自动化了：

```java
// src/test/java/org/example/ai/impl/context/RetrievalContextAssemblerTest.java
@ExtendWith(MockitoExtension.class)
class RetrievalContextAssemblerTest {
    @Mock private HybridRetriever hybridRetriever;   // LLM 检索侧 → 假
    @Mock private VectorStore vectorStore;           // Chroma 向量库 → 假
    @Mock private AskCountTracker askCountTracker;   // 热度统计 → 假
    // JdbcTemplate → 真 SQLite（见下）
}
```

---

## 3. 分层设计：本项目的测试金字塔

```
        ┌────────────────────┐
        │  e2e 红绿脚本       │  scripts/diag-*.sh —— 真应用 + 真 LLM
        ├────────────────────┤
        │  Live 实联调(门控)  │  PositionStackLiveIntegrationTest 等
        ├────────────────────┤
        │  混合组装测试       │  RetrievalContextAssemblerTest
        ├────────────────────┤
        │  单元 + 真 SQLite   │  CarSalesToolsSearchInventoryLimitTest
        ├────────────────────┤
        │  纯单元测试         │  VagueScorerTest / Bm25IndexTest …（最多、最快）
        └────────────────────┘
```

### 3.1 纯单元测试：逻辑即全部依赖

```java
// VagueScorerTest（#41）——表驱动：三层证据判定表逐行断言
scorer = new VagueScorer(new NeedSignalDetector(), 0.7, 0.6, 0.5, 0.15, 0.05);
//                       真实 collaborator（本身无外部依赖） + 与生产一致的阈值
```

注意注释："默认阈值：与生产配置一致（0.7 / 0.6 / 0.5，gap 0.15 / 0.05）"——**测试参数与 `application.properties` 对齐，模拟才有保真度**。毫秒级、无 I/O、数量最多。

### 3.2 Fake 数据库：真 SQL、零外部依赖

本项目数据库本来就是 SQLite（`sqlite-jdbc`），于是测试直接建**临时文件库**：

```java
// CarSalesToolsSearchInventoryLimitTest（#38）
tmpDb = new File(System.getProperty("java.io.tmpdir"),
        "test-inventory-limit-" + UUID.randomUUID() + ".db");
var ds = new SQLiteDataSource();
ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
jdbc = new JdbcTemplate(ds);
jdbc.execute("CREATE TABLE car_sku (...)");
for (int i = 1; i <= 40; i++) { /* 灌 40 条同品牌车源 */ }
```

这是 **Fake 而非 Mock**：SQL 引擎是真的，LIMIT 行为是真的，所以 #38 的验收（"匹配 40 条只返回 30 条上限内"）才有说服力。用随机文件名 + `@AfterEach` 删除，测试间互不污染、可并行。

### 3.3 混合组装：LLM 侧 mock、SQL 侧真实

`RetrievalContextAssemblerTest` 是本项目的集大成者，一个测试里三种替身并存：

```java
@Mock private HybridRetriever hybridRetriever;  // 会调 embedding/LLM → 必须假
@Mock private VectorStore vectorStore;          // Chroma → 必须假
// JdbcTemplate → 真 SQLite 临时库（store_config / series_ask_count / 画像表全建好）
```

**划分原则：贵、慢、不确定的（模型、网络）→ mock；便宜、确定、行为需要真实的（SQL）→ 真跑。** 同一个类里还承载了"黄金锚点"模式：

```java
// UNRESTRICTED 用例锁定迁移前现状行为（字节级零回归守卫）；
// SERIES 用例断言分层后的新行为（单系列父块 + 级别指令，零子块调用）
```

即：重构前先给旧行为拍一张"字节级快照"当锚点，重构后锚点测试必须绿——**模拟的价值不只是验证新功能，更是锁住旧行为不回退**。

### 3.4 Live 实联调：环境变量门控

mock 再像也不是真的，所以保留少量真联调，但用**环境变量门控**做到"无环境自动跳过"：

```java
// PositionStackLiveIntegrationTest（#16-T4）
@EnabledIfEnvironmentVariable(named = "POSITIONSTACK_API_KEY", matches = ".+")
class PositionStackLiveIntegrationTest { /* 只发 1 个真实请求（限速 2 req/s） */ }

// ThresholdCalibrationLiveTest（#40）
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_CALIBRATION", matches = "true")
class ThresholdCalibrationLiveTest { /* 真 Chroma + 真 embedding 扫描黄金集 */ }
```

| 测试 | 门控变量 | 真依赖 |
|---|---|---|
| `PositionStackLiveIntegrationTest` | `POSITIONSTACK_API_KEY` | positionstack HTTP |
| `ThresholdCalibrationLiveTest` | `RUN_CALIBRATION=true` | Chroma + BGE-M3 embedding |

这样 `./mvnw test` 在任何机器上都绿（门控测试被跳过），有环境的人手动开闸跑真联调——**确定性与真实性分层共存**。

### 3.5 Shell 红绿 e2e：进程级的"模拟/真实"开关

`scripts/diag-vague-loop.sh`（#43）是最外层的端到端验收，三态判定：

```
RED        (exit 1) = 行为回归（引导未注入/误注入、重复问城市、画像未落库）
GREEN      (exit 0) = 模糊场景行为符合预期
LOOP-ERROR (exit 2) = 环境问题（应用未启动、HTTP 异常），非行为判定
```

它打的是**真应用 + 真 LLM**（先 `docker compose up` 起 Chroma）。当环境不可用时（如当前开发机无 Chroma），验证策略就退一层：**用 mock 服务器代替真应用**来验证脚本自身的判定逻辑（见 §4.3）。

---

## 4. HTTP 层的 Mock：三个粒度

同一个 positionstack/聊天接口，本项目示范了三种粒度的 HTTP mock：

### 4.1 函数级（§1.2 的 lambda 接缝）

替换发生在**被测对象内部**——HTTP 调用动作本身被参数化。最轻，覆盖所有响应分支（正常/空/错误/断网/非法 JSON），无需任何端口。

### 4.2 客户端级（MockMvc）

`pom.xml` 引入了 `spring-boot-starter-webmvc-test`（含 MockMvc）。MockMvc 不起真服务器，在进程内直接调 DispatcherServlet，验证 Controller 路由/序列化。本项目暂未使用（Web 层薄，e2e 脚本直接打真端口）。

### 4.3 服务器级（mock server）

`diag-vague-loop.sh` 的判定逻辑验证用的手法：**真起一个监听端口的假服务器**（如 python socket / `nc`），返回预编排响应，检验脚本在"应用返回 X"时是否输出正确 VERDICT：

```
mock 服务器（假装是 /api/chat）  ←——  e2e 脚本（被测对象变成脚本本身）
```

这是"对契约另一侧整体建模"：不关心对方内部怎么算，只按契约吐字节。**#43 当前调试的 GBK 问题正出在这一层**——mock 收到 GBK 字节而非 UTF-8，根因是 Git Bash 把 argv 传给原生 `curl.exe` 时会按 GBK 重编码中文；脚本的生产路径已用 `--data-binary @文件` 规避（body 走临时文件不进 argv），验证工装也要同样处理。

---

## 5. 保真度边界：mock 能担保什么、不能担保什么

| mock 能担保 | mock 担保不了 |
|---|---|
| 契约等价：调用参数、次数、返回值形状 | 真实网络的超时/限速/断连（→ Live 测试补） |
| 逻辑分支全覆盖（含构造出的极端输入） | 真实模型的输出内容与波动（→ e2e 红绿脚本补） |
| 确定、快、可重复（CI 可跑） | 真实数据规模下的性能（→ 黄金集标定补） |
| 旧行为不回退（黄金锚点） | 环境配置正确性（→ LOOP-ERROR 态区分） |

本项目对边界的处理可以总结成三条纪律：

1. **能 mock 的尽量 mock**（30+ 单元/组装测试构成日常防线，`./mvnw test` 随处可跑）；
2. **mock 不到的留门控 Live 出口**（环境变量开闸，不污染 CI）；
3. **最终行为用红绿脚本对着真栈验收**（RED/GREEN 锁行为，LOOP-ERROR 把"环境问题"从"行为回归"里剥离出来，避免假阳性）。

---

## 6. 运行速查

```bash
./mvnw test                                   # 全量（门控测试自动跳过）
./mvnw test -Dtest=VagueScorerTest            # 单测试类（开发循环常用）
./mvnw test -Dtest='CarSales*'                # 通配

# 开闸 Live 测试：
POSITIONSTACK_API_KEY=<key> ./mvnw test -Dtest=PositionStackLiveIntegrationTest
RUN_CALIBRATION=true DEEPSEEK_API_KEY=<key> SILICONFLOW_API_KEY=<key> \
    ./mvnw test -Dtest=ThresholdCalibrationLiveTest    # 需先 docker compose 起 Chroma

# e2e 红绿脚本（需真环境）：
PORT=8080 DB_FILE=company_inventory.db bash scripts/diag-vague-loop.sh
```

## 7. 文件地图

| 层 | 代表文件 | 演示的模式 |
|---|---|---|
| 纯单元 | `impl/routing/VagueScorerTest.java` | 表驱动 + 阈值与生产对齐 |
| 单元+Fake DB | `impl/tool/CarSalesToolsSearchInventoryLimitTest.java` | 临时 SQLite 灌数据锁 LIMIT |
| 函数接缝 stub | `impl/location/PositionStackGeoLocatorTest.java` | lambda 替换 HTTP |
| 混合组装 | `impl/context/RetrievalContextAssemblerTest.java` | Mockito mock + 真 SQLite + 黄金锚点 + 行为验证 |
| Live 门控 | `impl/location/PositionStackLiveIntegrationTest.java`、`calibration/ThresholdCalibrationLiveTest.java` | `@EnabledIfEnvironmentVariable` |
| 全栈冒烟(禁用) | `AiApplicationTests.java` | 反面教材：完整上下文的代价 |
| e2e 红绿 | `scripts/diag-vague-loop.sh` | RED/GREEN/LOOP-ERROR + mock 服务器验证 |
