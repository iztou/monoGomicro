# monoGomicro 全面审核报告（2026-10-19）

审核对象：`develop` @ `9eaa312` + 未提交工作区改动（R-04 尾巴 / R-08 / R-09 / R-12 / R-13 / agent 静默织入 / CI）。

审核方式：全量构建与测试、CLI/MCP/Web 真实运行、RuoYi 4.8.3 端到端生成、冻结基线对拍、逐文件读码；
关键结论均给出可复现命令或实测输出。除本报告外未改动仓库任何文件。

> 结论：**不建议现在发布 0.3.0。**
>
> 全量 `mvn clean verify` 是绿的（81 单测 + 5 IT），但测试没有覆盖真实单体的关键路径：
> 生成出来的 RuoYi 工程 **`mvn package` 直接失败**、生成的服务 **启动即失败**，
> Web 控制台的「保存分区」**100% 返回 500**，MCP 存在一条可复现的**任意位置写文件**路径。
> 这些都是"测试绿但产品不可用"的典型盲区，优先级高于任何文档/风格问题。

---

## 0. 给评审者的说明

### 0.1 证据标记

| 标记 | 含义 |
|---|---|
| 【实测】 | 本次在本机实际执行命令/启动进程得到的结果，附录 A 有复现命令 |
| 【实测·子代理】 | 并行审计子代理在 `/tmp` 复现，命令与输出已回传；本文只保留可交叉验证的部分 |
| 【代码】 | 逐行读码确定，未运行 |
| 【推断】 | 路径可达但依赖特定分区/输入，未端到端复现 |

环境：macOS，JDK 17.0.6（Zulu），Maven 3.9.16，RuoYi 4.8.3 位于 `~/Work/gitRepo/RuoYi`。
codegen 的 IT 会 `mvn install` 生成工程，需要写 `~/.m2`；在受限沙箱下这一条会失败，属环境限制，不是项目缺陷。

### 0.2 本轮关注点

上一轮（`docs/project-review-2026-10-17.md`）已列出 R-01..R-17，并由 Kimi 复核。本轮不重复已修项，
重点放在：

1. 未提交修复本身是否正确（R-08/R-09/R-12/R-13/R-04 尾巴/agent 静默织入）；
2. 上一轮"修复"是否真的闭环（尤其 R-01/R-07/R-08 的失败策略）；
3. 既有测试**没有覆盖**的真实路径：真实单体的生成产物能否构建/启动、Web 端点、MCP 写盘边界；
4. 指标口径与文档数字是否自洽。

---

## 1. 验证基线

### 1.1 构建与测试【实测】

```bash
mvn -B clean verify
# BUILD SUCCESS；core 47 + codegen 10 + mcp 24 = 81 单测；
# agent IT 1 + codegen IT 1 + mcp IT 3 = 5 个集成测试；总耗时约 16s（依赖已缓存）
```

各模块单测：`core 47 / codegen 10 / mcp 24 / cli 0 / web 0 / agent 0`。
`cli` 与 `web` **没有任何测试**；`web` 在 CI 里只编译、零断言。

### 1.2 静态分析与基线对拍【实测】

```bash
java -jar monogomicro-cli/target/m2m.jar analyze ~/Work/gitRepo/RuoYi -p com.ruoyi \
  -o /tmp/ruoyi-check --no-maven-classpath
# 290 类 / 980 边 / 总权重 5901，与 AGENTS.md 一致

java -jar monogomicro-cli/target/m2m.jar partition \
  -a docs/audit-baseline/ruoyi-analysis-input.json -o /tmp/m2m-base-check
diff /tmp/m2m-base-check/partition.json docs/audit-baseline/ruoyi-partition-before.json
# 无差异（Q=0.7668238146123224，12 服务）
```

冻结输入对拍仍然逐字节一致，确定性没有问题。`m2m --version` 输出 `m2m 0.3.0`（读 manifest，R-14 已修）。

### 1.3 真实生成链路【实测】

用当前 `m2m-out/analysis.json` + `m2m-out/partition.json` 对 RuoYi 生成：

```bash
java -jar monogomicro-cli/target/m2m.jar generate \
  -a m2m-out/analysis.json -p m2m-out/partition.json \
  --project ~/Work/gitRepo/RuoYi -o /tmp/webtest --overwrite
# 生成完成：12 个服务 + 共享内核 / 264 个类 / 13 处注入改写 / 16 组 RPC 桥 / 21 个资源 / 4 项人工
```

随后在生成工程里执行：

```bash
cd /tmp/webtest && mvn -B -DskipTests package
# BUILD FAILURE @ svc-dict: Unable to find a single main class from the following candidates
#   [com.ruoyi.M2mDictApplication, com.ruoyi.RuoYiApplication]
```

启动其中一个已打包服务（`svc-user`）：

```bash
java -jar svc-user/target/svc-user-4.8.3.jar --server.port=18095
# APPLICATION FAILED TO START
# No qualifying bean of type 'com.ruoyi.system.mapper.SysUserMapper' available
```

这两条是本报告最重要的证据：**生成工程既不能 package，也不能启动**，而 `mvn verify` 全绿。

---

## 2. 上一轮 R 项复核

状态：✅ 已闭环；◐ 部分；❌ 未闭环/回归。

