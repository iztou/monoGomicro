# monoGomicro MCP Server 设计方案（v1 评审稿）

> 状态：待评审。读者假设：**没有项目上下文的审阅者**，本文自包含。
> 评审重点见文末「开放问题」清单。

## 1. 项目背景（30 秒版）

monoGomicro 是一个 Java 单体应用 → 微服务拆分建议工具（IBM Mono2Micro 的开源复刻），
Java 17 / Maven 五模块，v1 已完成并实测通过（RuoYi 4.8.3：290 类 / 980 边 / 模度 Q=0.81 /
12 服务，生成的微服务工程 `mvn compile` 通过）。

流水线四阶段，阶段间以 JSON 文件为契约：

```
单体项目 ──analyze──> analysis.json ──partition──> partition.json ──generate──> 微服务工程
                        ↑ trace.log（ByteBuddy agent 采集，可选融合）
                        ⇅ web 控制台（D3 拖拽调分区，人在环）
```

五个模块的分层：

| 模块 | 职责 | 依赖 |
|---|---|---|
| core | 解析（JavaParser）、加权依赖图、Louvain+退火分区、共享内核检测、轨迹解析。**零框架纯库** | javaparser / jackson / slf4j-api |
| agent | ByteBuddy 运行时插桩，产出 trace.log | byte-buddy |
| codegen | 分区 → 可编译微服务工程（RPC 桥/内核闭包/类提升/MIGRATION.md） | core |
| cli | picocli 四子命令（fat jar `m2m.jar`） | core / codegen |
| web | Spring Boot 3.3 薄 REST + D3 控制台 | core / codegen |

## 2. 目标与非目标

**目标**

1. 把流水线的"入口层"再扩展一个：MCP server。让 LLM（Claude Desktop / ZCode / 任意 MCP 客户端）
   能对话式完成「读单体 → 出分区 → 解释依据 → 人工（对话）微调 → 生成工程 → 查看迁移清单」。
2. 产品叙事升级：web 是"人在环"，MCP 是"LLM 在环"，共用同一套 core 算法和 JSON 契约。
3. 工程约束：不改动 core/codegen 的现有公开 API（允许小幅增量）；新增模块，不动旧模块职责。

**非目标（明确不做）**

- 不通过 MCP 远程挂载 javaagent（插桩用户正在运行的单体超出 server 能力范围，保持手动，提供轨迹文件导入工具即可）
- 不替代 web 控制台（两种交互入口并存）
- 不做 MCP sampling / roots / elicitation 等高级能力（客户端差异大，收益低）
- MVP 不做多用户/多 workspace 并发会话

## 3. 现状盘点：可复用资产与差距

**可直接复用（这是本方案可行性高的根本原因）**

| 资产 | 位置 | 在 MCP 中的角色 |
|---|---|---|
| 四阶段流水线 API | `ProjectScanner.scan()` / `PartitionEngine.run()` / `MicroserviceGenerator.generate()` / `TraceAnalyzer.analyze()` | 流水线层工具的函数体，一行胶水 |
| JSON 契约 + Jackson 读写 | `JsonIO.read/write`，`AnalysisReport` / `PartitionResult` | 工具入参/返回的结构基础；MCP resource 直接就是这两个 JSON |
| 会话状态原型 | web 的 `ConsoleState`（load/labels/move + sourcePaths 索引） | MCP workspace 会话类的蓝本（需补线程安全与 undo） |
| 指标重算逻辑 | web `WorkspaceController.metrics()`（crossRatio / modularity / perCommunity） | `get_metrics` 与 move 后 delta 的实现（建议**下沉到 core** 供两端共用，见 §10） |
| CLI 参数语义 | `Main.java` 各子命令选项（alpha/beta/minSize/maxServices/seed…） | 工具入参 schema 的直接来源 |

**差距（本设计的主体）**

1. 现有产物面向"文件 + 人"，没有面向 LLM 的渐进式披露接口（analysis.json 506KB 不可整读）。
2. CLI 无状态逐命令执行，MCP 需要常驻会话状态机。
3. 错误是退出码+日志，需要结构化错误码。
4. `analyze` 是长任务（含 `mvn dependency:build-classpath`），需要进度与超时语义。
5. `generate` 写整棵目录树，面向 agent 暴露需要安全边界。

