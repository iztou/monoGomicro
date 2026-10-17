# 《微服务架构体检与优化建议》设计评审意见（Kimi，2026-10-17）

> 评审对象：`docs/audit-design.md`（设计方案 v1 评审稿）
> 评审方式：文档中所有"文件:行号"级事实断言逐一到代码核实 + 设计本身合理性审查。
> 结论：**批准进入实施**，前提是落实 §1 指标语义修正、§2 口径修正、§4-Q7 的 maxServices 适配。

## 1. 概念性盲点（必须修正）：audit 场景的指标语义与单体拆分不同

设计把单体拆分场景的指标口径平移到了微服务体检场景，两者语义根本不同：

- **单体场景**：每条类间边都是"潜在 RPC"，crossRatio 近似拆分后的网络通信成本。
- **微服务 monorepo 场景**：跨 module 边绝大多数是**编译期依赖**。对 common 库类的依赖，运行时是被打进每个服务 jar 的**同进程方法调用**，不是 RPC；对 `*-api` / `*-client` 模块的依赖，可能是 Feign 接口声明（是 RPC），也可能只是 DTO 引用（不是 RPC）。

因此 `m2m audit` 输出的 crossRatio **不是"跨服务通信占比"，而是"边界耦合度/违规率"**。

落地要求：

1. 报告中指标命名与措辞改为"边界耦合率"类表述，避免暗示网络通信成本；
2. §9-Q2 定论：剔除 shared 服务**正确**，但理由不是"对齐 kernel 剔除口径"，而是"这些边本来就不是跨进程调用"；同时保留一个含 shared 的辅助口径（成本近零）；
3. （后续增强，不阻塞首版）对 `*-api`/`*-client` 模块中 Feign 接口的依赖单独计数，作为"真实 RPC 边"的下界估计。

反模式检测的价值不受此影响（边界耦合本身值得查），六类反模式定义维持有效。

## 2. 技术口径修正（§5.4 重构点 2 必须改写）：buildResult 统计不能整体下沉复用

两处口径不一致，已核实代码：

1. **fanIn/fanOut**：`PartitionEngine.buildResult` 基于**融合对称图** `serviceSym`（含动态权重、×1000 放大）计算再 `/2` 还原（PartitionEngine.java:306-337），语义是"无向流量对半分"；而设计 §5.2 定义的服务级 fanIn/fanOut 是**原始有向图聚合**（A 依赖 B 记 A→B 出边）。audit 侧应基于 `DependencyGraph.outEdges/inEdges` 一遍扫描独立实现，**不复用 buildResult 那套**。
2. **crossRatio** 同样有两个口径：`PartitionMetrics.of` 用原始有向图（PartitionMetrics.java:50-61），`buildResult` 用融合对称图。

修正后的 §5.4 重构点 2：只下沉口径无关的统计（imbalance、useCaseSpan）；audit 指标独立基于原始图实现。RuoYi 基线对拍时两套口径的数分别固化。

## 3. 反模式定义的精确化要求

- **#3 该合并对**：分母 `min(A,B 的跨服务总流量)` 表述含糊。实现前写成精确公式（明确是 min(总流量A, 总流量B) 单判定，还是 A、B 各自判定取交集）。
- **#5/#6 topN 扇入统计**：不要逐类调 `DependencyGraph.inEdges`（每次 O(V)，全量 O(V²)）；一遍扫边聚合即可。
- **#6 错位类**：明确 p_in / p_max 的分母（建议：该类全部出边权重，含投向 shared 的部分，并在 detail 中注明口径）。
- severity 三档（info/warning/critical）的分档规则需在设计或实现中显式定义。

## 4. 开放问题（§9）逐条结论

- **Q1**：固定阈值 + `--config` 覆盖 + 报告自带生效阈值。分位数自适应不做。
- **Q2**：剔除 shared，理由按本文 §1 改写；附加含 shared 辅助口径。
- **Q3**：输出并标注 warning，不自动过滤。delta 变差的建议往往揭示算法看不见的团队/历史约束，信息价值恰在这类条目。
- **Q4**：三键不够，加 `exclude`（glob）。**实测发现**：`moduleNameOf` 用的是**目录相对路径**而非 artifactId（ProjectScanner.java:441-452），嵌套布局下 module 名形如 `ruoyi-modules-ruoyi-system`，设计文档示例的 `ruoyi-system-user` 风格对不上。services.json 的 modules 匹配规则必须按实际推导命名写清，或扩展为同时匹配 artifactId。
- **Q5**：继承错位单独提为更高 severity 的子类 finding（`DependencyEdge.kindSet()` 已保留边类型，INHERITANCE 权重 3 属实，DependencyEdge.java:14/68），不做简单加权——加权会把它稀释进总分。
- **Q6**：首版只做 ANALYZED。`PartitionMetrics.of(graph, labels)` 重载落地后，"PARTITIONED 下拿当前分区当 landscape"只差一个 PartitionResult→landscape 转换器，列为后续增强。
- **Q7**：**担忧属实，已核实**。`PartitionOptions.maxServices` 默认 12（PartitionOptions.java:12），`mergeToTargetCount` 硬压社区数（PartitionEngine.java:68），对 >12 服务现状必然系统性建议合并。修正：`RefactoringAdvisor` 默认 `maxServices = max(12, 现状服务数 × 1.5)`，报告注明参考分区参数与 seed（固定 seed 保证可复现）。
- **Q8**：web 视图可裁剪放最后；`/api/audit` 后端端点保留。
- **Q9**：findings 加稳定 id（type + 服务名 + 关键类集合的短 hash），为后续执行器留锚点。