| 项 | 状态 | 复核结论 |
|---|---|---|
| R-01 生成器先删后校验 / 空 outDir | ◐ | 校验前置、cwd/项目根/祖先守卫、显式 overwrite 都正确；但 `-o` 指向 analysis 所在目录或一个普通文件时仍会删数据（A-07），符号链接与项目 `src` 子目录未防（A-20）。 |
| R-02 agent 入口（含上一轮 agent 静默织入） | ◐ | 独立 AgentBuilder、`--release 17`、onError 日志均正确；但 `AgentEntryIT` 只断言"存在 E 行"，锁不住 N/X 配对与用例重建（A-14/A-36）。 |
| R-03 MCP SSE | ✅ | 紧凑 mapper + 严格单行断言，实测 HTTP 正常。 |
| R-04 Web 安全 | ◐ | 绑定 127.0.0.1、前端 esc、overwrite 默认 false 已修；但 **`/api/save` 本身 100% 500**（A-03），且无 Host/Origin 校验（A-11），前端 generate 仍硬编码 `overwrite:true`（A-12）。 |
| R-05 PomReader | ✅ | parent-first 取坐标正确，回归测试在位。 |
| R-06 NaN 契约 | ◐ | 指标生产侧已修；`JsonIO` 序列化层仍会把 NaN 写成字符串 `"NaN"`（A-24）。 |
| R-07 tag 冲突 | ❌ | 目录去重只作用于 `serviceDirs`/`moduleTags`，`copyClasses`、`application.yml`、`portOfTag` 仍用未去重 tag；实测两个社区被合并（A-05）。 |
| R-08 RPC 桥失败策略 | ◐ | 白名单/去重/`Map.Entry` 已落地，但跳过方法后消费端调用点不改写 → 仍可能生成编译不过的代码（A-08）；wildcard/FQ/类型变量被误判（A-09）。 |
| R-09 mapper XML + RuoYi 硬编码 | ◐ | mapper 主路径正确、client 收敛按 basePackage 生效；但 `Files.walk(...,8)` 深度写死、`<base>/client` 过滤过宽（A-17），POM 编译级别未固定（A-18）。 |
| R-10 shared 词元 | ✅ | 边界匹配正确。 |
| R-11 退火空社区 | ✅ | 生产路径先 renumber，基线有意更新且对拍一致；公共 `AnnealingRefiner` 的 gapped 场景仍有偏差（A-37）。 |
| R-12 参数校验 | ◐ | CLI/MCP 入口已接；`isInfinite` 未查、`alpha+beta=0` 未拦、`ArchitectureAuditor.audit()` 公共 API 不内校验（A-28）。 |
| R-13 move 先验后改 | ✅ | 实测未知类/未知社区均 400，状态不变。 |
| R-14 README 漂移 | ◐ | 17 tools / agent 0.3.0 / audit 章节已同步；模块清单仍缺 MCP、case study 数字仍 0.81/15%（A-39）。 |
| R-15 `.zcode` | ✅ | 已删并 ignore；但 `.workbuddy/` 未 ignore（A-40）。 |
| R-16 CI | ◐ | workflow 已写但**未提交**；且 CI 只跑 verify，web/cli 零断言。 |
| R-17 backlog | ◐ | #7 仍在（audit 工具超 4KB，A-19）；#4 的兜底 catch 仍无 `log.error`，本轮未单列。 |

---

## 3. 阻断级发现（P0/P1）

### A-01（P0，实测）真实单体的生成工程无法 `mvn package`：原启动类与生成 Application 同模块

**证据**：`/tmp/webtest/svc-dict/src/main/java/com/ruoyi/` 下同时存在
`RuoYiApplication.java`（原单体启动类，`@SpringBootApplication` + main）和 `M2mDictApplication.java`；
生成的 `spring-boot-maven-plugin` 没有 `<mainClass>`。`mvn package` 报：

```
Unable to find a single main class from the following candidates
  [com.ruoyi.M2mDictApplication, com.ruoyi.RuoYiApplication]
```

**位置**：`MicroserviceGenerator.java:403`（`copyClasses` 原样拷贝）、`:668`（`writeApplicationClass`）、`:843`（POM 插件无 mainClass）。

**触发**：任何包含原始启动类的单体——Spring Boot 单体必然有。当前 RuoYi 分区把 `RuoYiApplication` 分到 dict-service。

**后果**：生成工程 `mvn compile` 通过（所以过去只验 compile 没发现），但 `package/install` 失败；
用户拿到的是不可构建的骨架。codegen IT 的 fixture 没有原启动类，因此永远测不到。

**修复**：拷贝阶段跳过原 `@SpringBootApplication`/main 类（记入 MIGRATION.md），或生成 POM 显式
`<mainClass>`；更稳妥的做法是两者都做。补一个"原启动类在服务社区内"的 IT。

---

### A-02（P1，实测）生成服务启动失败：`@MapperScan`/数据源配置被分到单个服务，生成 Application/yml 未继承

**证据**：`svc-user` 启动日志：

```
APPLICATION FAILED TO START
No qualifying bean of type 'com.ruoyi.system.mapper.SysUserMapper' available
```

`@MapperScan("com.ruoyi.**.mapper")` 所在的 `ApplicationConfig` 被分到 dict-service，`DruidConfig` 被分到
source-service；生成的 `M2m*Application` 是裸 `@SpringBootApplication`（丢掉了原 `exclude=DataSourceAutoConfiguration`），
`application.yml` 只有 `server/spring.application.name/svc`，原 datasource/mybatis/jackson 全部丢失。
MIGRATION.md 的 4 条人工项只列了共享类提升，没有列 MapperScan/数据源。

**位置**：`MicroserviceGenerator.java:668`（Application 生成）、`:703`（yml 生成）、`:403`（配置类原样拷贝）。

**后果**：除配置类所在的那个服务外，其余服务启动即失败；即使补了数据源，mapper 仍缺。

**修复**：把 `@MapperScan`/全局 `@Configuration` 强制提升到 common 或合并进生成的 Application；
yml 继承原 `datasource`/`mybatis`/`jackson`（或至少在 MIGRATION.md 里逐项列出）。
README 的"运行需要数据库/配置迁移"是笼统免责声明，不能替代具体人工项。

---

### A-03（P1，实测）Web `/api/save` 100% 返回 500：缺 `-parameters`

**证据**：启动 `monogomicro-web` 后：

```bash
curl -X POST 'http://127.0.0.1:18090/api/save?path=/tmp/ruoyi-check/partition-out.json'
# 500 {"status":500,"error":"Internal Server Error",...}
```

服务端栈：

```
java.lang.IllegalArgumentException: Name for argument of type [java.lang.String] not specified,
and parameter name information not available via reflection. Ensure that the compiler uses the '-parameters' flag.
```

`javap -v WorkspaceController.class | grep -c MethodParameters` → `0`。
web pom 不是 `spring-boot-starter-parent`，也没有开 `-parameters`；`@RequestParam String path` 依赖参数名反射。

**位置**：`WorkspaceController.java:132`、`monogomicro-web/pom.xml`（无 compiler 配置）。

**后果**：UI 的「保存分区」按钮永远失败；R-04 的 saveRoot 校验因此从未被真正执行过。

**修复**：`@RequestParam("path")` 显式命名，或全仓 compiler 加 `<parameters>true</parameters>`（推荐后者，
与 codegen 已生成的 `-parameters` 保持一致）；补一条 MockMvc 契约测试。

---

### A-04（P1，实测）MCP/CLI 的 `generate` 可被 analysis.json 内容诱导，越出 outDir 任意写文件

**证据**：构造一个 analysis.json，把 `packageName` 设为 `../../../../../../tmp/evil-write`，
`sourcePath` 指向项目内合法文件，然后正常执行 `m2m generate`：

```bash
java -jar m2m.jar generate -a /tmp/evil-analysis.json -p /tmp/evil-partition.json \
  -o /tmp/evil-out --project /tmp/mini-proj
# exit=0
find /tmp/evil-write -type f
# /tmp/evil-write/M2mXApplication.java
# /tmp/evil-write/A.java
```

**根因**：`copyClasses` 用 `info.getPackageName().replace('.', '/')` 直接拼目标路径；
`writeApplicationClass` 用 `commonPackagePrefix()`（来自同一批 packageName）拼 Application 路径。
两者都没有 `normalize()+startsWith(moduleRoot)` 校验。`sourcePath` 也直接 `projectDir.resolve(...)`，
绝对路径会被原样接受。

