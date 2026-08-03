# Matt Pocock Skills 完整使用指南

> 针对项目：汽车销售 AI 客服（`cheng180/java-spring-ai-agent`）
> 配置文件：`docs/agents/` + `CLAUDE.md`

---

## 一、需求 → 代码工作流（核心链路）

```
想法 → wayfinder(大任务拆解) → to-spec(写规格书) → to-tickets(拆ticket) → tdd(测试驱动) → implement(实现) → code-review(审查)
```

### 1. `/wayfinder` — 大规模任务规划

- **什么时候用**：你说"我想给项目加个多租户经销商后台"，需求大到一次对话搞不完。
- **做什么**：在 GitHub Issues 创建一张 Map（母 issue）+ 一堆决策子 ticket，逐个解决。每个子 ticket 回答一个决策问题（"用哪种多租户方案？""经销商数据怎么隔离？"），最终产出一份清晰的路线图，不是直接写代码。
- **触发方式**：手动 `/wayfinder`
- **实际场景**：AI 客服要接新渠道（抖音私信）、要加订单管理——这种大需求用它。

---

### 2. `/to-spec` — 对话转规格书

- **什么时候用**：和我讨论了半天"加个客户画像功能"，讨论清楚了。
- **做什么**：将对话内容合成一份 PRD 规格书，自动发到 GitHub Issues，打上 `ready-for-agent` 标签（AI 可执行）。
- **触发方式**：手动 `/to-spec`
- **实际场景**：讨论完任何新功能后，用它沉淀为正式规格，避免"聊完就忘了"。

---

### 3. `/to-tickets` — 规格书拆成开发 Ticket

- **什么时候用**：有了一份 spec 之后。
- **做什么**：把 spec 拆成一系列小 ticket，每个 ticket 是**垂直切片**（一个完整的小功能），不是水平分层（先 DAO 再 Service 再 Controller）。每个 ticket 标注阻塞关系（ticket-02 依赖 ticket-01 先完成）。
- **触发方式**：手动 `/to-tickets`
- **实际场景**：规格书里"客户画像"功能被拆成 3-5 个独立可测的 ticket，标好依赖链。

---

### 4. `/tdd` — 测试驱动开发

- **什么时候用**：要写新功能或修 bug，想测试先行。
- **做什么**：红 → 绿 → 重构循环。先写失败测试 → 最小实现让它通过 → 重构。决定测试放在哪个 seam（公共接口边界），不测内部实现细节。
- **触发方式**：说"TDD"或"red-green-refactor"自动触发，也可手动 `/tdd`
- **实际场景**：给 `CarSalesTools.java` 加一个"按价格区间筛选"的方法，先写测试验证 `searchByPriceRange(20, 30)` 返回正确结果，再实现。

---

### 5. `/implement` — 按 Ticket 执行实现

- **什么时候用**：ticket 已经拆分好，准备动手。
- **做什么**：按 ticket 顺序逐个实现，自动配合 `/tdd`，每做完一个跑测试，最后跑 `/code-review`，完成后 commit。
- **触发方式**：手动 `/implement`
- **实际场景**：自动化执行"读取 ticket → TDD 实现 → 审查 → 提交"全流程。

---

### 6. `/code-review` — 双轴代码审查

- **什么时候用**：改完代码想审查，或 PR 提交前检查。
- **做什么**：两个并行子 agent：
  - **Standards 轴**：检查代码是否符合项目规范、命名、风格
  - **Spec 轴**：检查代码是否忠实实现了对应的 issue/spec
- **触发方式**：说"review"或手动 `/code-review`
- **实际场景**：改完 `CarSalesAgent.java` 后审查，检查是否符合 `CONTEXT.md` 术语、是否与已有 ADR 冲突。

---

## 二、代码质量

### 7. `/diagnosing-bugs` — 诊断疑难 Bug

- **什么时候用**：报错/坏了/抛异常/慢。
- **做什么**：结构化诊断。Phase 1 建反馈回路（写复现测试），Phase 2 二分定位，Phase 3 修复。
- **触发方式**：说"diagnose""debug this""broken""throwing""failing""slow"**自动触发**
- **实际场景**：用户说"查奥迪A4L 返回空"，用它先写复现测试，再定位是 SQL 还是向量检索的问题。

---

## 三、架构与设计

### 8. `/codebase-design` — 深度模块设计

- **什么时候用**：设计新模块接口，或重构现有模块。
- **做什么**：引入精确设计词汇——**Module**（模块）、**Interface**（接口）、**Depth**（深度 = 接口小但能力多）、**Seam**（可测试边界）。判断模块是"深"（好）还是"浅"（差）。
- **触发方式**：手动 `/codebase-design`
- **实际场景**：评估 `KnowledgeBaseInitializer` 和 `CarSkuVectorIndexer` 的接口设计——调用方只需 `indexer.indexAll()` 一行就完成全部工作，这是深模块。

---

### 9. `/improve-codebase-architecture` — 架构扫描 + 重构报告

- **什么时候用**：项目跑了一段时间，感觉代码越来越乱。
- **做什么**：扫描代码库，找出"浅模块"（接口复杂但实际没做多少事），生成 HTML 可视化报告，指出哪里需要"加深"（让代码更可测试、AI 更易理解）。
- **触发方式**：手动 `/improve-codebase-architecture`
- **实际场景**：可能发现 `CarSalesTools.java` 里多个 `@Tool` 方法混在一起，建议按"车辆查询 / 库存查询 / 门店查询"拆分成独立模块。

