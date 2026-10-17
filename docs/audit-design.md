# monoGomicro 微服务架构体检与优化建议（设计方案 v1 评审稿）

> 状态：待外部评审（Kimi）→ 复核 → 实施。
> 本文档自包含：未参与设计讨论的评审者不需要额外上下文。
> 文末附**开放问题清单**（§9），是评审的重点。

## 1. 项目背景（30 秒版）

monoGomicro 是一个 Java 单体 → 微服务拆分建议工具（IBM Mono2Micro 复刻），六模块 Maven 工程（Java 17）：

| 模块 | 职责 |
|---|---|
| `monogomicro-core` | JavaParser 静态分析 → 类级加权依赖图；Louvain + 模拟退火分区；指标 —— 无框架纯库 |
| `monogomicro-agent` | ByteBuddy 运行时插桩，按 HTTP 入口切分业务用例（单 JVM） |
| `monogomicro-codegen` | 分区 → 可编译的 Maven 多模块微服务工程（跨服务调用改写 REST） |
| `monogomicro-cli` | `m2m analyze / partition / trace / generate` |
| `monogomicro-web` | 本地控制台（D3 力导向图 + 拖拽调区 + 指标实时重算） |
| `monogomicro-mcp` | MCP server（stdio + Streamable HTTP 双传输，15 工具 + 2 资源） |

已验证：RuoYi 4.8.3 单体（290 类 / 980 边）→ Q=0.81 / 12 服务 → 生成工程 `mvn compile` 通过；构件已发布 Maven Central（`io.github.iztou`）+ Homebrew tap + jbang。

**现有能力全部针对"单体输入"**：把一个工程当成一张平面类图，输出"从零怎么拆"的建议。

## 2. 本次目标：新增"微服务架构体检"能力

输入一个**已经是微服务的工程**（Maven 多模块 monorepo，如 RuoYi-Cloud），输出：

1. **现状体检报告**：架构指标（跨服务通信占比、模度、每服务扇入/扇出、规模失衡）+ 六类反模式检测（§5.2）
2. **优化建议**：对同一张依赖图跑一版参考分区，与现状边界对比，输出合并/拆分/移动类群建议；**每条建议附模拟指标 delta**（沙盘推演，不修改任何状态）
3. 入口：CLI 新子命令 `m2m audit`、MCP 只读工具 `audit_architecture` / `suggest_refactoring`、web `/api/audit`

### 非目标

- **跨服务运行时追踪**（traceId 跨进程传播 + 多实例聚合）——那是 APM（SkyWalking）的领地；静态依赖图已覆盖"边界该怎么调"的决策输入
- **渐进式重构执行器**（直接修改原工程：移文件/改 pom/修 import）——本轮只设计不做；若评审认为价值高列入后续
- 非 JVM 栈、非 Maven 工程
- 不替代现有单体拆分流程（两者并行，见 §4 铁律）

## 3. 现状盘点：可复用资产与缺口

### 已经有的（探索核实，含文件:行号）

| 资产 | 位置 | 对本设计的意义 |
|---|---|---|
| `ClassInfo.mavenModule` 字段 | `core/model/ClassInfo.java:13` | 扫描时已记录每个类的所属 Maven module（`ProjectScanner.java:168` 目录推导）——**现状服务边界的原始信号零成本可得** |
| `DependencyGraph` | `core/graph/DependencyGraph.java` | 节点/边/权重 API；`symmetrized()`（:110）是指标计算统一入口；`outEdges` O(出度)，`inEdges` O(V)（服务级聚合可承受） |
| `LouvainPartitioner.modularity(sym, labels)` | `core/partition/LouvainPartitioner.java:267` | 静态纯函数，接受任意 `Map<String,Integer>` 标签——现状模度直接可算 |
| `PartitionMetrics.of(graph, partition)` | `core/partition/PartitionMetrics.java:30` | crossRatio/modularity，但**只吃 `PartitionResult`**，labels 构建与计算耦合（:33-46） |
| 丰富统计（perCommunity fanIn/fanOut/intraWeight、imbalance、useCaseSpan） | `PartitionEngine.buildResult` 内联（`PartitionEngine.java:300-375`） | **未沉淀为可复用 API** |
| `PartitionEngine` | `PartitionEngine.java:46` | 参考分区直接调用（只读） |
| MCP 工具注册模式 | `McpServerMain.toolSpecs()` / `McpTools`（纯 Java 实现层） | 新只读工具照 `get_metrics` 抄（readOnly 注解、≤4KB 渐进披露） |
| JSON 兼容机制 | `JsonIO.java`（`FAIL_ON_UNKNOWN_PROPERTIES=false` + 写前版本盖章） | 新结构/新字段无破坏性 |

