# monoGomicro 改进执行计划（执行版 v2）

> 由 2026-10-19 的 GPT 规划稿压缩而来：保留五个方向，按当前分支事实重排优先级。
> 基线：`develop` @ `d47437b` + 未提交的 round-5 收尾；最近记录 **89 单测 + 5 IT**（以 `mvn clean verify` 为准）。
> 图例：`[x]` 已完成并验证；`[~]` 已改但待提交/待补测试；`[ ]` 未开始；`[!]` 需设计评审后再动。

## 0. 执行规则

- 开工前先看 `git status` 和本文件状态表；`[x]` 项不要重做，`[~]` 项只补缺的测试/提交。
- 每个 PR 只做一个 Track；`README.md`、`README.zh-CN.md`、`AGENTS.md`、本计划指定单一 owner，避免并行冲突。
- “完成”= 代码 + 测试 + 文档 + `mvn clean verify`。P0 项没有回归测试不算完成。
- 不在本计划内：事务/数据库/部署自动迁移，业务语义决策，自动改写所有跨服务调用。

## 1. 当前状态（2026-10-20 round-6 收尾后）

| 能力/事项 | 状态 | 证据或缺口 |
|---|---|---|
| 17 个 MCP 工具 / 2 个 resource | `[x]` | mcp-design.md 已同步 17 工具/六模块 |
| RuoYi 生成 → `mvn package` | `[x]` | 12 服务 jar + common 全出（BUILD SUCCESS） |
| 生成服务启动到 DataSource | `[x]` | 7 组桥全 `svc-* -> svc-*`，无 `svc.common` |
| F-01/F-03/F-05/F-07 | `[x]` | 独立回归测试落地，全部先红后绿（附 K） |
| F-04 前端 overwrite/confirm | `[x]` | 代码已修（rethrow + error 字段）；无浏览器 E2E |
| F-06/F-08/F-10/F-11/F-12/F-14 | `[x]` | round-4 + round-5 + round-6 补齐 modules:[null] 测试 |
| F-13 相对 out / 默认路径 | `[x]` | 相对 `out=` IT 落地（AgentRelativeOutIT，红绿验证）；默认 `~/.monogomicro`、symlink 测试仍缺 |
| I-01..I-06 | `[x]` | 工作区已修；I-01 agent 子进程回归已补 |
| F-09 Q 双值 | `[x]` | PartitionQToleranceTest（静态=取整误差级 / 动态=随占比）+ README 双语口径说明 |
| Web / CLI 测试 | `[~]` | web 0→4（Host/Origin 过滤器）；CLI `src/test` 仍空（P1 §3.1） |
| Agent 采集统计 | `[ ]` | `TraceRecorder` 无入队/丢弃/写入失败/关闭计数；零命中不可见 |
| Trace 解析统计 | `[ ]` | `TraceAnalyzer` 无 E/N/X/丢弃/未配对统计 |
| 静态分析 classpath 状态 | `[ ]` | 有 `parseFailures`；`AnalysisReport` 无 classpath 降级原因 |
| 生成前诊断模型 | `[ ]` | 只有 `manualItems` 字符串 + `MIGRATION.md`，无 typed diagnostic |
| 真实架构约束 | `[ ]` | `PartitionOptions` 只有 alpha/beta/min/max/seed/kernel 参数 |
| round-5/6 提交 | `[x]` | 本轮一并提交（英文提交信息，双推 github + nas） |
| 文档真值 | `[x]` | README×2 加 audit/数字对齐；mcp-design 17/六模块/Q=0.77/按工具截断；AGENTS 统一 103+6 |

## 2. P0：0.3.x 发布门禁（只修边角，不加功能）

### 2.1 提交与验证

- [x] 提交 round-5/6 收尾（I-01..I-06、回归测试、报告与 AGENTS），英文提交信息，双推 `github` + `nas`。
- [x] 干净树执行 `mvn -B clean verify`：**103 单测 + 6 IT 全绿**（core 61 / codegen 14 / web 4 / mcp 24；agent 2 + codegen 1 + mcp 3）。
- [x] RuoYi 验收：analyze 290/980 → partition 冻结基线对拍 IDENTICAL → generate（7 桥全 svc→svc / 7 人工项）→ `mvn -DskipTests package`（12 jar + common）→ svc-user 启动停在 `Failed to configure a DataSource`（预期验收线）。
- [ ] 推送后观察 GitHub Actions 首跑；环境失败与代码失败分开记录。

### 2.2 文档真值（单一事实源）