**位置**：`MicroserviceGenerator.java:403`（copy）、`:668`（Application）、`:245`（guardOutDir 只守 outDir，不守类目标路径）。

**后果**：MCP 是面向 LLM 的入口，`load_workspace` 接受任意 analysis.json；被诱导加载一个恶意文件后，
`generate_services` 可在 workspace/outDir 之外写文件（落点由 `packageName` 决定，文件名受
`sourcePath` 的文件名约束；足以覆盖 shell 配置、CI 配置等可写文件），内容来自 `sourcePath` 指向的文件。

**修复**：两层校验——① `load_workspace`/`generate` 入口校验 fqcn/packageName 是合法 Java 包名、
`sourcePath` 必须相对且不含 `..`；② codegen 对每个目标路径 `normalize()` 后必须落在 `moduleRoot`
（common 或 svc-*）内，`sourcePath` 解析后必须落在 `projectDir` 内。

---

### A-05（P1，实测）R-07 修复不完整：tag 冲突时两个社区被合并，yml 出现重复 key

**证据**：把 0/1 号社区改名为 `alpha` / `alpha-service`（两者 `serviceTag` 都是 `alpha`），生成：

```bash
jq '(.communities[0].name)="alpha" | (.communities[1].name)="alpha-service"' \
  m2m-out/partition.json > /tmp/tag-partition.json
m2m generate -a m2m-out/analysis.json -p /tmp/tag-partition.json -o /tmp/tagtest \
  --project ~/Work/gitRepo/RuoYi --overwrite
# 摘要：11 个服务（实际目录 12 个）——classesPerService 的 key 已被覆盖
for d in /tmp/tagtest/svc-alpha*; do echo "$d $(find $d/src/main/java -name '*.java'|wc -l)"; done
# svc-alpha   32
# svc-alpha-1  1     ← 只剩生成的 Application
grep -A1 '^  alpha:' /tmp/tagtest/svc-alpha/src/main/resources/application.yml
# alpha: 8081
# alpha: 8081      ← 重复 key，且两个 provider 都指向 8081
```

**根因**：去重 tag 只写进了 `serviceDirs`/`moduleTags`/`ownerOf`（`MicroserviceGenerator.java:297-310`），
但 `copyClasses`（`:329`）、`writeApplicationYml`（`:706`）、`portOfTag`（`:725`）重新用
`serviceTag(name,id)` 算了一遍，没有 `usedTags` 去重。`RpcBridgeGenerator` 的
`providerNameKey`（`:581`）同样基于未去重 tag。

**后果**：两个服务物理合并、第二个服务只剩空 Application、RPC 客户端共用同一个 base-url，
summary 服务数也错。RuoYi 默认分区恰好没有重名，所以既有 IT/样本没暴露；MCP 的 `rename_service`
允许任意服务名，很容易造出冲突。

**修复**：tag 只计算一次，存 `Map<communityId, dedupedTag>`，目录、yml key、端口、`providerNameKey`、
`classesPerService` 全部从这张表取。补 tag 冲突 + rename 后 generate 的回归测试。

---

### A-06（P1，实测）Web/MCP 展示的 modularity 与 partition.json 不是同一个数

**证据**：同一份分区：

```java
part.getModularity()                              // 0.7668238146123224（partition.json）
PartitionMetrics.of(report.toGraph(), part).modularity()  // 0.5476058662215673（web /api/state、MCP get_metrics）
```

按"kernel 剔除后诱导子图"重算应为 `0.7649872570516043`（子代理复算）。

**根因**：`PartitionMetrics.of()` 把 `graph.symmetrized()` 的**全图**传给 `modularity()`，
但只过滤了 labels（kernel 的 label 为 -1）。`modularity()` 用全图算 `m2` 与度数，
求和却只对两侧都有 label 的节点对进行——kernel 度数进了分母、没进分子，Q 被系统性压低。

**位置**：`PartitionMetrics.java:65-90`（`:89` 调用）、`LouvainPartitioner.java:267-295`。

**后果**：同一个分区在 CLI（0.77）和 Web/MCP（0.55）显示两个 Q；单服务分区会得到非 0 的 Q
（数学上应为 0）；`suggest_refactoring` 的 before/after 绝对值同样偏。

**修复**：算 Q 前构造仅含已标注节点的诱导子图（或让 `modularity()` 只累加两侧都在 labels 的边，
并用这批边的权重和作 `m2`）。补一条"非零 Q + kernel 内部重边"的精确数值断言，以及
`PartitionMetrics.of == partition.json Q` 的一致性测试。

---

### A-07（P1，实测）`m2m generate -o` 会删掉输入文件 / 普通文件（overwrite=false 也删）

**证据 A**：把 analysis/partition 放在输出目录里：

```bash
cp m2m-out/{analysis,partition}.json /tmp/del-test/
m2m generate -a /tmp/del-test/analysis.json -p /tmp/del-test/partition.json \
  -o /tmp/del-test --project ~/Work/gitRepo/RuoYi --overwrite
# 生成成功，但 /tmp/del-test/analysis.json 已不存在
```

**证据 B**：`-o` 指向一个普通文件：

```bash
echo important > /tmp/victim.txt
m2m generate ... -o /tmp/victim.txt --project ~/Work/gitRepo/RuoYi   # 未传 --overwrite
# exit=0；/tmp/victim.txt 被删除并变成目录
```

**根因**：`guardOutDir` 只拒绝空路径/cwd/项目根/其祖先；`isNonEmptyDir` 对普通文件返回 false，
于是绕过 `E_OUTPUT_EXISTS`，`deleteRecursively` 直接 `Files.walk(file)` 删除。

**位置**：`MicroserviceGenerator.java:245`（guard）、`:233`（isNonEmptyDir）、`:957`（delete）。

**后果**：`-o m2m-out`（与 analyze 同目录）是很容易犯的错，会静默删掉本次输入；`-o pom.xml` 这类
手滑会删用户文件。这是 R-01 同一类数据丢失，只是入口从"空路径"换成了"输入目录/普通文件"。

**修复**：拒绝"已存在且不是目录"的输出路径；拒绝 outDir 等于/包含 `-a`、`-p` 的父目录；
`deleteRecursively` 用 `Files.delete` 并在失败时抛错；最好改成临时目录生成后原子替换。

---

### A-08（P1，代码核实）R-08 跳过方法后，消费端调用点仍指向 Client → 仍会生成编译不过的代码

**位置**：`RpcBridgeGenerator.java:121-153`（过滤/去重）、`MicroserviceGenerator.java:584-593`（只要一侧非空就 `rewriteInjections`）、`:616`（改写注入）。