### 缺口

1. 没有"现状服务边界（as-is）"的独立建模——`mavenModule` 是构建坐标概念，不是部署级边界（多 module 可属一个服务，common 类库是共享而非业务服务）
2. 指标计算不支持任意"类→服务"映射
3. 没有服务级图聚合与反模式检测（环、上帝服务……）
4. 没有两个分区的 diff/对比与建议层

## 4. 行为保持铁律（最重要的约束）

**允许**为实现新功能而修改/重构现有代码；**但原有功能的行为必须逐位不变**：

1. 现有全部测试（41 单测 + 4 集成测试）全绿，**不改任何现有断言**
2. RuoYi 端到端回归：analyze → partition 的模度 Q、服务数、跨服务通信占比、每服务规模——重构前后**完全一致**（先固化基线快照，再动 core，对拍）
3. CLI 四个子命令、MCP 15 个工具、web 五个端点的行为与输出不变

单体拆分主流程与新体检能力并行存在、互不干扰。

## 5. 详细设计

### 5.1 `ServiceLandscape`——现状服务边界模型（core 新包 `com.monogomicro.core.audit`）

```java
/** 现状架构视图：服务 → 类集合。与 PartitionResult 完全独立的概念。 */
public final class ServiceLandscape {
    List<Service> services;        // name, List<String> fqcns, boolean shared
    // 派生索引：fqcn -> serviceName（构建时一次生成）
}
```

**构建来源**（`LandscapeFactory`）：

- 默认：按 `ClassInfo.mavenModule` 聚合，module 名即服务名
- 启发式：module 名命中 `common|shared|api|client|dto`（可配）→ 标记 `shared=true`（共享库语义，不参与业务服务指标口径，见 §9-Q2）
- 覆盖：可选 `services.json` 声明式映射（用户修正自动推导）：

```json
{
  "services": [
    { "name": "user-service", "modules": ["ruoyi-system-user", "ruoyi-system-account"] },
    { "name": "common",       "modules": ["ruoyi-common"], "shared": true }
  ]
}
```

JSON 契约版本化（复用 JsonIO 盖章机制）。未在 services.json 出现的 module 默认自成服务。

### 5.2 `ArchitectureAuditor`——体检引擎

输入 `DependencyGraph + ServiceLandscape`，输出 `AuditReport`。

**指标**（§5.2.1）：

| 指标 | 口径 |
|---|---|
| crossRatio | 跨服务边权重 / 总边权重（两端任一属 shared 服务 → 剔除，与 PartitionMetrics 剔 kernel 口径对齐，待评审 §9-Q2） |
| modularity | `LouvainPartitioner.modularity(sym, 现状labels)`（shared 类剔除后计算） |
| 每服务 fanIn/fanOut | 跨服务**有向**边权聚合（A 依赖 B 记 A→B 出边） |
| 服务内部权重 | 两端同服务的边权和 |
| imbalance | 服务规模 max/min 与 标准差/均值 两个口径都给 |

**六类反模式**（§5.2.2，阈值全部可配，默认值待评审 §9-Q1）：