**stdout 纪律审计结论**：core 与 codegen 的 main 源码已确认**零 `System.out` / `printStackTrace`**
（全部走 slf4j）；`ConsoleReporter` 以 `PrintStream` 构造注入、MCP 路径不会实例化到 stdout。
风险收敛为新模块自身的纪律：stdio 传输下 stdout 是 JSON-RPC 通道，mcp 模块内日志一律
slf4j → simple 绑定输出到 **stderr**。

## 4. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│ monogomicro-mcp（新增模块，shade fat jar）                     │
│                                                             │
│  ┌------------------┐   ┌---------------------------------┐ │
│  | 传输层（可换）      │   | 工具实现层 McpTools（纯 Java，    │ │
│  | MVP: stdio       │──▶│ 不 import SDK 类型，方法出入参     │ │
│  | 后续: webmvc/HTTP │   │ 全部为项目内 DTO）                │ │
│  └------------------┘   └────────┬────────────────────────┘ │
│                                  │                          │
│  ┌-------------------------------▼------------------------┐ │
│  | WorkspaceSession（会话状态机 + 读写锁 + undo 栈）          │ │
│  │  AnalysisReport / DependencyGraph / PartitionResult     │ │
│  │  labels / 已解析用例（待融合）/ 快照栈                     │ │
│  └───────┬───────────────────────────────┬────────────────┘ │
│          │                               │                  │
│          ▼                               ▼                  │
│   core（算法库）                      codegen（生成器）       │
└─────────────────────────────────────────────────────────────┘
```

要点：

- **工具实现与 SDK 解耦**：`McpTools` 类的方法是纯 Java（DTO 入出参），传输/注册层只做
  schema 映射与 JSON 序列化。换 SDK 或加 HTTP 传输不动工具逻辑。
- **进程模型**：本地 stdio server（客户端拉起，生命周期随客户端）；后续可加
  Streamable HTTP 常驻模式，同一份工具实现。
- **一个 workspace**：MVP 单会话（单客户端拉起的进程即单会话），读写锁保证工具并发安全。

## 5. 技术选型

| 选项 | 说明 | 判断 |
|---|---|---|
| **官方 MCP Java SDK**（`io.modelcontextprotocol.sdk:mcp`） | 协议官方实现，stdio/WebFlux/WebMVC 传输齐备，不绑定 Spring | **推荐**：与 core 一样保持"薄"，shade 单文件分发与 agent/cli 的交付形态一致 |
| Spring AI MCP starter（`spring-ai-starter-mcp-server-*`） | 注解式工具注册，Boot 集成顺滑 | 备选：web 模块已是 Boot 3.3，若后续要与 web 合体或远程部署再引入（注意 starter 对 Boot 版本要求，可能需升 3.4+，以官方兼容矩阵为准） |

版本策略：SDK 走 `0.x` 快线，`pom` 中单独 property 管理，锁定具体版本；升级只影响传输注册层。

## 6. 工具目录（核心设计）

三层共 **16 个工具 + 2 个 resource**。命名统一 `snake_case`；所有入参出参为 JSON 对象。
注册时附带 MCP 工具 annotations（观察层标 `readOnlyHint`，`generate_services` 标
`destructiveHint`——客户端会自动给写盘操作加确认 UI）与 outputSchema（结构化输出，
客户端可校验渲染，比纯文本 JSON 稳）。

### 6.1 流水线层（改变状态机）

#### `analyze_project` — 静态分析目标单体，载入会话
- 入参：`projectDir`(string, 绝对路径)、`packagePrefixes`(string[], 业务包前缀)、
  `useMavenClasspath`(bool, 默认 true)、`persistAnalysisPath`(string?, 可选落盘路径)
- 返回：`{classes: 290, edges: 980, jarsResolved: 92, classpathDegraded: false, modules: ["ruoyi-admin", …], durationMs}`
  —— `classpathDegraded=true`（含 detail）表示 mvn 解析降级，依赖图精度已受影响，LLM 必须知情
- 错误：`E_PROJECT_NOT_FOUND` / `E_PKG_NO_MATCH` / `E_MAVEN_MISSING`（hint: 加 `useMavenClasspath=false` 重试）/ `E_PARSE_FAILED`（附文件与行号）
- 进度：按模块/文件粒度发 progress 通知
- 状态：`EMPTY → ANALYZED`

#### `load_workspace` — 从已有 JSON 载入（复用 CLI/web 已产出）
- 入参：`analysisPath`(string)、`partitionPath`(string?, 缺省则纯静态自动分区一版，与 web `/api/load` 语义一致)、
  `projectDir`(string?, 目标单体项目根——`generate_services` 要读源码与 pom，analyze 会话可继承，
  load 会话若要生成必须显式给)
- 返回：分区摘要（同 `get_partition_summary`）
- 状态：→ `ANALYZED` / `PARTITIONED`

#### `ingest_trace` — 导入 agent 轨迹，解析为业务用例
- 入参：`tracePath`(string)
- 返回：`{useCases: 14, classesCovered: 203, topUseCases: [{name, classes, edges} × 前5]}`
- 错误：`E_TRACE_NOT_FOUND` / `E_TRACE_FORMAT`
- 状态：附着在 `ANALYZED`，等 `compute_partition` 融合

#### `compute_partition` — 计算分区（融合已导入轨迹）
- 入参：`alpha`/`beta`(double, 默认 0.5)、`minSize`(int, 5)、`maxServices`(int, 12)、`seed`(long, 42)
- 返回：分区摘要（同 `get_partition_summary`；核心字段 `services` / `modularity` / `crossRatio` / `kernelSize`）
- 错误：`E_NO_ANALYSIS`
- 状态：`→ PARTITIONED`，清空 undo 栈

#### `generate_services` — 生成微服务工程
- 入参：`projectDir`(string?, 单体项目根；analyze 会话可省略继承，load 会话必填)、
  `outDir`(string?, 默认 `<workspace>/services`)、`dryRun`(bool, **默认 true**)、`overwrite`(bool, 默认 false)
- 返回：`{dryRun, services: 12, rpcBridges: 16, injectionsRewritten: 13, mappersMigrated: 21, manualItems: [前10条], manualItemsTotal: 4, outDir}`
- 错误：`E_NO_PARTITION` / `E_OUTPUT_EXISTS` / `E_OUT_OF_WORKSPACE`
- 安全：dryRun 默认开——只计算统计与迁移项不落盘（依赖 §10 新增的 codegen dry-run 支持）；
  真写盘时若目标已存在且非空，必须显式 `overwrite=true`，否则报 `E_OUTPUT_EXISTS`
  （**注意：现版 `MicroserviceGenerator.generate()` 首行即 `deleteRecursively(outDir)` 无条件清空，
  overwrite 检查是新增行为**，见 §10-4）

### 6.2 观察层（只读，渐进式披露）

#### `get_partition_summary`
- 返回：`{state, services: [{id, name, size}], metrics: {crossRatio, modularity, imbalance, avgUseCaseSpan}, kernelSize, totalClasses}` —— 目标 ≤ 800 token，这是对话的"仪表盘"

#### `list_classes`
- 入参：`scope`(`service:<id>` | `kernel` | `all`)、`stereotype?`(枚举过滤)、`page`(默认1)、`pageSize`(默认50, 上限200)
- 返回：`{total, page, classes: [{fqcn, simpleName, stereotype, mavenModule}]}`

#### `get_class_info` — 单类详情 + 依据解释（`explain_why` 并入此工具）
- 入参：`fqcn`
- 返回：`{stereotype, mavenModule, superClass, interfaces, sourcePath, currentService, inEdgesTop5: [{from, weight, kinds, line}], outEdgesTop5: [{to, …}], neighborCommunityConcentration: "12/18 条边指向 service:7"}`
  —— 最后一项是「为什么它在这个服务」的可计算解释：最强边的去向分布

#### `find_classes`
- 入参：`query`(简单名/包名子串)、`stereotype?`
- 返回：前 20 匹配 `[{fqcn, simpleName, stereotype, service}]` —— LLM 探索入口

#### `get_metrics`
- 返回：全局指标 + 每服务规模/通信量 + top 用例跨度（比 summary 更细的数值视图）

#### `get_migration_items`
- 返回：`{total, items: [{type, target, reason, doc}]}`（MIGRATION.md 的结构化形式，来自 `GenerationResult.getManualItems()`；
  未生成过——或分区在生成后被编辑过、缓存已失效——则 `E_NO_GENERATION`，hint 提示先（重新）`generate_services`）

### 6.3 编辑层（变更 + undo）

#### `move_class`
- 入参：`fqcn`、`targetService`(int, `-1` = 移入共享内核)
- 行为：push undo 快照 → 执行移动（语义同 `ConsoleState.move`）→ 重算指标
- 返回：`{moved, metricsDelta: {crossRatio: {before, after}, modularity: {before, after}}, affectedEdgesTop5}` —— delta 让 LLM 立刻看到后果，决定去留
- 错误：`E_UNKNOWN_CLASS` / `E_UNKNOWN_SERVICE`

#### `rename_service` — 给服务起语义名
- 入参：`serviceId`(int)、`name`(string)
- 行为：改 `Community.name`（影响 `get_partition_summary` 展示与 `generate_services` 的 `svc-<tag>` 目录名）；push undo 快照
- 返回：`{serviceId, name}`
- 错误：`E_NO_PARTITION` / `E_UNKNOWN_SERVICE`
- 动机：对话式工作流里 LLM 看类列表后给服务起语义名再生成，是「LLM 在环」最直观的一环

#### `undo`
- 返回：`{restored: {fqcn, fromService}}`；栈空返回 `E_NOTHING_TO_UNDO`（栈深上限 50）

#### `save_partition`
- 入参：`path?`(默认 workspace 内 `partition.json`)
- 返回：`{saved, path}`；`E_OUT_OF_WORKSPACE` 越界即拒

### 6.4 Resources（客户端按需拉取大对象）

| URI | 内容 | 说明 |
|---|---|---|
| `m2m://analysis` | 原始 analysis.json | 图数据按需取，不占对话上下文 |
| `m2m://partition` | 当前分区（含未保存编辑） | 与 `save_partition` 落盘内容一致 |

