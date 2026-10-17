# monoGomicro 微服务架构体检与优化建议（设计方案 v2 定稿）

> 状态：v1 → Kimi 评审（`docs/audit-review.md`，2026-10-17）→ 断言逐条复核属实 → v2 合入全部修订意见，**待实施**。
> 本文档自包含：未参与设计讨论的实施者不需要额外上下文。
> v2 相对 v1 的关键变化：指标语义修正（边界耦合率而非通信占比，§5.2）、重构点 2 改写（§5.4）、services.json 契约扩展（§5.1）、反模式公式精确化与实现约束（§5.2.2）、参考分区参数适配（§5.3）、原 §9 开放问题全部定论并入正文。

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

1. **现状体检报告**：架构指标（**边界耦合率**、模度、每服务扇入/扇出、规模失衡——指标语义见 §5.2.0）+ 六类反模式检测（§5.2.2）
2. **优化建议**：对同一张依赖图跑一版参考分区，与现状边界对比，输出合并/拆分/移动类群建议；**每条建议附模拟指标 delta**（沙盘推演，不修改任何状态）
3. 入口：CLI 新子命令 `m2m audit`、MCP 只读工具 `audit_architecture` / `suggest_refactoring`、web `/api/audit` 端点（前端视图可裁剪，见 §6）

### 非目标

- **跨服务运行时追踪**（traceId 跨进程传播 + 多实例聚合）——APM（SkyWalking）的领地
- **渐进式重构执行器**（直接修改原工程）——本轮不做，findings 稳定 id（§5.2.3）为其预留锚点
- 非 JVM 栈、非 Maven 工程
- 不替代现有单体拆分流程（并行关系，见 §4 铁律与 §8 示意）

## 3. 现状盘点：可复用资产与缺口

### 已经有的（v2 复核后的事实基线）

| 资产 | 位置（以代码为准） | 对本设计的意义 |
|---|---|---|
| `ClassInfo.mavenModule` 字段 | `core/model/ClassInfo.java:13` | 扫描时记录每个类的所属 module（构造点 `ProjectScanner.java:168`；**命名推导在 `moduleNameOf`（`ProjectScanner.java:441-452`），是目录相对路径 `/`→`-`，非 artifactId**——嵌套布局下形如 `ruoyi-modules-ruoyi-system`） |
| `DependencyGraph` | `core/graph/DependencyGraph.java` | 节点/边/权重 API；`symmetrized()`（:109-121）**实际实现为双向求和**（方法上的 max 注释是陈旧注释，以实现为准；实施阶段顺手修正注释）；`outEdges` O(出度)，`inEdges` O(V)（audit 扇入统计须一遍扫边聚合，禁止逐类调 `inEdges`） |
| `LouvainPartitioner.modularity(sym, labels)` | `core/partition/LouvainPartitioner.java:267` | 静态纯函数，接受任意 `Map<String,Integer>` 标签 |
| `PartitionMetrics.of(graph, partition)` | `core/partition/PartitionMetrics.java:30`（labels 构建与计算耦合 :33-46） | crossRatio 基于原始有向图（:50-61） |
| 丰富统计（fanIn/fanOut/intra、imbalance、useCaseSpan） | `PartitionEngine.buildResult` 内联（:300-375；fanIn/fanOut/intra :301-340 基于融合对称图且 `/2` 还原，imbalance :342-348，useCaseSpan :350-375） | **与 audit 口径不同，不能整体下沉复用**（§5.4） |
| `PartitionEngine` / `PartitionOptions` | `PartitionEngine.java:46`；`PartitionOptions.java:12`（maxServices 默认 12） | 参考分区直接调用（只读）；参数须适配（§5.3） |
| MCP 工具注册模式 | `McpServerMain.toolSpecs()` / `McpTools` | 新只读工具照 `get_metrics` 抄（readOnly 注解、≤4KB 渐进披露） |
| JSON 兼容机制 | `JsonIO.java`（`FAIL_ON_UNKNOWN_PROPERTIES=false` + 写前版本盖章） | 新结构/新字段无破坏性 |
| `DependencyEdge.kindSet()` | `DependencyEdge.java:68`（EdgeKind :14，INHERITANCE 权重 3） | 错位类的"继承错位"子类判定用 |

### 缺口