**机制**：`bridge.generate()` 只要有任一方法被保留就返回 true；消费类里的字段/构造器/参数被改成
`<Provider>Client`，但方法体里 `providerService.someSkippedMethod(...)` 原样保留——被跳过的方法不在
Client 接口里，`mvn compile` 报 `cannot find symbol`。两侧方法都为空时返回 false、不改写，
消费类则仍引用只在 provider 模块的接口，同样编译失败。

**触发**：被调用方法带未知/外部类型、`? extends`、类型变量或被去重的重载。R-08 之后跳过面变大，
与"底线 = 不生成编译不过的代码"直接冲突。

**修复**：跳过方法时同时列出调用点并让生成失败（或为跳过方法生成保底签名）；`clientMethods` 为空
必须返回 false 并把原接口复制到消费模块；补"重载/外部类型"fixture 的编译级 IT。

---

### A-09（P1，代码核实）R-08 白名单过窄：wildcard / 全限定名 / 类型变量被误判为"不可处理"

**位置**：`RpcBridgeGenerator.java:34-59`（白名单）、`:196-205`（`collectNames`）。

**机制**：`collectNames` 用正则提取标识符并跳过 `.` 后的段，于是：

| 签名 | 提取结果 | 结果 |
|---|---|---|
| `List<? extends Foo>` | `List`, `extends`, `Foo` | `extends` 不在白名单 → 整方法丢弃 |
| `com.foo.Bar` | `com`（`foo`/`Bar` 被跳过） | 整方法丢弃 |
| `<T> T get()` | `T` | 整方法丢弃 |

`JAVA_LANG` 还缺 `Iterable/Comparable/Void/CharSequence/Runnable`；`KNOWN_JDK_IMPORTS` 缺
`Stream/CompletableFuture/java.sql.Timestamp`；`MultipartFile/PageInfo/HttpServletRequest` 等外部类型一律丢。

**后果**：大量本可桥接的方法被静默列入人工项；叠加 A-08 就是编译失败。

**修复**：改用 JavaParser AST + provider 源文件 import 表解析类型，显式处理 `?/extends/super/类型变量`；
白名单补常用 JDK 类型；外部类型按"原项目依赖可达"放行，而不是按简单名猜。

---

## 4. 高优先发现（P2）

### A-10（P2，实测·子代理）MCP 的 workspace 根未 normalize：`projectDir:"./proj"` 后默认保存/生成全部报"越界"

`WorkspaceSession` 里 `projectDir.toAbsolutePath()` 保留 `./`，`workspaceRoot` 变成
`/path/./proj/m2m-out`；待校验路径 `toAbsolutePath().normalize()` 后不含 `./`，`startsWith` 必为 false。

```bash
# stdio: analyze_project{projectDir:"./proj"} 成功
# save_partition{} → E_OUT_OF_WORKSPACE "保存路径越出 workspace 根：/private/tmp/..././proj/m2m-out/partition.json"
```

位置：`WorkspaceSession.java:117-118`、`:164`、`:352-358`、`:384-390`；`McpTools.java:60-62`。
LLM 常发相对路径，这是主流程级缺陷。修复：赋值与比较前统一 `toAbsolutePath().normalize()`。

### A-11（P2，实测）Web 无 Host/Origin 校验，DNS rebinding 可驱动全部写接口

绑定 127.0.0.1 已修，但没有 Host 白名单（MCP 模块有 `LoopbackHostValidator`，基线不一致）：

```bash
curl -H 'Host: evil.example:18090' http://127.0.0.1:18090/api/state
# 200（已载入数据时返回完整图）
```

恶意页面把域名重解析到 127.0.0.1 后即同源，可读 `/api/state`、调 `/api/generate --overwrite` 清空目录。
修复：`OncePerRequestFilter` 校验 `Host ∈ {127.0.0.1:port, localhost:port, [::1]:port}`，
`Origin` 存在时必须同源；与 MCP 共用同一套逻辑。

### A-12（P2，实测）Web 前端硬编码 `overwrite:true`，且 `E_OUTPUT_EXISTS` 被映射成无信息的 500

- `index.html:221` 固定 `{overwrite: true}`，无二次确认；
- 后端没有 `@ControllerAdvice`，`M2mException` 全变成 500：

```bash
# 目标目录非空、overwrite=false：
curl ... /api/generate -d '{"projectDir":"...","outDir":"/tmp/webtest","overwrite":false}'
# 500 Internal Server Error（真实原因 E_OUTPUT_EXISTS 在 UI 完全不可见）
```

修复：前端默认 false + 显示将清空的绝对路径 + 二次确认；后端把 `M2mException` 映射为 4xx + `{code,message,hint}`。

### A-13（P2，实测）MCP HTTP 4xx 把 `McpError` 当 Java 异常序列化，泄露堆栈

```bash
curl -X POST http://127.0.0.1:18091/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# {"cause":null,"stackTrace":[{"className":"io.modelcontextprotocol.spec.McpError$Builder",...},
#  {"className":"...HttpServletStreamableServerTransportProvider","lineNumber":574}, ...]}
```

位置：`McpServerMain.java:95-104`（provider 复用 `JsonIO.compactMapper()`）。
暴露 SDK/Jetty 版本与行号，且不是 JSON-RPC envelope。修复：给 provider 的 mapper 注册 `McpError`
自定义 serializer，只输出 `{"jsonrpc":"2.0","id":null,"error":{...}}`。

### A-14（P2，实测）agent：方法抛异常时只写 N 不写 X，N/X 栈永久失衡

`MethodAdvice` 的 `@Advice.OnMethodExit` 没有 `onThrowable`（默认 `NoExceptionHandler`，已用反射核实），
业务方法抛异常时不会执行 exit advice；`TraceAnalyzer` 只在 `X` 时 pop，`N` 时 push，`E` 时 clear。
异常路径之后，同一用例内所有 `N` 的 caller 都错位，动态权重与用例跨度失真。

位置：`TraceAgent.java:127-137`、`TraceAnalyzer.java:58-68`。
修复：`@Advice.OnMethodExit(onThrowable = Throwable.class)`（异常路径也写 X 或单独的 T），
解析器做栈平衡校验；`EntryAdvice` 也补 exit。

### A-15（P2，实测）扫描器静默丢弃语法错误文件，报告"成功但缺类"

`ProjectScanner.java:151-156` 在 `result.getResult()` 为空时 `log.debug` 后 `continue`，slf4j 默认 INFO 不可见。

```bash
# p/A.java 正常，p/B.java 有语法错
m2m analyze /tmp/brokenproj -p p --no-maven-classpath
# exit=0，classCount=1，无任何警告；若全部文件都坏，报的是 E_PKG_NO_MATCH（误导）
```

修复：统计 parseFailures 写进 `AnalysisReport` 并由 CLI 汇总；失败至少 WARN；0 类且有解析失败时
给 `E_PARSE_FAILED`，不要报"包前缀不匹配"。