## 7. 会话与状态模型

```
EMPTY ──analyze_project──▶ ANALYZED ──compute_partition──▶ PARTITIONED
  │                            │  ▲                            │
  └──load_workspace────────────┘  └──────move_class/undo───────┘
                                 （编辑不降级状态；generate 仅 PARTITIONED 可用）
```

- 单 workspace 会话；所有工具先查状态机，非法调用返回结构化 `E_*`（含"下一步该调什么"的 hint）。
- 重复 `analyze_project` / `load_workspace`：任何状态下都允许，语义 = 重开会话
  （清空已导入 trace、undo 栈、生成缓存，回到 `ANALYZED` / `PARTITIONED`）。
- 分区编辑（`move_class` / `rename_service` / `undo` / `compute_partition`）使缓存的
  `GenerationResult` 失效——此后 `get_migration_items` 返回 `E_NO_GENERATION`。
- `save_partition` 仅 `PARTITIONED` 可用，否则 `E_NO_PARTITION`。
- 并发：读工具并行（`ReadWriteLock` 读锁），写工具（analyze/compute/move/rename/generate/save）串行（写锁）。
  MCP 客户端目前实际串行为主，锁是防御性的。
- undo 栈：`move_class` / `rename_service` 前压栈；`compute_partition` / `load_workspace` / 重复 `analyze_project` 清栈。