| # | 反模式 | 判定（默认） | 输出 |
|---|---|---|---|
| 1 | 服务循环依赖 | 服务图（节点=服务，有向边=跨服务聚合权重）Tarjan SCC，\|SCC\|>1 | 环成员 + 环上权重 top 边 |
| 2 | 上帝服务 | size > max(均值×2, 15) 且为最大服务 | 服务名、规模、占总量比 |
| 3 | 该合并对 | 服务对 (A,B)：min(w(A→B), w(B→A)) > 0.5 × min(A,B 的跨服务总流量)，即彼此是对方的主要外部流量 | 服务对 + 双向权重 + 占比 |
| 4 | 哑服务 | 跨服务出边权重 > 3 × 入边权重，且 size < 8 | 服务名 + 出入比 + 主要流向 |
| 5 | 共享库滥用 | shared 服务的类按"扇入服务数"排序 topN（默认 10），扇入 ≥ 半数服务 | 类名 + 扇入服务列表 |
| 6 | 归属错位类 | 类 C 属服务 S：出边投向 S 的权重比 p_in，投向其他单服务最大比 p_max；p_max − p_in > 0.2 → 候选 | 按差值排序 topN + 两边权重明细 |

**报告模型**（`AuditReport`）：`{ formatVersion, generatedAt, summary(指标), services[], findings[](type/severity/service/classes/detail), config(生效阈值) }`；severity 三档（info/warning/critical）。

### 5.3 `RefactoringAdvisor`——优化建议

输入 `DependencyGraph + ServiceLandscape`：

1. **参考分区**：对同一张图（shared 类先剔除）调用现有 `PartitionEngine`（纯静态权重，默认参数）→ 参考 labels
2. **对齐**：现状服务 ↔ 参考社区的重叠矩阵（交集类数），贪心降序配对（重叠为 0 不配）
3. **diff → 建议三类**：
   - **移动类群**：配对 (S, C) 中，属 S 但参考属其他社区的类群 → 建议移出（反之移入）；类群按目标社区聚合
   - **拆分**：现状服务 S 与多个参考社区都有大交集且无一个过半 → 建议审视拆分
   - **合并/吸收**：未配对的参考社区 + 配对服务间小规模者 → 建议审视合并
4. **沙盘 delta**：每条移动类群建议在 landscape 副本上应用后重算 crossRatio/modularity，输出 before/after；**delta 变差的建议自动降级或标注**（§9-Q3）

措辞定位：参考分区是**参照物不是真理**——历史包袱、团队边界、发布节奏都是算法看不见的约束，报告里显式声明这一点。

### 5.4 现有代码的安全重构点（共两处，均有对拍）

| 改动 | 内容 | 行为保证 |
|---|---|---|
| `PartitionMetrics` 加重载 | `of(DependencyGraph, Map<String,Integer> labels)`（kernel=-1 约定）；旧签名保留并委托新路径 | 新增对拍测试：旧路径结果逐位一致 |
| `PartitionEngine.buildResult` 统计下沉 | perCommunity fanIn/fanOut/intraWeight/imbalance 计算抽为 `PartitionMetrics` 静态方法，buildResult 调用 | RuoYi 基线快照对拍（Q/服务数/每服务规模逐位一致） |

### 5.5 入口层

**CLI**（照 `PartitionCommand` 风格新增子命令）：

```
m2m audit -a <analysis.json> [-s services.json] [--suggest] [-o 输出目录]
# 默认：体检报告（audit.json + 控制台摘要）；--suggest 附加建议清单
```

**MCP**（两个只读工具，照 `get_metrics` 注册模式）：

- `audit_architecture`：入参 `{servicesPath?}`（可选 services.json；缺省用 mavenModule 自动聚合）；ANALYZED 状态即可用（不进分区状态机）；返回摘要 + findings 分页
- `suggest_refactoring`：入参同上 + `{minDeltaImprovement?}`；返回建议清单（每条含模拟 delta）

**web**：`/api/audit`（POST，body 同 CLI 参数）返回 AuditReport JSON；前端加体检视图（反模式列表 + 建议卡片）——薄层，最后做，可裁剪。