- [x] `README.md` / `README.zh-CN.md`：CLI 架构行与 Homebrew 注释补 `audit`；generate 验收行改为 package/12 jar/启动到数据源。
- [x] `docs/mcp-design.md`：15 → 17 工具；五模块 → 六模块；Q=0.81 → 0.77。
- [x] `AGENTS.md`：计数统一（103 + 6，以最近 verify 为准）；去重「下一步候选」。
- [x] 4KB 决策：选 **B**——删除"≤4KB per tool result"承诺，改为"按工具截断"（audit top20/top10 + truncated 即实例）。
- [x] 安装段保持 0.2.0（Central 实际版本）；发布 0.3.0 后再统一改。
- [x] F-09：容差断言（PartitionQToleranceTest）+ README 双语口径说明（静态=取整误差，动态=随占比，RuoYi 实测 0.002）。

**验收**：全仓不再出现 "15 个工具""Q=0.81"，也不再同时出现"≤4KB"与"硬上限 8KB"的互斥承诺；README 中每条命令可执行。

### 2.3 最小回归（每项先红后绿，红证据见附 K）

- [x] F-01：恶意 `packageName`/`sourcePath` → outDir 外无任何文件（EvilAnalysisGuardTest；红=回退 84c831d 生成器后逃逸复现）。
- [x] F-03：`Origin: http://127.0.0.1:<port>` 200；`https://evil.example` 403；伪造 Host 403（HostOriginFilterTest；红=回退 84c831d 过滤器）。
- [x] F-05：恢复型语法错误 → parseFailures 非空；scan() 复用不累积；全坏文件报 `E_PARSE_FAILED` 而非 E_PKG_NO_MATCH（ProjectScannerParseFailureTest；红=回退 84c831d）。
- [x] F-07：outDir 包含输入文件 → `E_BAD_OUT_DIR` 且输入保留（EvilAnalysisGuardTest；红=同 F-01 回退）。
- [x] F-11：`services:[null]`、`exclude:[null]`、`modules:[null]` 都 `E_BAD_SERVICES_JSON`（ServicesConfigNullElementTest；红=回退 84c831d）。
- [x] I-01：agent `out=rel.log` 子进程 IT → 文件生成、E/N 行齐全、无 writer NPE、0640（AgentRelativeOutIT；红=回退 d47437b TraceRecorder 后 NPE 复现）。
- [x] I-04：接口提升进 common + impl 在消费者服务 → 不生成自桥（BridgeRerouteTest；红=移除同属重判后 `svc-consumer -> svc-consumer` 自桥复现）。
- [x] F-09：容差断言（静态 <0.01 / 动态强化场景 <0.01，A-06 量级回归 0.22 会被抓住）。

**验收**：上述测试在修复前 commit 上失败、当前通过——已按附 K 逐项以单文件回退方式复现红。

### 2.4 CI

- [x] workflow 改为 `mvn -B clean verify`（fresh checkout 下与 verify 等价，显式 clean 与本地门禁一致）。
- [x] codegen IT 缓存隔离：CI 在 verify 后、缓存保存前清理 `~/.m2/repository/com/demo`（生成构件跨 run 残留会掩盖生成缺陷；runner 本身一次性，完整 `-Dmaven.repo.local` 隔离会失去依赖缓存、拖慢每轮 CI，不采用）。
- [x] failsafe/surefire 失败时上传 `target/*-reports`（7 天保留）。
- [ ] 确认 CI 从干净环境跑通（推送后观察首跑）。

## 3. P1：入口契约测试（最高性价比）

### 3.1 CLI

- [ ] 新建 `monogomicro-cli/src/test`（或 failsafe IT），用真实子进程/ picocli 测试。
- [ ] 覆盖：`--version`、`analyze` 缺路径/包前缀、`partition` 非法 alpha/beta/min-size、`audit` 坏 config/services、`generate` dry-run/overwrite/输入目录保护、退出码 0/2。
- [ ] 每个 JSON 产物至少断言顶层字段（classes/edges/communities/metrics/findings）。
- [ ] 用临时项目 + 隔离本地仓库，避免污染 `~/.m2`。

### 3.2 Web

- [ ] 加 `spring-boot-starter-test`；新建 `monogomicro-web/src/test`，MockMvc 为主。
- [ ] 覆盖：未载入 409、load 成功/坏 JSON、state、move 合法/未知类、save 根内/根外、audit、generate overwrite=false 的 400 + `{code,message,hint}`。
- [ ] 安全：Host/Origin（含端口）、saveRoot、protectedInputs（analysis + partition）。
- [ ] 前端交互仅在 UI 仍是核心能力时加最小 E2E；否则不引入浏览器测试栈。

### 3.3 MCP

- [ ] 17 个工具逐个断言 inputSchema/成功输出/结构化错误/状态变化；现有 stdio + HTTP IT 保留。
- [ ] 对**完整序列化 text content** 测字节数，而不是只数 findings/moves；按 4KB 决策加断言。
- [ ] HTTP 补充：无/过期 session 的 body 形状、Host/Origin、非法 JSON、并发 move+resource read。

### 3.4 CI

- [ ] `mvn verify` 真正执行 web/cli 新测试；禁止 `Assumptions` 在缺 mvn 时“绿着跳过”（至少 fail 或显式 skip 原因）。