### A-16（P2，实测）扫描器类型位置覆盖缺口：record/instanceof/cast/throws/catch/构造器/局部变量/类型参数/方法引用

实测同一包内最小样例：

```java
record R(B b) {}                       // 无边
void f(Object o) throws Ex {
    if (o instanceof B) { B b=(B)o; }  // 无边
    try {} catch (Ex e) {}             // 无边
}
void g() { new C(); }                  // 只有 A->C
```

位置：`ProjectScanner.java:257-304`（`extractEdges`）。只遍历字段、方法返回/参数、注解、import、方法调用、new、类字面量、NameExpr；
没有 `RecordDeclaration`、`CastExpr`、`InstanceOfExpr`、`CatchClause`、`ConstructorDeclaration`、
`TypeParameter` 上界、`MethodReferenceExpr`、局部变量类型。RuoYi 因显式 import 兜底掩蔽率高，
同包/通配符导入/record 密集的新代码会漏边；共享内核闭包据此判断提升，漏边会导致生成工程编译失败。

修复：补上述 AST 节点；补"每类硬依赖一个最小 fixture"的单测（现在扫描器测试只断言节点集合，
把 edges 全删也能过）。

### A-17（P2，代码核实）R-09 client 收敛深度写死 8 + 过滤过宽

`consolidateClientsToCommon` 用 `Files.walk(outDir, 8)`。client 文件深度 = 6 + basePackage 段数：
`com.demo` 恰好 8，`com.demo.app` 是 9，会被漏掉（子代理实测 depth8=0 / depth9=1）。
`contains("/src/main/java/<base>/client/")` 还会把原项目自带的 `<base>.client.*`（Feign 常见）
当成本次生成的客户端搬进 common，并可能用同名旧类顶替生成类。

修复：由桥生成器返回本次生成的精确路径清单；或按 Path 组件逐段匹配，并排除非本次生成的文件。

### A-18（P2，代码核实）生成 POM 未固定编译级别

生成的 `maven-compiler-plugin` 只配 `<parameters>true</parameters>`，没有 `release/source/target`；
`${maven.compiler.source}` 默认 1.8（插件默认值）。原单体若用了 record/var/text block，生成工程
编译失败。根 POM 只复制 `java.version` 属性，没有 `maven.compiler.release`。
另外 `externalDependencies` 只按 `groupId == projectGroupId` 过滤，子模块 groupId 不同时会留下
原模块坐标；`repositories` 未复制。

修复：生成 `maven.compiler.release`（原值或 17）；按原 module 坐标集合过滤内部依赖；复制 repositories。

### A-19（P2，实测·子代理）audit 工具返回体超设计上限，无分页/截断

位置：`McpTools.java:418-492`。`audit_architecture`/`suggest_refactoring` 把 findings/moves 全量返回。RuoYi 样本 25 条 findings，
compact JSON 实测 **11.4KB**（suggest 10.3KB），超过设计文档的 ≤4KB 目标、8KB 硬上限。

修复：findings 分页（page/pageSize 或 topN+total+truncated），moves 限 topN；补响应体大小断言。

### A-20（P2，代码核实）R-01 守卫仍可被符号链接/普通文件/项目 src 子目录绕过

`guardOutDir` 只做 `toAbsolutePath().normalize()` 文本比较，不 `toRealPath()`；`outDir` 经中间
符号链接指向项目时，`Files.walk` 会遍历真实目录并删除（子代理实测）；`-o src` 会删掉单体源码。
与 A-07 同根因，建议一起修：`toRealPath()/fileKey` 比较 + 拒绝项目内源码树 + 拒绝非目录。

### A-21（P2，代码核实）RPC 桥运行期序列化只对 String/基本类型成立

位置：`RpcBridgeGenerator.java:246-339`（client/controller 模板）。

- `Foo[]` 返回：`(Foo[]) result`，但 `postForObject(..., Object.class)` 对 JSON 数组返回 `ArrayList` → CCE；
- `new ObjectMapper()` 不注册 JavaTimeModule、不继承 `spring.jackson`：`LocalDateTime` 返回 500，
  `Date` 变成 epoch 数字后 `(Date) result` → CCE；
- 单参 `Map.of("p0", x)` 在 `x == null` 时 NPE（`Map.of` 不允许 null）；
- `char` 返回 `(Character) result`（实际是 String）→ CCE。

修复：注入 Spring 的 ObjectMapper；数组/日期用 `TypeFactory` 显式转换或列入不可处理；单参用
`HashMap` 允许 null。补对应 fixture。

### A-22（P2，实测）`minCommunitySize` / `maxServices` 是"软约束"，实际经常不满足且无提示

```bash
m2m partition -a docs/audit-baseline/ruoyi-analysis-input.json \
  -o /tmp/min30 --min-size 30 --max-services 50
# 23 个服务，20 个 < 30（最小 1 类）
```

`mergeSmallCommunities` 对无跨边的小社区直接保留，`attachIsolated` 在退火之后还能再抽小。
`maxServices` 在等大孤立分量时也可能不满足（子代理用两个 3 类三角簇复现：`--max-services 1` 得 2 个服务）。
CLI 帮助写的是"服务最小类数量"，用户会当真。

修复：退火/attach 后再做一轮 undersized 收敛（孤立并入最大社区）；或在文档/CLI 明确标为软约束并
在违反时输出警告；补约束测试。

### A-23（P2，代码核实）`sharedAbuse` 在只有 1 个业务服务时是同义反复

`ArchitectureAuditor.java:358-371`：`half = (businessCount + 1) / 2`，`businessCount=1` 时 `half=1`，
凡被唯一业务服务依赖的 shared 类都会报"被 1/1 个业务服务依赖（全部）"，severity=warning。
单体体检是工具主场景，属系统性误报。

修复：`businessCount < 3` 跳过该类反模式，或用"占比 + 绝对数"双条件；补 1/2 服务测试。

---

## 5. 低优先发现（P3）

> 下表为**代码核实**或**子代理实测**的汇总，未逐条在本机复跑；涉及数值的条目已在正文/附录给出复现方式。