## 6. 实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 固化 RuoYi 指标基线（临时脚本产出快照，供阶段 2 对拍） | 快照入库 |
| 1 | `LandscapeFactory` + `ServiceLandscape` + services.json 解析 | 单测：聚合/覆盖/启发标记 |
| 2 | `PartitionMetrics` 重载 + buildResult 下沉 | 对拍全绿 + RuoYi 基线一致 + 现有 45 测试全绿 |
| 3 | `ArchitectureAuditor`（指标 + 六反模式）+ `AuditReport`/Reporter | 反模式 fixture 单测（构造环/上帝/错位样本断言命中） |
| 4 | `RefactoringAdvisor` | fixture 对拍 + delta 合理性单测 |
| 5 | CLI `m2m audit` + MCP 两工具 | CLI 冒烟 + McpServerIT 增补（工具列表数断言更新） |
| 6 | 真实样本冒烟（RuoYi-Cloud 或手造）+ 报告人工核验 | 记录核验结论 |
| 7 | web 视图（可选）+ README 双语 + AGENTS.md + 发布 0.3.0 | 全量 verify + 双推 |

## 7. 验证策略

- **行为保持**：阶段 2 是唯一动现有代码的时点，前后各跑一次全量 verify + RuoYi 端到端对拍
- **新功能**：好/坏边界两版 fixture 微服务 repo（坏版必须检出：2-3 服务环、上帝服务、错位类）；单测覆盖每个反模式规则与边界条件（空图/单服务/全 shared）
- **冒烟**：真实微服务样本跑 `m2m audit --suggest`，人工核验 ≥3 条 findings 与 ≥1 条建议的合理性

## 8. 与现有能力的关系（边界示意）

```
单体工程 ──analyze──▶ 依赖图 ──partition──▶ PartitionResult ──generate──▶ 新微服务工程   （现有，不动）
                          │
微服务 monorepo ──analyze─┤（同一入口，mavenModule 已天然记录）
                          └──LandscapeFactory──▶ ServiceLandscape
                                      └──ArchitectureAuditor──▶ AuditReport（体检）
                                      └──RefactoringAdvisor──▶ 建议清单（沙盘 delta）
```

同一份 analysis.json 两条消费路径，互不感知。

## 9. 开放问题清单（评审重点）

- **Q1 反模式阈值**：默认值（上帝服务 ×2 均值、合并对 0.5、哑服务 3:1、错位 0.2）取静态常数还是按样本分位数自适应？我的倾向：首版全部固定 + `--config` 可覆盖，报告里带"生效阈值"自描述；分位数留后续。
- **Q2 shared 服务口径**：crossRatio/modularity 计算剔除 shared 服务（对齐现有 kernel 剔除口径）是否正确？还是应该计入（毕竟跨服务调用共享库也是运行时成本）？
- **Q3 建议的 delta 过滤**：移动类群建议若沙盘 delta 变差，是"自动不输出"还是"输出但标 warning"？我的倾向：输出并标注（信息价值 > 简洁），由人决策。
- **Q4 services.json 最小契约**：`name/modules/shared` 三键够不够？要不要支持"排除 module"（测试代码、generated）？
- **Q5 错位类口径**：只按出边权重，还是对 INHERITANCE 类边（权重 3）额外加权？继承关系的错位比方法调用错位更严重。
- **Q6 MCP 工具状态依赖**：`audit_architecture` 只需 ANALYZED（推荐，独立于分区状态机）还是也允许在 PARTITIONED 下用当前分区当 landscape（对比两种边界）？后者有联动价值但引入语义耦合。
- **Q7 参考分区的参数**：RefactoringAdvisor 跑 PartitionEngine 用默认参数（maxServices=12 等）对大微服务样本（>12 服务）会不会系统性建议合并？要不要按现状服务数适配 maxServices？
- **Q8 web 视图**：本轮做不做？（价值中等，工作量约占 10%）
- **Q9 AuditReport 的 findings 是否需要稳定 id**（便于沙盘/后续执行器引用建议条目）？

---

评审请重点看：§4 铁律的验证手段是否充分、§5.2 反模式定义与阈值的合理性、§5.3 参考分区对齐算法的漏洞、§9 全部开放问题。