## 4. P1：可观测性（小改，面试可讲）

### 4.1 Agent

- [ ] 用 `LongAdder` 统计：enqueued / written / dropped(queue full) / writeFailed / remaining；不在业务线程加锁。
- [ ] shutdown：等待时长、是否超时、剩余队列；写失败置 failed 标志并 stderr 明确“采集已停止”。
- [ ] 入口命中：配置的 entry class/method、命中次数；零命中时明确提示“动态用例可能为空”。
- [ ] 测试：队列满、不可写路径、相对 `out=`、关闭超时、入口零命中、坏 trace 行。

### 4.2 TraceAnalyzer

- [ ] 统计 events/E/N/X、丢弃行、未配对事件、useCases、覆盖类数；栈失配采用可恢复策略并计数。
- [ ] CLI `m2m trace` / MCP 输出这些统计；能区分“没有动态调用”与“agent 未命中/轨迹丢失/解析失败”。
- [ ] 保持旧 trace 文件兼容；统计走可选字段。

### 4.3 静态分析

- [ ] `parseFailures` 已有；补 classpath 状态/降级原因（mvn 不可用、jar 数、fallback）到 `AnalysisReport` 与 CLI/MCP。
- [ ] 文档写明“分析完整度”字段语义；不要要求调用方从日志推断。

## 5. P2：生成前诊断（typed model，先设计）

- [ ] 定义 `Diagnostic`：code、severity（blocking-build / runtime-risk / business-decision）、consumer/provider、source、reason、action。
- [ ] 盘点跨界依赖种类（字段/构造器注入、Mapper、继承、静态调用、共享 DTO、泛型、反射/配置）。
- [ ] dry-run 与正式生成复用同一诊断快照；`MIGRATION.md` 由诊断渲染，不另造一套。
- [ ] `GenerationResult.summary` 明确 `buildable` / `runLevel`；有阻断项时不得只写“生成完成”。
- [ ] 测试：每类依赖一个最小 fixture；dry-run 与实际一致；阻断项摘要断言。
- [ ] 不安全的自动改写继续留人工项，不扩大自动改写面。

## 6. P3：真实架构约束（设计先行，0.4 再实现）

- [ ] 先写设计稿/schema：固定归属、不可拆分组、禁止归属、软偏好；配置版本与兼容策略。
- [ ] 明确冲突优先级：约束 vs `maxServices`/`minCommunitySize`、共享内核、空服务创建、Web/MCP move/undo/save。
- [ ] 设计通过后再实现硬约束，最后软约束；固定 seed 可复现，输出实际生效权重。
- [ ] 测试用参数化 + 小规模随机循环；不引入 property-testing 依赖也可。
- [ ] 0.3.x 内不写实现代码。

## 7. 并行与分工

| Track | 内容 | 依赖 | 建议 owner |
|---|---|---|---|
| A | P0 文档/CI/提交 | 无 | 一个 owner，避免 README/AGENTS 冲突 |
| B | P0 回归 + P1 契约测试 | A 的提交基线 | 测试 owner |
| C | P1 可观测性 | 无（可与 B 并行） | agent/core owner |
| D | P2 诊断 | B 的 codegen IT 稳定后 | codegen owner |
| E | P3 约束设计 | 无，只出文档 | 设计 owner |

合并顺序：A → B（或 B 先补 P0 回归）→ C → D → E。每轮结束跑 `mvn clean verify`，共享文件由 A owner 统一收口。

## 8. 发布门槛（Definition of Done）

- [ ] `mvn -B clean verify` 全绿；web/cli 不再零断言。
- [ ] RuoYi：290 类 / 980 边；partition 12 服务；generate 7 组桥 / 12 jar；启动到 DataSource。
- [ ] P0 全部 `[x]`；4KB 决策已写入文档；README/zh/AGENTS/mcp-design 无互斥数字。
- [ ] 推送 `github` + `nas`；GitHub Actions 首跑通过或已记录环境差异。
- [ ] 0.3.x 发布（按用户计划；若继续缓发 0.3.0，本项改为“等待发布窗口”）。

---

## 附：与 GPT 原稿的映射

| GPT 原稿 | 本执行版 |
|---|---|
| 1 统一产品承诺与文档 | §2.2（P0，按 diff 修，不重写） |
| 2 分析与 Agent 可观测性 | §4（P1，先计数器/统计，不改目标函数） |
| 3 CLI/Web/MCP 契约测试 | §3（P1，最高性价比） |
| 4 真实架构约束 | §6（P3，设计先行，0.4 再实现） |
| 5 生成前诊断 | §5（P2，typed diagnostic 优先） |

原稿的五方向保留；主要调整：把“约束”从 P2 降到 P3，把“当前已修/未修”写进 §1，把文档项从“重写”改成“按 diff 修”，并给 4KB/8KB 一个明确决策点。