| ID | 发现 | 位置 | 说明 |
|---|---|---|---|
| A-24 | `JsonIO` 序列化层 NaN/Infinity 仍写成字符串；`classes:null` 会 NPE；formatVersion 不校验 | `JsonIO.java:16-40`、`AnalysisReport.java:60` | R-06 只修了生产者；建议关闭 `QUOTE_NON_NUMERIC_NUMBERS`、读入时校验版本区间、`@JsonSetter(nulls=AS_EMPTY)` |
| A-25 | `ClassStereotype` 的 `BaseEntity` 判定永假 | `ClassStereotype.java:57` | 传入的是简单名 `BaseEntity`，却比较 `.baseentity`；不在 `.domain./.entity./.model.` 包的实体被判 OTHER |
| A-26 | `DependencyGraph.symmetrized()` 对未注册端点 NPE；`putEdge` 绕过自环守卫 | `DependencyGraph.java:71-121` | analysis.json 是用户可编辑输入；建议 `computeIfAbsent` 或 `putEdge` 自动补节点/拒绝未知端点 |
| A-27 | `services.json` 的 `services:null`/`exclude:null` 抛裸 NPE | `ServicesConfig.java:89-103`、`:123-125` | 实测 CLI 输出 `Cannot invoke "java.util.List.iterator()"` + usage；应给 `E_BAD_SERVICES_JSON` |
| A-28 | `AuditConfig.validate` 不查 `isInfinite`；`alpha+beta=0` 被放行；`ArchitectureAuditor.audit()` 公共 API 不内校验 | `AuditConfig.java:97-114`、`PartitionOptions.java` | `1e999` 通过校验后 mergePair 反模式静默消失；`alpha=beta=0` 输出"正常"的空分区 |
| A-29 | 扫描器同名简单类跨包误连；`Files.walk` 顺序影响归属；实例状态不复位 | `ProjectScanner.java:233-234`、`:386-389`、`:63-65` | 同包 `Helper` 可能连到另一包的 `Helper`；复用 scanner 会丢边 |
| A-30 | 扫描器 import 兜底把未使用 import 也连边，且统一记 PARAM_RETURN | `ProjectScanner.java:281-286` | 假依赖；RuoYi 样本未使用 import 为 0，当前无影响 |
| A-31 | 扫描器 symlink 源码根被跳过、重复 FQCN 静默覆盖、`/m2m-out/` 硬编码排除 | `ProjectScanner.java:138-142`、`:160-175`、`:426` | 单仓多模块/自定义输出目录时漏类或自我污染 |
| A-32 | `mapToRegistered` 每次未命中做 O(注册类数) 前缀扫描 | `ProjectScanner.java:340-343` | 子代理微基准：20k 注册类 × 2000 外部引用 ≈ 3.4s；对宣称的 20k 文件规模不可用 |
| A-33 | agent trace 文件安全/可靠性：默认 `/tmp`、0644、跟随符号链接、写失败后 `running` 仍为 true | `TraceAgent.java:20`、`TraceRecorder.java:30-45` | 同机用户可读内部类名；写失败后静默丢全部数据 |
| A-34 | agent 织入错误日志无节流；启动横幅写 stdout；`entryMethod` 会被 `entry` 重置；空 `entry=` 匹配全部类型 | `TraceAgent.java:34-38`、`:74-81`、`:112` | 宿主 JDK 更新时可能刷屏几千行；stdout 污染协议型宿主 |
| A-35 | Advice 的 `suppress` 默认是 `NoExceptionHandler`（不抑制），织入代码异常可抛进业务方法 | `TraceAgent.java:119-137` | 建议显式 `suppress = Throwable.class`，并拒绝 `java./javax./jdk./sun.` 包 |
| A-36 | `AgentEntryIT` 断言弱：只查"存在 E 行" | `AgentEntryIT.java:41-81` | 不校验 E 数量/类型、N/X 配对、`TraceAnalyzer` 能重建用例、异常路径 |
| A-37 | 公共 `AnnealingRefiner` 的 `useCaseSpanNorm` 仍按 `max(label)+1` 含空槽 | `AnnealingRefiner.java:39`、`:153-177` | 生产路径先 renumber 不触发；公共 API 直接调用会偏好"带空洞"的分组 |
| A-38 | `suggest_refactoring` 的 move delta 不累积；目标名撞 shared 服务时会被标成 -1 | `RefactoringAdvisor.java:328-389` | 正常路径是真重算；多条 move 合并执行后的实际收益不可加，应在 harness/文档说明 |
| A-39 | 文档漂移 | `README.md`、`README.zh-CN.md`、`docs/mcp-design.md`、`AGENTS.md` | README 仍写 Q=0.81 / ~15% 跨服务（实测 0.765 / 19.7%）；架构清单缺 MCP/audit；mcp-design 仍写"15 工具/五模块"；AGENTS 仍写 71 单测/4 IT（现 81/5） |
| A-40 | 仓库卫生 | `.gitignore`、`.github/`、`.workbuddy/` | CI workflow 未提交；`.workbuddy/` 未 ignore；本轮修复整体未提交；`m2m-out/analysis.json` 是旧格式（totalWeight 5970 vs 5901），作为证据容易误导 |
| A-41 | 测试盲区 | 全仓 | web 0 测试、CLI 0 测试、`RpcBridgeGenerator` 0 单测、扫描器只测节点不测边、`GenerateOptionsGuardTest.freshTargetPassesGuard` 吞掉所有非 `E_OUTPUT_EXISTS` 异常、IT 在无 mvn 时静默跳过 |

---

## 6. 亮点与保持项

- 六模块边界清晰，core 仍是零框架库；`PartitionEngine`/`LouvainPartitioner` 手写实现可读。
- 冻结输入对拍文化很好：`docs/audit-baseline` 的"analyze 会漂移、必须用冻结输入"注释是正确姿势；
  本轮复跑 diff 为空。
- codegen 的运行级 IT 是整个仓库最有价值的测试（生成 → install → 双服务 → HTTP 断言 RPC 桥），
  它确实挖出过四个只在运行时暴露的缺陷。
- MCP 的协议层（紧凑 JSON、SSE 分帧、Host 白名单、结构化错误、进度通知）整体扎实；
  stdio/HTTP 双传输各有真子进程契约测试。
- 审计功能有设计文档、review 文档、真实样本记录；`boundaryCoupling` 剔除 shared 的口径在代码注释里写清楚了。
- agent 的 `--release 17` 修复和 onError listener 是正确方向；`TraceRecorder` 的"队列满不阻塞业务线程"设计正确。

---

## 7. 测试与 CI 盲区（按影响排序）

1. **没有"真实单体生成产物能构建/启动"的测试**：IT fixture 没有原启动类、没有 `@MapperScan`、
   没有 datasource、没有外部类型/重载，恰好绕开 A-01/A-02/A-08/A-18。
2. **web 完全没有 `src/test`**：A-03（save 500）、A-11（Host）、A-12（overwrite/500）本可以被
   一条 MockMvc 测试抓住。
3. **R-08/R-09 修复没有单测**：`RpcBridgeGenerator` 没有测试类，`consolidateClientsToCommon`/
   `copyMapperXml` 只靠 IT 间接覆盖，而 IT 的 `com.demo` 恰好卡在深度 8 边界。
4. **扫描器测试只断言节点集合**：`ProjectScannerExclusionTest` 把 edges 全删也能过；
   AGENTS.md 自称"刻意覆盖"的 @interface/枚举/泛型/字段注解/类字面量/varargs 没有一条边断言。