---

### 10. `/domain-modeling` — 领域建模（维护 CONTEXT.md + ADR）

- **什么时候用**：需要定义术语、记录架构决策。
- **做什么**：主动挑战你用的概念，写进 `CONTEXT.md`（术语表）和 `docs/adr/`（架构决策记录），后续所有 skill 自动使用统一术语。
- **触发方式**：手动 `/domain-modeling`
- **实际场景**：讨论"车源"和"车系"的区别、"上架"和"在售"是不是一回事——写成正式术语，写入 `CONTEXT.md`。

> **注意**：`/domain-modeling` 是**产出**领域模型的 skill；其他 skill（`diagnosing-bugs`、`tdd` 等）只是**读取** `CONTEXT.md` 使用术语，不创建。

---

### 11. `/grilling` — 无情追问（计划评审）

- **什么时候用**：有一个想法/方案，想被挑战和压测。
- **做什么**：我一个接一个追问，每次一个问题带推荐答案，逐个击破决策树分支，直到达成共识。
- **触发方式**：手动 `/grilling`
- **实际场景**：你说"想把 Chroma 换成 Milvus"，`/grilling` 会追着问——现在 Chroma 哪里不够用？数据量预计增长多少？Milvus 运维成本你能接受吗？

---

### 12. `/grill-with-docs` — 追问 + 同时产出文档

- **做什么**：`/grilling` + `/domain-modeling` 的组合。追问的同时把决策自动记成 ADR、术语写入 `CONTEXT.md`。
- **触发方式**：手动 `/grill-with-docs`

---

## 四、探索与调研

### 13. `/research` — 调研并输出文档

- **什么时候用**：需要查技术问题，想得到有引用来源的结论。
- **做什么**：后台 agent 去查官方文档 / 源码 / 规范 / 一手资料，产出带引用来源的 Markdown 文件。
- **触发方式**：说"research"或手动 `/research`
- **实际场景**：调研"Spring AI 2.0 是否支持多模态 embedding 混合检索"，读完源码和文档后写报告。

---

### 14. `/prototype` — 快速原型验证

- **什么时候用**：不确定某个设计是否靠谱，想做小实验。
- **做什么**：生成**可丢弃**代码来验证想法：
  - **逻辑验证** → 终端交互程序，走状态机的极端场景
  - **UI 验证** → 生成多个不同设计变体，可切换预览
- **触发方式**：说"prototype"或手动 `/prototype`
- **实际场景**：不确定"多轮对话上下文窗口管理"策略对不对，生成原型跑极端场景验证。

---

## 五、项目管理

### 15. `/triage` — Issue 分类与优先级管理

- **什么时候用**：GitHub Issues 堆积需要整理。
- **做什么**：扫描 issue，走状态机：
  `needs-triage` → `needs-info`（追问模糊描述）→ `ready-for-agent`（AI 可执行）或 `ready-for-human`（需人工）或 `wontfix`（不做）
- **触发方式**：手动 `/triage`
- **实际场景**：有人提 issue"希望支持多语言"，`/triage` 追问"哪些语言？API 还是 UI？"，然后分类打标签。

---

### 16. `/resolving-merge-conflicts` — 解合并冲突

- **什么时候用**：git merge/rebase 冲突时。
- **做什么**：理解冲突双方的原始意图，按目标合并保留，不创造新行为。
- **触发方式**：有冲突时说"resolve conflicts"或手动调用

---

## 快速速查：什么时候用什么

| 场景 | Skill | 触发方式 |
|---|---|---|
| 有大需求不知道怎么下手 | `/wayfinder` | 手动 |
| 讨论完了，沉淀成文档 | `/to-spec` | 手动 |
| 有规格书，要拆任务 | `/to-tickets` | 手动 |
| 开始写代码（测试先行） | `/tdd` | 关键词/手动 |
| 按 ticket 自动实现 | `/implement` | 手动 |
| 代码写完了，审查 | `/code-review` | 关键词/手动 |
| 报错 / 坏了 / 慢 | `/diagnosing-bugs` | **自动触发** |
| 设计模块接口 | `/codebase-design` | 手动 |
| 项目乱，想重整架构 | `/improve-codebase-architecture` | 手动 |
| 确定术语、记架构决策 | `/domain-modeling` | 手动 |
| 方案需要被挑战 | `/grilling` | 手动 |
| 追问 + 同步产出文档 | `/grill-with-docs` | 手动 |
| 调研技术问题 | `/research` | 关键词/手动 |
| 快速验证想法 | `/prototype` | 关键词/手动 |
| 整理 GitHub Issues | `/triage` | 手动 |

---

## 配置文件（已创建，勿删）

| 文件 | 作用 |
|---|---|
| `CLAUDE.md` | 入口索引，每次对话自动读取 |
| `docs/agents/issue-tracker.md` | GitHub Issues 操作规范 |
| `docs/agents/triage-labels.md` | 5 个默认标签映射 |
| `docs/agents/domain.md` | 领域文档布局规则 |

> 可随时编辑 `docs/agents/*.md` 调整配置。只需在切换 Issue 追踪器或重新开始时重跑 `/setup-matt-pocock-skills`。