## 5. 事实勘误（修订设计文档即可，不影响方案）

| 设计文档断言 | 核实结果 |
|---|---|
| 41 单测 + 4 集成测试（§4）；现有 45 测试（阶段 6） | 实际 **42 单测 + 4 IT = 46**（core 15 + codegen 3 + mcp 24；IT = GeneratedServicesRpcIT 1 + McpServerIT 1 + McpHttpIT 2）。文档两处数字自相矛盾 |
| `ProjectScanner.java:168` 目录推导 | :168 是 ClassInfo 构造点，推导逻辑在 `moduleNameOf`（:441-452）；且是目录路径而非 artifactId（见 §4-Q4） |
| `symmetrized()` 注释 "max(有向权重和, …)" | 代码内陈旧注释，实际实现是双向求和（DependencyGraph.java:109-121）。audit 指标以代码行为为准 |
| ClassInfo.java:13 mavenModule 字段 | ✅ 属实 |
| LouvainPartitioner.modularity :267 静态纯函数、接受任意 labels | ✅ 属实 |
| PartitionMetrics.of :30、labels 耦合 :33-46 | ✅ 属实 |
| buildResult 内联统计 :300-375 | ✅ 属实（fanIn/fanOut/intra :301-340，imbalance :342-348，useCaseSpan :350-375） |
| PartitionEngine :46 | ✅ 属实（run() 方法） |
| MCP 15 工具、web 5 端点、JsonIO 容错 + 版本盖章 | ✅ 属实 |

## 6. 实施前置清单（设计文档修订点汇总）

1. §5.2 指标改名与措辞（边界耦合率），Q2 定论写入；
2. §5.4 重构点 2 改写为"只下沉 imbalance/useCaseSpan，audit 指标独立实现"；
3. §5.3 补 maxServices 适配规则 + 固定 seed + 报告注明参考分区参数；
4. §5.1 services.json 加 `exclude`，module 匹配规则对齐 `moduleNameOf` 实际命名（或扩 artifactId 匹配）；
5. 反模式 #3/#6 公式精确化，#5/#6 扇入统计一遍扫边；
6. findings 模型加稳定 id + severity 分档规则；
7. §4/§6 测试数量勘误为 42 单测 + 4 IT = 46；
8. （后续项记录）Feign 接口依赖单独计数、PARTITIONED 下 landscape 复用。


---

# 附：阶段 0-5 实施复核（Kimi，2026-10-17 15:52，HEAD=0a94679 develop）

复核方式：不采信提交声明，独立重跑——全量构建、旧代码（e9c92f6）worktree 重建对拍、CLI 冒烟、新增代码逐文件评审。

## A. 独立核实通过

| 验证项 | 结果 |
|---|---|
| 全量构建 | `mvn verify` BUILD SUCCESS，70 单测 + 4 IT 全绿（core 43 = 旧 15 + audit 25 + PartitionMetrics 对拍 3；codegen 3+1IT；mcp 24+3IT） |
| 行为保持铁律（§4） | 旧代码 e9c92f6 检出独立 worktree 重建，同一输入跑 partition：**旧/新代码输出逐字节一致**（Q=0.7651972625483001，12 社区全同）——铁律实质达成 |
| 基线对拍声明 | zcode 的 after 运行（/tmp/m2m-after）与冻结基线 `docs/audit-baseline/ruoyi-partition-before.json` **全字段逐字节一致**，"byte-identical" 声明属实 |
| 现有代码改动面 | 仅 `PartitionMetrics` 重载 + `buildResult` 两处下沉（imbalance/useCaseSpans）+ `DependencyGraph` 注释修正；既有测试仅两处 IT 工具数断言 15→17（预期内） |
| 下沉等价性 | `imbalance`/`useCaseSpans` 逐行比对：遍历序与语义保留；`c != null && c >= 0` 与原 `c != null` 在 buildResult 上下文等价（finalLabels 无 -1） |
| CLI/MCP 冒烟 | `m2m audit -a <ruoyi analysis> --suggest` 实跑：misplaced 精准命中 framework→system 真实耦合（ConfigService/DictService/AsyncFactory），沙盘 delta 非零（阶段 5 修复有效），worsened 标注正常 |
| 设计还原度 | v2 §6 前置清单 8 条全部落地；severity 分档、稳定 id、双口径、maxServices 适配、seed 固定并注明均符合设计 |
| 实施主动性 | mergePair 取等号（纯互指上限 0.5）、hollowService 加 in>0 前置、沙盘目标对齐现状服务（否则 delta 恒零）——三个实施中发现并修复的都是真问题 |