## 8. 输出与 token 预算（渐进式披露规则）

1. 单个工具返回目标 **≤ 4KB**（约 1~2K token），硬上限 8KB。
2. 列表必须分页：默认 `pageSize=50`，上限 200；返回带 `total`。
3. 边/邻居列表按 weight 降序取 top-N，被截断时输出 `truncated: true, total: N`。
4. `fqcn` 永远全限定名（LLM 后续引用它做 `move_class` 入参，不能有歧义）。
5. 摘要工具（`get_partition_summary`）设计目标 ≤ 800 token——它是每轮对话的锚点。
6. 大对象走 resource（`m2m://analysis`），由客户端决定是否拉取。

## 9. 错误模型

所有失败统一结构：

```json
{ "code": "E_NO_ANALYSIS", "message": "会话中尚无分析结果", "hint": "先调用 analyze_project 或 load_workspace" }
```

| 码 | 场景 | hint 要点 |
|---|---|---|
| E_NO_ANALYSIS / E_NO_PARTITION / E_NO_GENERATION | 状态机前置不满足 | 指明该先调哪个工具 |
| E_PROJECT_NOT_FOUND / E_TRACE_NOT_FOUND | 路径不存在 | 建议检查绝对路径 |
| E_PKG_NO_MATCH | 包前缀在项目中无匹配类 | 列出项目中实际出现的前两级包名供修正 |
| E_MAVEN_MISSING | useMavenClasspath=true 且 mvn 不在 PATH | 重试时置 false |
| E_PARSE_FAILED / E_TRACE_FORMAT | 解析失败 | 附文件/行号/样例行 |
| E_BAD_PARAMS | 缺少必填参数/参数类型错误 | 指明缺的字段，对照 inputSchema 补全 |
| E_SCAN_LIMIT | 源文件数/边数超防御上限（core `ProjectScanner` 已实现） | 确认 projectDir 指向单体而非过大目录；超大项目说明可调上限构造参数 |
| E_UNKNOWN_CLASS / E_UNKNOWN_SERVICE / E_NOTHING_TO_UNDO | 编辑类参数错 | 给出相近候选（前缀匹配） |
| E_OUTPUT_EXISTS / E_OUT_OF_WORKSPACE | 生成写盘越界/冲突 | 说明 overwrite 语义 |
| E_INTERNAL | 兜底 | 附异常摘要 + stderr 日志位置 |