5. **约束类参数没有测试**：`--min-size`/`--max-services` 违反了也没人知道（A-22）。
6. **指标一致性没有测试**：`PartitionMetrics.of` 与 `partition.json` 的 Q 从未对拍（A-06）。
7. **CI 只有 `mvn -B verify`**：没有 web/CLI 断言，没有响应体大小断言，没有 stdout 纯净度检查；
   且 `.github/workflows/ci.yml` 目前未提交，等于没有 CI。
8. **IT 的 `Assumptions.assumeTrue(mvnAvailable())`**：CI/环境缺 mvn 时会"绿着跳过"，应改为 fail。

---

## 8. 修复顺序建议

### 第一批（发布 0.3.0 前必须）

1. A-01 双 main + A-02 MapperScan/配置/yml：让真实 RuoYi 生成工程能 `mvn package` 并至少启动到
   "缺数据库"而不是缺 Mapper；补真实形态的 codegen IT。
2. A-04 任意写：入口校验包名/sourcePath + codegen 目标路径 `startsWith` 校验；这是安全问题。
3. A-03 `/api/save` 500：`@RequestParam("path")` 或全仓 `-parameters`。
4. A-05 tag 冲突：tag 只算一次；补 rename/冲突 generate 测试。
5. A-06 modularity：诱导子图重算；补一致性测试。
6. A-07/A-20 输出目录数据丢失：拒绝输入父目录/普通文件/项目 src/符号链接逃逸。
7. A-08/A-09 RPC 跳过策略：要么生成可编译代码，要么明确失败，不能"静默跳过 + 让消费端编译失败"。

### 第二批（输出正确性/主流程）

8. A-10 MCP `./` normalize；A-11 Host/Origin；A-12 web error mapping/前端 overwrite。
9. A-13 MCP 4xx 错误体；A-19 audit 工具分页；A-14 agent N/X 配对。
10. A-15/A-16 扫描器解析失败可见 + 类型位置补全；A-17 client 收敛深度；A-18 编译级别。
11. A-21 RPC 序列化；A-22 约束收敛；A-23 sharedAbuse 阈值。

### 第三批（质量/文档）

12. A-24..A-41 按表处理；优先补 web 测试、扫描器边测试、RpcBridge 单测、CI 提交与 web/CLI 断言。
13. 文档数字以可复现命令为准更新（Q=0.765、crossRatio=19.7%、81 单测/5 IT、六模块）。

---

## 附录 A：关键复现命令