1. 没有"现状服务边界（as-is）"的独立建模（`mavenModule` 是构建坐标概念：多 module 可属一个服务，common 类库是共享而非业务服务）
2. 指标计算不支持任意"类→服务"映射；现有两套统计口径（原始图 vs 融合对称图）语义均与 audit 需求不完全一致
3. 没有服务级图聚合与反模式检测
4. 没有两个分区的 diff/对比与建议层

## 4. 行为保持铁律（最重要的约束）

**允许**为实现新功能而修改/重构现有代码；**但原有功能的行为必须逐位不变**：

1. 现有全部测试（**42 单测 + 4 集成测试 = 46**：core 15 + codegen 3 + mcp 24；IT = GeneratedServicesRpcIT 1 + McpServerIT 1 + McpHttpIT 2）全绿，**不改任何现有断言**
2. RuoYi 端到端回归：analyze → partition 的模度 Q、服务数、跨服务通信占比、每服务规模——重构前后**完全一致**（先固化基线快照，再动 core，对拍；两套口径的数字分别固化）
3. CLI 四个子命令、MCP 15 个工具、web 五个端点的行为与输出不变

单体拆分主流程与新体检能力并行存在、互不干扰。

## 5. 详细设计

### 5.0 现状边界模型：`ServiceLandscape`（core 新包 `com.monogomicro.core.audit`）

```java
/** 现状架构视图：服务 → 类集合。与 PartitionResult 完全独立的概念。 */
public final class ServiceLandscape {
    List<Service> services;        // name, List<String> fqcns, boolean shared
    // 派生索引：fqcn -> serviceName（构建时一次生成）
}
```

**构建来源**（`LandscapeFactory`）：

- 默认：按 `ClassInfo.mavenModule` 聚合，module 名即服务名（**注意命名来自目录推导**，嵌套布局带路径前缀）
- 启发式：module 名命中 `common|shared|api|client|dto`（可配）→ 标记 `shared=true`（§5.2.0 口径用）
- 覆盖：可选 `services.json` 声明式映射（**v2 契约**：modules 匹配支持**精确名 / 目录推文名 / 前缀通配**三种写法，按 `moduleNameOf` 实际产出命名对齐；另加 `exclude`）：

```json
{
  "services": [
    { "name": "user-service", "modules": ["ruoyi-modules-ruoyi-system", "ruoyi-modules-*-account*"] },
    { "name": "common",       "modules": ["ruoyi-common"], "shared": true }
  ],
  "exclude": ["**/test-*", "**/*-generated"]
}
```

JSON 契约版本化（复用 JsonIO 盖章机制）。未在 services.json 出现且未被 exclude 的 module 默认自成服务。

### 5.2.0 指标语义（v2 核心修正，来自评审 §1）

**单体拆分与微服务体检的指标语义根本不同**：单体场景每条类间边都是潜在 RPC，crossRatio 近似拆分后网络通信成本；微服务 monorepo 场景跨 module 边绝大多数是**编译期依赖**——对 common 库类的依赖运行时是打进每个服务 jar 的**同进程方法调用**（不是 RPC），对 `*-api`/`*-client` 模块的依赖可能是 Feign 接口声明（RPC）也可能只是 DTO 引用（非 RPC）。

因此：

- 主指标命名 **boundaryCoupling（边界耦合率）**——报告措辞为"边界耦合/违规率"，**不得**表述为"跨服务通信占比"
- **shared 服务剔除**（定论，原 Q2）：理由不是"对齐 kernel 口径"，而是**这些边本来就不是跨进程调用**；同时输出一个含 shared 的辅助口径（成本近零，仅供参照）
- **modularity**：`LouvainPartitioner.modularity(sym, 现状labels)`（shared 类剔除后）
- **每服务 fanIn/fanOut**：基于 `DependencyGraph.outEdges` 的**原始有向图**一遍扫边聚合（A 依赖 B 记 A 的出边、B 的入边）——**不复用** `buildResult` 的对称图 `/2` 口径（§5.4）
- **服务内部权重**：两端同服务的边权和（原始图）
- **imbalance**：服务规模 max/min 与标准差/均值两个口径

后续增强（不阻塞首版，记录）：对 `*-api`/`*-client` 模块中 Feign 接口的依赖单独计数，作为"真实 RPC 边"的下界估计。

### 5.2.2 六类反模式（阈值固定 + `--config` 覆盖 + 报告自带生效阈值；分位数自适应不做，定论 Q1）