## B. 发现的问题（按严重度）

### B1. 模度口径 bug：shared 幻影社区【建议阶段 6 前修】

`ArchitectureAuditor.audit` 把含 `-1` 的 `mainLabels` 直接传给 `PartitionMetrics.of(graph, labels, kernelSize, views)` 重载，该重载将此 map **原样**传给 `LouvainPartitioner.modularity`。`modularity` 的相等判定（`ci.equals(labels.get(j))`）使所有 shared 类共享标签 -1 而被视为**同一个幻影社区**：shared 类之间的边被计为社区内部边，Q 被抬高（RuoYi 冒烟值 0.3558 含此水分）。设计 §5.2.1 要求"shared 类剔除后计算"。

- 原 `of(graph, partition)` 路径无此问题（先过滤 -1 再算模度），分区主流程不受影响；
- `RefactoringAdvisor.snapshotOf` 同样受影响：沙盘 before/after 同口径，delta 相对比较仍有效，但绝对值偏离设计；
- **修法**（一行级）：算模度前过滤掉 -1 项——与 kernel 语义对齐（kernel 类留在图中但不进 labels）。注意保持 crossRatio 部分行为不变。

### B2. `--config` 阈值覆盖入口缺失【设计偏差，随阶段 6/7 收尾】

v2 §5.5 CLI 签名含 `[--config 阈值覆盖]`，Q1 定论写明"固定默认 + `--config` 可覆盖"。实现中 `AuditConfig` 具备完整 setter 与 `describe()`，但 CLI 与 MCP 均未暴露读取阈值配置的入口——当前实为纯固定阈值。补 CLI `--config <audit-config.json>`（JsonIO 读入即可，AuditConfig 已有 `@JsonIgnoreProperties(ignoreUnknown=true)`）；MCP 可选加 `configPath` 入参。

### B3. MCP 提示语指向不存在的文件【一行文案，随手修】

`McpTools.auditArchitecture` 返回的 note 称"完整报告（含每服务统计与生效阈值）见 audit.json"，但 MCP 路径从不写 audit.json（那是 CLI 行为）。改为返回完整 findings 分页指引，或改为"每服务统计与生效阈值见 summary/config 字段"并确实带出。

### B4. 基线未锚定输入，长期回归价值脆弱【流程建议】

复核中发现：15:05 重新 analyze RuoYi 所得图与 11:50 冻结基线时的图有 **47 条边权重漂移**（totalWeight 5901→5984，疑与 mvn 本地仓库解析状态随时间变化有关），导致"仓库 + 默认命令"无法复现基线。zcode 当时的对拍为真（输入一致），但基线作为长期回归 oracle 脆弱。建议：

1. 把生成基线所用的 `analysis.json` 一并冻结进 `docs/audit-baseline/`；
2. 对拍流程改为"以冻结的 analysis.json 为输入跑 partition，diff 输出"——真正隔离"重构是否改变分区行为"，不受 analyze 输入漂移干扰；
3. 在 baseline 目录附 README 记录生成命令与环境。

### B5. 文档同步小尾巴【随阶段 6/7 收尾】

- mergePair 实现取等号（`bidirectional >= ratio × minFlow`）、hollowService 加 `in > 0` 前置——两处修正均合理（AGENTS.md 有记录），但 `audit-design.md` §5.2.2 公式与 `AuditConfig` 注释仍写 `>`，请回写；
- `findingId` 实现为 `finding:<6位hex>`（不透明），设计示例为 `misplaced:com.x.SysUserServiceImpl:a3f2`（可读）。稳定性满足，建议把 type 前缀纳入 id 以保留可读性（`type:hash` 即可）；
- `RefactoringAdvisor.labelOf` 为 O(社区×类) 逐类扫描，整体 O(N²)——290 类无感，万级类仓库会钝；建议构建一次 fqcn→community 索引；
- 结构审视建议只覆盖"未配对的现状服务"，设计 §5.3 中"未配对的参考社区"的合并/吸收建议未显式产出（实际以"新建服务承接移动类群"形式部分体现）——确认是否为有意简化，若是请在设计文档注明。

## C. 结论

**阶段 0-5 批准**，质量高于预期（实施中主动暴露并修复三个真问题）。阶段 6 开始前请先修 B1（影响报告主指标之一的口径）与 B3；B2/B4/B5 随阶段 6/7 一并收尾。