```bash
# 0) 全量构建（需要写 ~/.m2；受限沙箱会卡在 codegen IT 的 mvn install）
mvn -B clean verify

# 1) 真实 RuoYi 生成 + 构建 + 启动
java -jar monogomicro-cli/target/m2m.jar generate \
  -a m2m-out/analysis.json -p m2m-out/partition.json \
  --project ~/Work/gitRepo/RuoYi -o /tmp/webtest --overwrite
cd /tmp/webtest && mvn -B -DskipTests package
# 期望：svc-dict 双 main 失败
java -jar svc-user/target/svc-user-4.8.3.jar --server.port=18095
# 期望：No qualifying bean SysUserMapper

# 2) Web save 500
java -jar monogomicro-web/target/monogomicro-web.jar --server.port=18090 &
curl -X POST -H 'Content-Type: application/json' \
  -d '{"analysisPath":"/tmp/ruoyi-check/analysis.json"}' http://127.0.0.1:18090/api/load
curl -X POST 'http://127.0.0.1:18090/api/save?path=/tmp/ruoyi-check/x.json'
# 期望：500 + IllegalArgumentException(-parameters)

# 3) 任意写（packageName 穿越）
java -jar monogomicro-cli/target/m2m.jar generate \
  -a /tmp/evil-analysis.json -p /tmp/evil-partition.json \
  -o /tmp/evil-out --project /tmp/mini-proj
find /tmp/evil-write -type f   # 应在 outDir 之外出现 A.java/M2mXApplication.java

# 4) tag 冲突
jq '(.communities[0].name)="alpha" | (.communities[1].name)="alpha-service"' \
  m2m-out/partition.json > /tmp/tag-partition.json
java -jar monogomicro-cli/target/m2m.jar generate \
  -a m2m-out/analysis.json -p /tmp/tag-partition.json \
  -o /tmp/tagtest --project ~/Work/gitRepo/RuoYi --overwrite

# 5) modularity 不一致
# 见 /tmp/qcheck.jsh：part.getModularity()=0.7668，PartitionMetrics.of=0.5476

# 6) CLI 删输入/普通文件
cp m2m-out/{analysis,partition}.json /tmp/del-test/
java -jar monogomicro-cli/target/m2m.jar generate \
  -a /tmp/del-test/analysis.json -p /tmp/del-test/partition.json \
  -o /tmp/del-test --project ~/Work/gitRepo/RuoYi --overwrite
ls /tmp/del-test/analysis.json   # 不存在

# 7) 扫描器静默丢类
java -jar monogomicro-cli/target/m2m.jar analyze /tmp/brokenproj -p p \
  --no-maven-classpath -o /tmp/broken-out
jq '{classes:[.classes[].fqcn]}' /tmp/broken-out/analysis.json

# 8) MCP HTTP 错误体泄露
java -jar monogomicro-mcp/target/m2m-mcp.jar --http --port 18091 &
curl -s -X POST http://127.0.0.1:18091/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 附录 B：本轮未覆盖

- Windows 行为（项目已声明 macOS/Linux，未验证）。
- 真并发压测（web ConsoleState 竞态、MCP move delta 跨锁）——按代码路径判断存在风险，未做压力测试。
- RuoYi-Cloud 审计样本复跑（本机无该仓库；只复跑了 RuoYi 单体）。
- 真实 LLM 客户端（Claude Desktop/Inspector）端到端——只用裸 HTTP/stdio 客户端验证。


---

# 附 C：Kimi 交叉复核（2026-10-19，对附 A/附 B 即本报告正文）

复核方式：对 P0/P1 全部 9 条 + 涉及 Kimi 上轮修复的 P2 条目（A-10/A-12/A-14/A-17/A-25/A-28）逐一到代码核实；
其余 P2/P3 未逐行复核，按 deepseek 标注的证据等级采信。

## C.1 总体结论

**本报告可信，结论「不建议现在发布 0.3.0」成立。** 抽查 15+ 条全部属实或基本属实，
未发现捏造、夸大或张冠李戴；机制分析（尤其 A-06 的 Q 口径、A-03 的 -parameters）与代码逐行吻合。
A-01/A-02 揭示的「测试绿但产品不可用」是本仓库当前最真实的发布风险，根因是 codegen IT fixture
太干净（无原启动类、无 @MapperScan、无数据源），恰好绕开了真实单体的关键形态。

## C.2 P0/P1 逐条核实

| 项 | 核实结论 | 代码证据 |
|---|---|---|
| A-01 双 main | ✅ 证实 | `copyClasses` 原样拷贝全部类不跳过原 `@SpringBootApplication`（MicroserviceGenerator:403-425）；生成 POM 的 spring-boot-maven-plugin 无 `<mainClass>`（:841-854）；`writeApplicationClass` 在同包再加一个 main（:668-689）。三者叠加，原启动类所在服务 `mvn package` 必失败 |
| A-02 MapperScan/数据源 | ✅ 证实 | 生成 Application 是裸 `@SpringBootApplication` 无 exclude（:672-684）；yml 只有 server/name/svc 三段（:703-721，Kimi 上轮改 yml 时亲见）；配置类按分区原样拷贝，无提升逻辑 |
| A-03 /api/save 500 | ✅ 证实 | 根 pom 与 web pom 均无 `-parameters` 配置；`@RequestParam String path` 未显式命名（WorkspaceController:132）。**推论：Kimi 上轮修的 R-04 saveRoot 校验从未被真正执行过（请求在参数绑定阶段即失败），该修复的有效期从本轮才开始** |
| A-04 packageName 穿越 | ✅ 证实 | `projectDir.resolve(sourcePath)` 对绝对路径原样接受（:410）；目标路径由 `packageName.replace('.','/')` 直拼、无 normalize+startsWith 校验（:419-421）。`writeApplicationClass` 同构（:685-686）。MCP 面向 LLM 入口，威胁模型真实，P1 评级合理 |
| A-05 tag 冲突 | ✅ 证实 | 去重只进 `serviceDirs`/`moduleTags`（:296-310）；`copyClasses`（:329）、`writeApplicationYml`（:711-712，Kimi 上轮编辑处原样保留重算）、`portOfTag`（:727-736）、`providerNameKey`（:581 用 `serviceTag(name, 0)` 重算）全部绕过 `usedTags` |
| A-06 modularity 口径 | ✅ 证实，机制分析准确 | `modularity()` 的 `degree`/`m2` 用全图累加不过滤 labels（LouvainPartitioner:268-277），分子只对双侧有 label 的边对求和（:286）——kernel 度数进分母不进分子，Q 系统性压低。**这是 B1（幻影社区）修复的遗留：zcode 修了 labels 侧，没跟着修分母侧** |
| A-07 删输入/普通文件 | ✅ 证实 | `isNonEmptyDir` 对普通文件返回 false（:269-276）→ 绕过 E_OUTPUT_EXISTS → `deleteRecursively` 直删；guard 无 `-a`/`-p` 输入路径概念（生成器根本没收到这两个路径） |
| A-08 跳过方法+改写注入 | ✅ 证实 | `generate()` 只要任一侧方法非空即返回 true（RpcBridgeGenerator:153）→ `rewriteInjections` 把字段类型改成 XxxClient（MicroserviceGenerator:591-592）→ 消费类方法体里对被跳过方法的调用原样保留 → cannot find symbol。**Kimi 上轮 R-08 扩大了跳过面却没闭环这条路径，javadoc 写的「底线=不生成编译不过的代码」实际未达成，deepseek 的批评成立** |
| A-09 白名单误判 | ✅ 证实 | `List<? extends Foo>` 提取出 `extends` 进 bad 集；`com.foo.Bar` 提取出 `com`；`<T> T` 的 `T`；JAVA_LANG 缺 Iterable/Comparable/Void/CharSequence/Runnable（RpcBridgeGenerator:34-59、:196-205 逐行吻合） |

## C.3 涉及 Kimi 上轮修复的 P2 复核（deepseek 判 ◐ 的公正性）

| 项 | 核实结论 |
|---|---|
| A-28（R-12 残余） | ✅ 证实：`AuditConfig.validate()` 只查 `isNaN \|\| < 0`，错误文案写「非负有限数」但实际不拦 `1e999`（+Infinity）——文案与检查不一致；`alpha+beta=0` 确实放行。Kimi 的 R-12 有这两个瑕疵，判 ◐ 公正 |
| A-17（R-09 残余） | ✅ 证实：`consolidateClientsToCommon` 的 `Files.walk(outDir, 8)` 深度参数在 Kimi 修复时原样保留——`com.demo` 两段包恰好 8，三段包即漏；`contains` 过滤会把原项目自带 `<base>.client.*`（Feign 常见）误搬 |
| A-10 | ✅ 证实：`WorkspaceSession.java:117-118` 与 `:164` 均 `toAbsolutePath()` 未 normalize，与校验处的 normalize 不对称，相对路径输入必误判越界 |
| A-12 | ✅ 证实：`index.html:221` 硬编码 `overwrite: true`，无二次确认 |
| A-14 | ✅ 证实：`@Advice.OnMethodExit` 无 `onThrowable`，ByteBuddy 默认语义下异常退出不执行 exit advice，N/X 栈失衡机制成立 |
| A-25 | ✅ 证实：`ClassStereotype.java:57` 对简单名 `BaseEntity` 比较 `.baseentity`（前导点），永假 |

未逐行复核：A-11/A-13/A-15/A-16/A-19/A-21/A-22/A-23/A-24/A-26/A-27/A-29..A-41——
均为实测（附复现命令）或代码核实型，与已核实条目的准确率一致，整体采信。

## C.4 补充与不同意见

1. **A-03 修复建议取后者**：根 pom 给 maven-compiler-plugin 加 `<parameters>true</parameters>` 一处生效
   （生成工程已这么配，本仓编译插件配置对齐即可），不必每个 `@RequestParam` 显式命名；但要补 MockMvc
   契约测试，否则同类问题还会回来。
2. **A-06 修复方向确认**：诱导子图（仅含已标注节点）重算 Q 是正确做法；`modularity()` 作为公共 API
   应改为「只累加双侧有 label 的边、以这批边权重和为 m2」，并在 `PartitionMetrics.of == partition.json Q`
   上加一致性测试——这条测试本轮之前从未存在，是两个 Q 并存到现在的根本原因。
3. **A-01/A-02 的测试缺口比修复本身更要紧**：建议第一批修复时把 codegen IT fixture 扩成「含原启动类 +
   @MapperScan 配置类」的形态（哪怕不真连数据库），否则下一批真实单体缺陷还会漏。
4. 对报告正文无实质性不同意见。

## C.5 建议执行顺序（与报告 §8 基本一致，微调）

第一批（0.3.0 发布门禁）：A-01+A-02（生成工程真实可用）→ A-04（安全）→ A-03（一行配置级修复）→
A-05（tag 唯一表）→ A-06（Q 口径+一致性测试）→ A-07/A-20（输出目录守卫）→ A-08/A-09（桥失败策略闭环）。
A-08 的修法倾向「跳过即失败并把调用点列进人工项」，与 R-08 的失败策略哲学一致，比保底签名诚实。