| # | 反模式 | 判定（默认；公式为唯一口径） | 输出 |
|---|---|---|---|
| 1 | 服务循环依赖 | 服务图（节点=服务，有向边=跨服务原始权重聚合）Tarjan SCC，\|SCC\|>1 | 环成员 + 环上权重 top 边 |
| 2 | 上帝服务 | size > max(均值×2, 15) 且为最大服务 | 服务名、规模、占总量比 |
| 3 | 该合并对 | 对服务对 (A,B)：`min(w(A→B), w(B→A)) > 0.5 × min(F(A), F(B))`，其中 **F(X) = X 的全部跨服务出边权重 + 全部跨服务入边权重**（单次判定，min 两侧同取） | 服务对 + 双向权重 + 占比 |
| 4 | 哑服务 | 跨服务出边权重 > 3 × 入边权重，且 size < 8 | 服务名 + 出入比 + 主要流向 |
| 5 | 共享库滥用 | shared 服务的类按"扇入服务数"排序 topN（默认 10），扇入 ≥ 半数业务服务；**扇入统计 = 一遍扫边聚合**（禁逐类 `inEdges`，O(V²)） | 类名 + 扇入服务列表 |
| 6 | 归属错位类 | 类 C 属服务 S：p_in = C 出边中投向 S 的权重 / **C 全部出边权重（分母含投向 shared 的部分）**；p_max = 投向其他单个业务服务的最大占比；p_max − p_in > 0.2 → 候选，按差值排序 topN（默认 15）；**继承错位单独提为更高 severity 的子类 finding**（`kindSet()` 含 INHERITANCE 的错位边 ≥1 即升级，不做加权——加权会稀释） | 两边权重明细 + 是否含继承边 |

**severity 分档规则**（显式定义，所有 finding 必须落档）：

- `critical`：服务环（#1）；错位类中含继承边子类（#6-inherit）
- `warning`：上帝服务、该合并对、错位类普通子类；共享库滥用中扇入 = 全部业务服务
- `info`：哑服务；共享库滥用其余；其余观察项

### 5.2.3 报告模型（`AuditReport`）

`{ formatVersion, generatedAt, summary(指标，含双口径), services[], findings[], config(生效阈值) }`；
**findings 稳定 id**（定论 Q9）：`type + 服务名 + 关键类集合的短 hash`（如 `misplaced:com.x.SysUserServiceImpl:a3f2`）——为后续沙盘引用与执行器预留锚点。

### 5.3 `RefactoringAdvisor`——优化建议

输入 `DependencyGraph + ServiceLandscape`：

1. **参考分区**：对同一张图（shared 类先剔除）调用现有 `PartitionEngine`（纯静态权重）；**参数适配（定论 Q7）**：`maxServices = max(12, 现状业务服务数 × 1.5)`，seed 固定（默认 42），**报告注明参考分区的参数与 seed**（可复现）
2. **对齐**：现状服务 ↔ 参考社区的重叠矩阵（交集类数），贪心降序配对（重叠为 0 不配）
3. **diff → 建议三类**：移动类群（配对 (S,C) 中属 S 但参考属其他社区的类群，按目标社区聚合）；拆分（S 与多个参考社区都有大交集且无一过半）；合并/吸收（未配对的参考社区 + 配对服务间小规模者）
4. **沙盘 delta**：每条移动类群建议在 landscape 副本上应用后重算 boundaryCoupling/modularity，输出 before/after；**delta 变差的建议输出并标注 warning，不自动过滤**（定论 Q3——这类条目往往揭示算法看不见的团队/历史约束，信息价值恰在其中）

措辞定位：参考分区是**参照物不是真理**，报告显式声明。

### 5.4 现有代码的安全重构点（v2 改写，共两处，均有对拍）

| 改动 | 内容 | 行为保证 |
|---|---|---|
| `PartitionMetrics` 加重载 | `of(DependencyGraph, Map<String,Integer> labels)`（kernel=-1 约定）；旧签名保留并委托新路径 | 新增对拍测试：旧路径结果逐位一致 |
| `buildResult` 统计**部分**下沉（v2 收窄范围） | 只下沉**口径无关**的统计：imbalance、useCaseSpan；fanIn/fanOut/intra **不下沉**（buildResult 基于融合对称图 `/2` 口径，与 audit 的原始有向图口径语义不同，强行共享会埋坑） | RuoYi 基线快照对拍（Q/服务数/每服务规模逐位一致）；audit 指标在 audit 包内基于原始图独立实现 |