实现方式：MCP 协议层报 `isError` 工具结果，payload 为上表结构（不做 JSON-RPC protocol error——
工具级失败对 agent 是正常分支，不是协议异常）。

## 10. 对 core / codegen 的少量增量改动

原则：**只加不改**，且 web 模块同步受益。

1. **指标计算下沉 core**：把 web `WorkspaceController.metrics()` 的逻辑（crossRatio /
   modularity / perCommunity）挪到 core 新类 `PartitionMetrics`（或挂在 `DependencyGraph`），
   web 与 mcp 共用，消除未来两处漂移。
2. **JSON 契约加版本**：`AnalysisReport` / `PartitionResult` 增加 `formatVersion` 字段
   （写入时填当前值；读取时缺失视为 v1 并兼容），工具端好做前向判断。
3. **`AnalysisReport` 补 `mavenModules` 汇总**（analyze 返回值要用，现需从 classes 里聚合，放模型上更稳）。
4. **codegen 增 dry-run 与 overwrite 语义**：现版 `MicroserviceGenerator.generate()` 首行
   `deleteRecursively(outDir)` 无条件清空写盘，与 MCP 的 `dryRun`/`overwrite` 契约直接冲突。
   新增 `generate(Path out, GenerateOptions{dryRun, overwrite})`：dryRun 时只计算
   summary/manualItems 不落盘；`overwrite=false` 且目标非空时报错而非删除。旧签名保留委托，web 不受影响。
5. **扫描资源上限放 core**：`ProjectScanner` 现无上限，新增源文件数/边数上限参数，超限抛结构化异常。
   放 core 而非 mcp 层，web 入口同样受益（§12 第 4 条的实现位置）。

## 11. 长任务与进度

- `analyze_project` / `compute_partition` / `generate_services` 通知 progress
  （SDK progress notification：`progress` + `total` + 可选消息"正在解析 ruoyi-system (3/6 模块)"）。
- MVP 同步阻塞执行（工具调用即等结果）；依赖客户端超时配置（建议 ≥ 120s）。
  拆分为「启动+轮询」的任务模型（`start_analysis` / `get_job_status`）留作后续演进，不在本期。

## 12. 安全边界

1. **写路径白名单**：`generate_services.outDir`、`save_partition.path` 与 `analyze_project.persistAnalysisPath`
   必须位于 workspace 根内，规范化后校验，拒绝 `..` 逃逸。**workspace 根在会话创建时定死**：
   analyze 会话默认 `<projectDir>/m2m-out`（与 CLI 的 `m2m-out/` 约定一致），
   load 会话默认 `analysisPath` 所在目录，均可显式指定；全文档所称 `<workspace>` 即此根。
2. **dry-run 默认**：`generate_services` 默认 `dryRun=true`，只返回统计与迁移项；真写盘需显式
   `dryRun=false` + 目标目录已存在且非空时 `overwrite=true`。
3. **进程外命令固定**：仅 `MavenClasspathResolver` 固定调 `mvn dependency:build-classpath`，
   无任意命令拼接；路径参数不经 shell。
4. **资源上限**：分析文件数上限（默认 20k 源文件）与边数上限（默认 500k），超出报 `E_SCAN_LIMIT`
   附说明（M0 已在 core `ProjectScanner` 落地，构造参数可调），防误指根目录全盘扫。
   边数检查依赖 `DependencyGraph.edgeCount()` 的 O(1) 计数器实现，扫描主循环内逐单元检查无性能负担。
5. **不回读敏感文件**：工具只暴露图数据与源码路径，不返回文件内容。

## 13. 测试策略