### 5.5 入口层

**CLI**（照 `PartitionCommand` 风格新增子命令）：

```
m2m audit -a <analysis.json> [-s services.json] [--suggest] [--config 阈值覆盖] [-o 输出目录]
```

**MCP**（两个只读工具，照 `get_metrics` 注册模式）：

- `audit_architecture`：入参 `{servicesPath?}`；**ANALYZED 状态即可用**（不进分区状态机，定论 Q6）；返回摘要 + findings 分页；后续增强：PARTITIONED 下拿当前分区当 landscape（只差一个 PartitionResult→landscape 转换器）
- `suggest_refactoring`：入参同上 + `{minDeltaImprovement?}`；返回建议清单（每条含模拟 delta）

**web**：`/api/audit` 端点（后端保留）；前端体检视图**可裁剪放最后**（定论 Q8）。

## 6. 实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 固化 RuoYi 指标基线（**两套口径分别快照**） | 快照入库 |
| 1 | `LandscapeFactory` + `ServiceLandscape` + services.json 解析（含 exclude 与三种匹配） | 单测：聚合/覆盖/启发标记/嵌套命名 |
| 2 | `PartitionMetrics` 重载 + buildResult 部分下沉 + symmetrized 陈旧注释修正 | 对拍全绿 + RuoYi 基线一致 + 现有 46 测试全绿 |
| 3 | `ArchitectureAuditor`（指标 + 六反模式 + severity）+ `AuditReport`（稳定 id）/Reporter | 反模式 fixture 单测（构造环/上帝/错位样本断言命中与分档） |
| 4 | `RefactoringAdvisor`（含 maxServices 适配与 seed 注明） | fixture 对拍 + delta 合理性单测 |
| 5 | CLI `m2m audit` + MCP 两工具 | CLI 冒烟 + McpServerIT 工具列表数断言更新 |
| 6 | 真实样本冒烟（RuoYi-Cloud 或手造）+ 报告人工核验 | 记录核验结论 |
| 7 | web `/api/audit` + 前端视图（可裁剪）+ README 双语 + AGENTS.md + 发布 0.3.0 | 全量 verify + 双推 |

## 7. 验证策略

- **行为保持**：阶段 2 是唯一动现有代码的时点，前后各跑全量 verify + RuoYi 端到端对拍
- **新功能**：好/坏边界两版 fixture 微服务 repo（坏版必须检出：2-3 服务环、上帝服务、错位类）；单测覆盖每个反模式规则与边界条件（空图/单服务/全 shared）
- **冒烟**：真实微服务样本跑 `m2m audit --suggest`，人工核验 ≥3 条 findings 与 ≥1 条建议

## 8. 与现有能力的关系（边界示意）

```
单体工程 ──analyze──▶ 依赖图 ──partition──▶ PartitionResult ──generate──▶ 新微服务工程   （现有，不动）
                          │
微服务 monorepo ──analyze─┤（同一入口，mavenModule 已天然记录）
                          └──LandscapeFactory──▶ ServiceLandscape
                                      └──ArchitectureAuditor──▶ AuditReport（体检）
                                      └──RefactoringAdvisor──▶ 建议清单（沙盘 delta）
```

## 9. 评审结论记录（v1 开放问题全部定论）

| 问题 | 定论 |
|---|---|
| Q1 阈值 | 固定默认 + `--config` 覆盖 + 报告自带生效阈值；分位数不做 |
| Q2 shared 口径 | 剔除（理由：编译期依赖非跨进程调用）；附含 shared 辅助口径 |
| Q3 delta 过滤 | 变差的建议输出并标 warning，不自动过滤 |
| Q4 services.json | 加 `exclude`（glob）；module 匹配对齐目录推文名（精确/前缀/通配） |
| Q5 继承错位 | 独立高 severity 子类 finding，不做加权 |
| Q6 MCP 状态 | 首版仅 ANALYZED；PARTITIONED 复用列后续增强 |
| Q7 参考分区参数 | `maxServices = max(12, 现状服务数 × 1.5)` + 固定 seed + 报告注明 |
| Q8 web | 端点保留，前端视图可裁剪放最后 |
| Q9 findings id | 稳定 id（type + 服务名 + 类集合短 hash） |

后续增强池（本轮不做）：Feign 接口依赖单独计数（真实 RPC 下界）、PARTITIONED 下 landscape 复用、渐进式重构执行器。