1. **单元测试（core 增量）**：`PartitionMetrics` 与 web 现值对齐测试；`formatVersion` 读写兼容。
2. **工具层测试**：`WorkspaceSession` 状态机全转移覆盖；每个工具的成功/每种 `E_*` 错误路径；
   undo 栈边界（空栈/超深）。
3. **契约测试**：用 SDK 自带的 client 能力对 server 做端到端调用（stdio 起 server，列工具、
   调 `analyze_project` 于小型 fixture 工程（core 自身或专造 30 类 mini 单体，不跑 mvn classpath 以提速）、
   断言返回 schema。
4. **手工验收**：MCP Inspector + Claude Desktop / ZCode 实测对话链路
   「analyze → summary → 解释某类 → move → 看 delta → undo → save」。

## 14. 里程碑与工作量

| 里程碑 | 内容 | 预估 |
|---|---|---|
| M0 | core 增量：PartitionMetrics 下沉、formatVersion、mavenModules、扫描上限；codegen 增量：GenerateOptions（dryRun/overwrite） | 1 天 |
| M1（MVP） | mcp 模块骨架 + stdio + 8 工具：analyze_project / load_workspace / compute_partition / get_partition_summary / list_classes / get_class_info / find_classes / move_class（含 undo） | 2~3 天 |
| M2 | generate_services（dry-run/overwrite/路径校验）、save_partition、rename_service、get_metrics、get_migration_items、ingest_trace、resources、进度通知 | 1~2 天 |
| M3 | 打磨：README（含 Claude Desktop `claude_desktop_config.json` 配置片段示例）+ Inspector 截图、错误 hint 完善、（可选）HTTP 传输 | 1 天 |

MVP 验收标准：在 Claude Desktop 里对一个真实单体完成「分析 → 摘要 → 追问某类归属依据 →
移动它并看到指标变化 → undo」全对话闭环。

## 15. 风险

| 风险 | 缓解 |
|---|---|
| SDK `0.x` 版本 API 变动 | 传输注册层与工具层隔离（§4），升级只碰注册层；版本锁定 |
| stdio 下 stray stdout 污染协议 | 新模块 CI 检查/代码评审规约：禁 `System.out`；slf4j-simple 输出到 stderr（审计确认 core/codegen 现状干净） |
| 大项目 analyze 超客户端超时 | progress 通知 + 文档标注建议超时；`--no-maven-classpath` 语义保留为 `useMavenClasspath=false` |
| LLM 滥用 move 把分区改坏 | delta 即时反馈 + undo；save 前可 get_metrics 复核；不自动落盘 |
| 工具过多导致模型选择困难 | 分层命名 + description 写清前置状态；MVP 先放 8 个验证选中率 |

## 16. 开放问题（请评审者重点表态）

1. **工具粒度**：`get_partition_summary` 与 `get_metrics` 是否合并？`list_services` 是否单列？
   （现方案：合并进 summary，metrics 单列细档）
2. **会话模型**：单 workspace 是否够？要不要一开始就 `session_id` 化（多客户端 HTTP 场景前置）？
3. **SDK 路线**：官方 Java SDK（现方案）vs 直接上 Spring AI starter（web 已是 Boot）——
   若你看重与 web 合体部署，请反对现方案。
4. **undo 粒度**：仅 move 级（现方案，简单）vs 快照级（整个 partition 深拷贝，内存换简单）？
5. **generate 的 dry-run 默认值**：true（现方案，安全优先）vs false（演示更爽快）？
6. **JSON 契约版本字段**：现在加（现方案）vs 等破坏性变更时再加？

**评审结论（2026-10-16 代码核对后定稿）**：① 维持分开——summary 是每轮对话锚点必须小，metrics 单列细档；
② MVP 单 workspace，HTTP 常驻时再 `session_id` 化；③ 维持官方 Java SDK；④ move 级 undo 足够，
快照级是过度设计；⑤ dryRun 默认 true，但依赖 §10-4 的 codegen 改动先落地（现版 generate 无条件删目录）；
⑥ formatVersion 现在加，一行字段换前向兼容。

---

*文档生成：2026-10-16；基于代码实测盘点（core/codegen stdout 审计、WorkspaceController/ConsoleState
结构核对、根 pom 依赖版本：javaparser 3.26.2 / jackson 2.17.2 / byte-buddy 1.15.10 /
picocli 4.7.6 / spring-boot 3.3.5 / slf4j 2.0.13 / junit 5.10.2）。*
