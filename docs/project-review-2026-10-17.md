# monoGomicro 项目审阅报告（2026-10-17）

> 用途：提交智谱 / Kimi 做独立交叉评审。本文尽量自包含，不依赖审阅过程中的对话上下文。
>
> 审阅对象：`monoGomicro`，`main` / `develop` 均为 commit `242bfda`（Bump version to 0.3.0）。
>
> 审阅结论：项目功能完整、构建与测试全绿，但发布前存在 **1 个 P0、4 个 P1**，其中生成器的删除行为有真实可复现的数据丢失风险。以下所有发现都给出代码位置、证据和修复建议；标注 `[实测]` 的条目已在隔离环境复现，标注 `[静态]` 的条目来自代码推理，标注 `[仓库记录]` 的条目来自仓库既有文档。

---

## 0. 给评审者的说明

### 0.1 希望你们做什么

请优先做四件事：

1. **挑战严重度判断**：P0/P1 是否成立，是否有我夸大的地方。
2. **找反例**：尤其 R-02（agent 入口）、R-03（SSE）、R-05（POM 坐标）、R-08（RPC 桥签名）、R-11（退火空社区）。
3. **判断修复策略**：有些问题不是“改一行”那么简单，见第 6 节开放问题。
4. **指出遗漏**：本文没有做完整安全审计、性能压测、RuoYi 全链路重跑，也没有逐行审查所有测试代码。

### 0.2 证据标记

- `[实测]`：本次审阅中通过命令或最小 Java 程序复现。
- `[静态]`：阅读代码后的推断，未单独运行验证。
- `[仓库记录]`：来自 `AGENTS.md` / `docs/` 的既有结论，本次未重跑。

### 0.3 复现环境

- macOS 27.0.1 arm64
- Java 17.0.6（Zulu）
- Maven 3.9.16
- 在仓库根目录执行；所有临时实验均在 `/tmp` 隔离目录进行，未改动仓库源码。

---

## 1. 项目与验证基线

### 1.1 项目是什么

Java 单体 → 微服务拆分建议工具（IBM Mono2Micro 复刻），六模块 Maven / Java 17：

| 模块 | 职责 | main 行数 | 测试 |
|---|---|---:|---|
| `monogomicro-core` | JavaParser 静态分析、加权依赖图、Louvain + 退火分区、共享内核、审计引擎 | 4092 | 44 单测 |
| `monogomicro-agent` | ByteBuddy java agent，运行时轨迹采集 | 181 | 0 |
| `monogomicro-codegen` | 分区 → 微服务工程生成（源码拷贝、RPC 桥、POM、MIGRATION.md） | 1505 | 3 单测 + 1 IT |
| `monogomicro-cli` | `m2m analyze / partition / trace / generate / audit` | 273 | 0 |
| `monogomicro-web` | Spring Boot + D3 本地控制台 | 251 | 0 |
| `monogomicro-mcp` | MCP server（stdio + Streamable HTTP），17 个工具 | 1682 | 24 单测 + 3 IT |

### 1.2 本次怎么审

- 全量执行 `mvn -B verify`（含 unit、failsafe IT、shade 打包）。
- 阅读 core / codegen / MCP / web / agent 的主要代码路径，重点看输入边界、文件路径、生成产物、协议输出。
- 用最小 Java 程序或裸 socket 对关键结论做隔离复现。
- 没有重跑 RuoYi / RuoYi-Cloud 真实样本；仓库记录为已通过，本次沿用该结论。

### 1.3 构建与运行基线

`mvn -B verify`：

```text
monoGomicro ........................................ SUCCESS
monogomicro-core ................................... SUCCESS
monogomicro-agent .................................. SUCCESS
monogomicro-codegen ................................ SUCCESS
monogomicro-cli .................................... SUCCESS
monogomicro-web .................................... SUCCESS
monogomicro-mcp .................................... SUCCESS
BUILD SUCCESS
```

测试统计：

- 单测：core 44 + codegen 3 + mcp 24 = **71**
- IT：codegen 生成服务运行级 1 + MCP stdio 1 + MCP HTTP 2 = **4**
- 合计 **71 单测 + 4 IT 全绿**

CLI：

```text
$ java -jar monogomicro-cli/target/m2m.jar --version
m2m 0.3.0
```

工作树：

```text
$ git status --short
（空）
```

### 1.4 已知未覆盖

- 未重跑 RuoYi / RuoYi-Cloud 的 analyze/audit/generate 全链路，只沿用仓库记录。
- 未做 JVM 性能基准、内存压测、并发 MCP 压力测试。
- 未做完整威胁建模；R-04 是明显的暴露面问题，但不是完整安全审计。
- 未逐行审查所有测试实现；测试是否“测到点子上”只做了抽样。

---

## 2. 发现清单（按严重度）

| ID | 严重度 | 主题 | 状态 |
|---|---|---|---|
| R-01 | P0 | 生成器先删后校验；空 `outDir` 会清空当前目录 | [实测] |
| R-02 | P1 | 运行时 agent 在文档用法下不产生用例入口 | [实测] |
| R-03 | P1 | MCP HTTP 的 SSE 响应不是合法 SSE | [实测] |
| R-04 | P1 | Web 控制台默认监听所有网卡，且接受任意文件路径 | [实测] |
| R-05 | P1 | `PomReader` 对 parent-first POM 取错 groupId/version | [实测] |
| R-06 | P2 | 空/全 shared 审计产生字符串 `"NaN"` 指标 | [实测] |
| R-07 | P2 | 服务目录 tag 冲突（`user` 与 `user-service`） | [实测] |
| R-08 | P2 | RPC 桥只覆盖理想签名：重载/部分 JDK 类型会编译失败 | [静态] |
| R-09 | P2 | Mapper XML 单模块路径错误；生成器残留 RuoYi 专用逻辑 | [静态] |
| R-10 | P2 | shared 启发式把 `rapid-service` 误判为 shared | [实测] |
| R-11 | P2 | 退火目标函数把空社区当真实社区 | [实测] |
| R-12 | P2 | CLI/MCP 的分区与审计参数缺少范围校验 | [静态] |
| R-13 | P2 | Web 的 `ConsoleState.move` 先改状态后校验 | [静态] |
| R-14 | P3 | README 版本/工具数/agent jar 路径与代码漂移 | [实测] |
| R-15 | P3 | `.zcode/plans/...` 被提交进仓库 | [实测] |
| R-16 | P3 | 测试覆盖不均、无 CI；上述问题基本无回归测试 | [静态] |
| R-17 | P3 | 一组低优先级健壮性/可移植性观察 | [静态] |

---

## 3. 详细发现

### R-01（P0，实测）生成器先删目录、后做校验；空 outDir 会清空当前目录

**位置**

- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/MicroserviceGenerator.java:245-251`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/MicroserviceGenerator.java:200-202`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/GenerateOptions.java:23-26`
- `monogomicro-cli/src/main/java/com/monogomicro/cli/Main.java:261-268`
- `monogomicro-web/src/main/java/com/monogomicro/web/WorkspaceController.java:136-145`
- `monogomicro-mcp/src/main/java/com/monogomicro/mcp/WorkspaceSession.java:343-349`

**代码事实**

```java
// MicroserviceGenerator.java:245-251
private GenerationResult doGenerate(Path outDir) throws Exception {
    deleteRecursively(outDir);
    Files.createDirectories(outDir);
    pomReader = new PomReader();
    pomReader.readProject(projectDir);
    ...
}
```

`generate(Path)` 走 `GenerateOptions.legacy()`，而 `legacy()` 是 `overwrite=true`，没有任何守卫。Web 的 `/api/generate` 直接调用这个入口：

```java
// WorkspaceController.java:141-143
GenerationResult result = new MicroserviceGenerator(
        state.report(), state.partition(), Path.of(req.projectDir()))
        .generate(Path.of(req.outDir()));
```

**复现（实测）**

在隔离目录让当前工作目录包含 `keep.txt`，调用 `generate(Path.of(""))`：

```java
System.out.println(Path.of("").toAbsolutePath()); // 当前工作目录
new MicroserviceGenerator(new AnalysisReport(), new PartitionResult(), projectNoPom)
    .generate(Path.of(""));
```

结果：

```text
原项目没有 pom.xml，无法生成 Maven 工程
keep.txt exists after failed generate = false
```

也就是说：**目标目录先被递归删除，然后才因为原项目缺少 POM 失败**。Web 页面 `index.html` 的“生成”输入框没有必填校验（`monogomicro-web/src/main/resources/static/index.html:63-66`）；在已通过 `/api/load` 载入数据的会话中，如果用户在仓库根目录启动 web、把 `outDir` 留空后点“生成”，`outDir` 为空 → `Path.of("")` → 当前目录被清空。CLI 的 `-o` 指向项目根时同理。

MCP 路径有 workspace 包含检查，但 `outDir == workspaceRoot` 也能通过：

```java
// WorkspaceSession.java:344-349
Path normalized = effectiveOut.toAbsolutePath().normalize();
if (!normalized.startsWith(workspaceRoot)) {
    throw new M2mException("E_OUT_OF_WORKSPACE", ...);
}
```

若用户显式把 `outDir` 设为 `<project>/m2m-out` 并 `overwrite=true`，会删掉 `analysis.json` / `partition.json`。

**影响**

数据丢失，且触发路径非常短（Web 一次点击 / CLI 一个参数）。这是本次审阅唯一建议直接标 P0 的问题：它不是“生成失败”，而是“生成失败前已经删了文件”。

**建议**

1. 删除前校验：`outDir` 非空、不是 cwd、不是 `projectDir`、不是 `workspaceRoot`，且不是它们的祖先。
2. Web/CLI 默认不启用无条件覆盖；`overwrite` 必须显式传入，CLI 可加 `--overwrite` 并要求非交互环境显式确认。
3. 正式生成先写临时目录，成功后原子移动；失败时保留原目录。
4. 增加回归测试：空路径、cwd、项目根、workspace 根、父目录。

---

### R-02（P1，实测）运行时 agent 在文档用法下不产生用例入口

**位置**

- `monogomicro-agent/src/main/java/com/monogomicro/agent/TraceAgent.java:63-85`
- `monogomicro-core/src/main/java/com/monogomicro/core/trace/TraceAnalyzer.java:58-64`
- `README.md:77` / `README.zh-CN.md:73`

**代码事实**

类型匹配器只允许 `include` 前缀：

```java
var typeMatcher = ElementMatchers.<TypeDescription>none();
for (String prefix : includes) {
    typeMatcher = typeMatcher.or(ElementMatchers.nameStartsWith(prefix));
}
...
.type(typeMatcher)
.transform(... .visit(Advice.to(EntryAdvice.class).on(
        ElementMatchers.named(finalEntryMethod)
            .and(ElementMatchers.isDeclaredBy(
                ElementMatchers.nameContains(finalEntryClass)))))
```

`EntryAdvice` 绑定到 `DispatcherServlet.doDispatch`，但 Spring 的 `DispatcherServlet` 在 `org.springframework...`，不在 `include=com.your.pkg.` 里，所以不会被织入（除非用户额外把 Spring MVC 包加入 `include`，但文档没有这样要求，而且会引入插桩框架的风险）。没有 `E` 行时，`TraceAnalyzer` 不会建立 `current`，所有 `N` 行都被忽略：

```java
case 'N' -> {
    if (current != null) { ... }
    stack.push(raw.fqcn());
}
```

**复现（实测）**

```java
package com.demo;
public class Entry {
    public static void main(String[] args) { new Entry().controller(); }
    void controller() { service(); }
    void service() { System.out.println("business call"); }
}
```

```bash
java -javaagent:monogomicro-agent/target/monogomicro-agent-0.3.0.jar=\
out=/tmp/trace.log,include=com.demo.,entry=DispatcherServlet \
-cp classes com.demo.Entry

grep -c $'\tE\t' /tmp/trace.log   # 0
java -jar monogomicro-cli/target/m2m.jar trace /tmp/trace.log
# 共 0 个业务用例
```

轨迹里只有 `N/X`，没有 `E`。这与 README 的用法完全一致，所以“运行时分析”在主路径上实际不可用。

**影响**

`m2m partition -t trace.log` 的动态图永远为空，双图融合退化为纯静态分区。这是核心卖点之一（Mono2Micro 的 runtime analysis）在主路径上的失效。

**建议**

1. 用独立的 `AgentBuilder` 织入入口类，不受 `include` 限制；或
2. 改为在 `include` 包内的 Controller 方法/HTTP 入口上打 `E` 标记；或
3. 显式要求 `include` 同时包含 Spring MVC 包，并在启动日志/文档里说明代价（会插桩框架内部，风险高，不推荐）。
4. 增加一个真正启动 agent 的集成测试，锁住“至少产生 1 个用例”。

---

### R-03（P1，实测）MCP HTTP 的 SSE 响应不是合法 SSE

**位置**

- `monogomicro-mcp/src/main/java/com/monogomicro/mcp/McpServerMain.java:94-102`
- `monogomicro-core/src/main/java/com/monogomicro/core/report/JsonIO.java:16-18`
- `monogomicro-mcp/src/test/java/com/monogomicro/mcp/McpHttpIT.java:221-246`
- `docs/mcp-design.md:122-124`

**代码事实**

HTTP transport 使用 `JsonIO.mapper()`，该 mapper 开启了 `INDENT_OUTPUT`：

```java
// JsonIO.java:16-18
private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```

```java
// McpServerMain.java:94-102
HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(JsonIO.mapper()))
        ...
```

**实测输出**

```text
event: message
data: {
  "jsonrpc" : "2.0",
  "id" : 2,
  "result" : {
    "tools" : [ {
      ...
```

只有第一行带 `data:`，后续 JSON 行是裸文本。按 SSE 规范，只有 `data:` 行会进入事件载荷；标准客户端最终只拿到 `{`。项目自己的 `McpHttpIT` 已经写了“从 `data:` 收集到空行”的解析器来绕过：

```java
// McpHttpIT.java:221-224（注释原文）
// SDK 的 SSE 事件只在 JSON 首行前写一个 data: 前缀，JSON 本体（INDENT_OUTPUT 多行）
// 原样跟随直到空行（事件边界）——因此解析从 data: 行起收集到空行为止，
```

**影响**

HTTP 传输对标准 MCP 客户端（按 SSE 解析）可能完全不可用；现有契约测试只证明了“项目自己的宽容解析器能读”。stdio 不受影响（`McpJsonDefaults` 的 mapper 不缩进）。

**建议**

给 HTTP transport 使用一个**不带 `INDENT_OUTPUT`** 的紧凑 mapper（最好 stdio/HTTP 共用同一个专用 mapper，避免误用 `JsonIO` 的展示用 mapper）。然后把 `McpHttpIT` 改成严格 SSE 解析，或至少同时跑一个官方客户端/Inspector。

---

### R-04（P1，实测）Web 控制台默认监听所有网卡，且接受任意文件路径

**位置**

- `monogomicro-web/src/main/resources/`：没有 `application.properties` / `application.yml`，只有 `static/index.html`
- `monogomicro-web/src/main/java/com/monogomicro/web/ConsoleApplication.java`
- `monogomicro-web/src/main/java/com/monogomicro/web/WorkspaceController.java:40-54,128-145`
- `monogomicro-web/src/main/resources/static/index.html:107-112,176-179`

**实测**

```bash
java -jar monogomicro-web/target/monogomicro-web.jar --server.port=18090
# lsof 输出：
java ... TCP *:18090 (LISTEN)
```

Spring Boot 未设置 `server.address` 时默认绑定所有接口；README 却写的是 `http://localhost:8080`（`README.md:86`）。接口本身没有认证，且：

- `/api/load`：任意路径读 JSON
- `/api/save`：任意路径写 partition
- `/api/generate`：任意 `outDir` + 无条件覆盖删除（与 R-01 叠加）

另外 `index.html` 用 `innerHTML` 拼接 `c.name` / `d.name` / `d.module`：

```js
communities.innerHTML = G.communities.map(c =>
  `<div class="community">...${c.name}（${c.size}）</div>`).join('')
```

如果加载了不可信的 `partition.json`（服务名可含 HTML），可形成 XSS。

**影响**

局域网内任何人可调用任意文件读/写/目录删除接口；即使不考虑攻击者，默认“localhost 文档 + 全接口监听”也自相矛盾。

**建议**

1. 默认 `server.address=127.0.0.1`（本地控制台定位）。
2. `/api/save`、`/api/generate` 做 workspace 包含校验；`/api/load` 至少限制在明确配置的根目录内。
3. 如果确实要内网共享，不要复用无认证的 web 控制台；给 MCP HTTP 加 token/认证，或单独做受控部署。
4. 前端改用 `textContent` / `createElement`，不要拼接不可信名称。

---

### R-05（P1，实测）`PomReader` 对 parent-first POM 取错坐标

**位置**

- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/PomReader.java:38-54`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/PomReader.java:136-139`

**代码事实**

```java
List<Path> poms = ...;
Document root = parse(poms.get(0));       // 不是固定 projectDir/pom.xml
projectGroupId = textOf(root, "groupId", "generated");
projectVersion = textOf(root, "version", "1.0.0");
```

`textOf` 用 `doc.getElementsByTagName(tag)`，返回文档顺序里的**第一个**同名标签：

```java
private String textOf(Document doc, String tag, String fallback) {
    NodeList list = doc.getElementsByTagName(tag);
    return list.getLength() == 0 ? fallback : list.item(0).getTextContent().trim();
}
```

如果 POM 把 `<parent>` 放在最前面（Spring Boot 项目常见），第一个 `groupId`/`version` 来自 parent，而不是项目自身。

**实测**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
  </parent>
  <groupId>com.real</groupId>
  <artifactId>app</artifactId>
  <version>1.2.3</version>
</project>
```

`PomReader.readProject` 输出：

```text
groupId=org.springframework.boot version=3.3.5
```

期望是 `com.real / 1.2.3`。这会导致生成的聚合 POM 坐标错误，并且 `externalDependencies()` 按错误的 `projectGroupId` 过滤依赖。

另外 `poms.get(0)` 也不是根 POM 的稳定选择：`Files.walk` 结果按路径排序，`<project>/app/pom.xml` 会排在 `<project>/pom.xml` 前面（`a` < `p`）。

**影响**

对标准 Spring Boot parent-first 单体，生成的工程坐标和依赖并集可能错误；RuoYi 根 POM 没有 `<parent>`，所以现有实测没有暴露。

**建议**

1. 根 POM 固定读 `projectDir.resolve("pom.xml")`。
2. 只取 `<project>` 的直接子元素 `groupId`/`version`；缺失时再回退 parent。
3. `properties` 的合并顺序也要明确（当前按排序后的 POM 遍历，根/子模块覆盖关系不稳定）。
4. 补一个 parent-first + 多模块的 `PomReader` 单测。

---

### R-06（P2，实测）空/全 shared 审计产生字符串 `"NaN"` 指标

**位置**

- `monogomicro-core/src/main/java/com/monogomicro/core/partition/PartitionMetrics.java:97-107`
- `monogomicro-core/src/main/java/com/monogomicro/core/audit/ArchitectureAuditor.java:135`
- `monogomicro-core/src/test/java/com/monogomicro/core/audit/ArchitectureAuditorTest.java:207-221`

**代码事实**

```java
public static double imbalance(Map<Integer, List<String>> byCommunity) {
    int k = byCommunity.size();
    int totalClasses = byCommunity.values().stream().mapToInt(List::size).sum();
    double mean = totalClasses / (double) k;       // k=0 -> NaN
    ...
    return mean == 0 ? 0 : Math.sqrt(variance) / mean;
}
```

`k=0` 时 `mean=NaN`，后续 `mean == 0` 为 false，最终返回 `0 / NaN = NaN`。

**实测**

空图或只有 shared 模块时：

```text
empty imbalanceCv=NaN
allShared business=0 imbalanceCv=NaN
"imbalanceCv" : "NaN"
```

Jackson 把非有限 double 写成了字符串 `"NaN"`，字段类型从 number 变成 string，破坏 JSON 契约。`ArchitectureAuditorTest.degenerateInputsDoNotCrash` 覆盖了空图/全 shared，但只断言 `classCount` / `businessServiceCount`，没有断言指标，所以测试是绿的。

**建议**

1. `k == 0` 返回 0；或把该指标改为 `null` 并明确契约（见开放问题 Q7）。
2. 在 degenerate 测试中断言 `imbalanceCv` 是有限数值（或明确为 null）。
3. 检查 `PartitionEngine.buildResult` 对空分区的同类输出。

---

### R-07（P2，实测）服务目录 tag 冲突

**位置**

- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/MicroserviceGenerator.java:260-267,327-334`
- `monogomicro-mcp/src/main/java/com/monogomicro/mcp/WorkspaceSession.java:267-287`

**代码事实**

```java
private String serviceTag(String communityName, int id) {
    String tag = communityName == null ? "" : communityName;
    tag = tag.replaceAll("(?i)-?service$", "").replaceAll("[^a-zA-Z0-9-]", "");
    if (tag.isEmpty()) tag = "svc" + id;
    return tag.toLowerCase();
}
```

`user` 和 `user-service` 都变成 `user`，两者生成同一个 `svc-user` 目录；`serviceDirs` 会加入两个相同目录，POM 的 `<modules>` 出现重复项，`ownerOf` 也会把两个社区的类写到同一目录。

**实测**

```text
tag(user)=user tag(user-service)=user
```

`rename_service` 也没有唯一性校验（`WorkspaceSession.java:279-281` 直接 setName）。

**建议**

1. tag 冲突时追加社区 id（例如 `user-3`），或保留一个稳定的 name→dir 映射并校验唯一性。
2. `rename_service` 拒绝重复名，或在生成阶段显式报错。
3. 补一个“两个社区 sanitize 后同名”的 codegen 单测。

---

### R-08（P2，静态）RPC 桥只覆盖理想签名：重载/部分 JDK 类型会编译失败

**位置**

- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/RpcBridgeGenerator.java:237-264`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/RpcBridgeGenerator.java:274-293`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/RpcBridgeGenerator.java:313-315`

**问题 1：重载方法**

`writeController` 为每个 `MethodSig` 生成：

```java
public String <name>(@RequestBody(required = false) Map<String, Object> body)
```

同一接口里如果存在 `String find(Long id)` 和 `String find(String code)`，生成两个同名、同参数类型的 Java 方法 → **编译失败**；同时 `@PostMapping("/find")` 也重复。当前 `GeneratedServicesRpcIT` 的 fixture 没有重载，所以没有暴露。

**问题 2：JDK 类型 import 白名单**

`collectImports` 只处理项目类型和 `List/Map/Set/Date/BigDecimal`：

```java
if (fqcn != null) imports.add(fqcn);
else if (name.equals("List")) imports.add("java.util.List");
else if (name.equals("Map")) imports.add("java.util.Map");
else if (name.equals("Set")) imports.add("java.util.Set");
else if (name.equals("Date")) imports.add("java.util.Date");
else if (name.equals("BigDecimal")) imports.add("java.math.BigDecimal");
```

如果接口签名使用 `LocalDate`、`LocalDateTime`、`Optional`、`UUID` 等，生成源码里没有对应 import，编译失败。`typesReachable` 会把未知简单名当作 JDK/外部类型放行，所以不会提前跳过。

**问题 3：泛型/数组运行时**

客户端用 `(List<Foo>) result` 强转，参数用 `convert(body.get("p0"), List.class)`。JSON 反序列化后元素是 `LinkedHashMap`，服务方法按 `Foo` 遍历时会 `ClassCastException`；数组返回 `(Foo[]) result` 也会失败。README 已经提示“返回值需要显式 DTO”，但参数、泛型元素和数组同样受影响。

**建议**

1. 重载：生成唯一方法名/URL（如 `find_1`）或直接跳过并写入人工清单。
2. 从 provider 源文件复制 import，或统一使用全限定类型；遇到无法处理的类型显式 `manualItems`，不要生成编译不过的代码。
3. 对泛型/数组返回值生成 `ObjectMapper.convertValue(result, new TypeReference<...>(){})`，而不是裸强转；或明确不支持并跳过。
4. 增加重载、`LocalDate`、`List<DTO>`、数组四类 IT/fixture。

---

### R-09（P2，静态）Mapper XML 单模块路径错误；生成器残留 RuoYi 专用逻辑

**位置**

- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/MicroserviceGenerator.java:386-410`
- `monogomicro-codegen/src/main/java/com/monogomicro/codegen/MicroserviceGenerator.java:146-174`

**问题 1：单模块 mapper 路径**

```java
String rel = projectDir.relativize(xml).toString()
        .replaceFirst("^[^/]+/src/main/resources/", "");
Path target = outDir.resolve(owner).resolve("src/main/resources").resolve(rel);
```

正则要求 `src/main/resources` 前面至少有一个路径段。多模块 `ruoyi-system/src/main/resources/mapper/X.xml` 能剥成 `mapper/X.xml`；单模块 `src/main/resources/mapper/X.xml` 不匹配，最终生成：

```text
svc-*/src/main/resources/src/main/resources/mapper/X.xml
```

**问题 2：硬编码 RuoYi**

`consolidateClientsToCommon` 过滤 `"/src/main/java/com/ruoyi/client/"`，目标也写死 `common/src/main/java/com/ruoyi/client`（`MicroserviceGenerator.java:151,165`）。非 RuoYi 基包下客户端不会去重；行为静默不同。`copyMapperXml` 还硬编码 `"/mapper/"`（`:391`）。

**建议**

1. 用 `xml` 相对其最近的 `src/main/resources` 祖先计算 rel，不要靠正则。
2. 客户端收敛改为基于生成的 client 包（`basePackage + ".client"`）或显式传入的包，而不是 `com.ruoyi`。
3. 如果保留 RuoYi 专用行为，至少写成配置/策略，并在文档里标明。

---

### R-10（P2，实测）shared 启发式把 `rapid-service` 误判为 shared

**位置**

- `monogomicro-core/src/main/java/com/monogomicro/core/audit/LandscapeFactory.java:73-76`
- `monogomicro-core/src/main/java/com/monogomicro/core/audit/LandscapeFactory.java:29`

**代码事实**

```java
private static final Set<String> DEFAULT_SHARED_HINTS =
        Set.of("common", "shared", "api", "client", "dto");

private static boolean looksShared(String moduleName, Set<String> hints) {
    String lower = moduleName.toLowerCase();
    return hints.stream().anyMatch(lower::contains);
}
```

`rapid-service` 的 `r[a p i]d` 包含 `api`。

**实测**

```text
business=0 shared=1
```

一个业务模块被排除出审计主口径，boundaryCoupling/modularity/反模式都会受影响。

**建议**

按词元边界匹配（模块名等于 hint、`hint-` 前缀、`-hint` 后缀、`hint.` 包段），或把 hints 做成 `services.json` 可覆盖的显式配置。RuoYi 的 `ruoyi-api-*` 仍应命中 `api`。

---

### R-11（P2，实测）退火目标函数把空社区当真实社区

**位置**

- `monogomicro-core/src/main/java/com/monogomicro/core/partition/PartitionEngine.java:150-180`
- `monogomicro-core/src/main/java/com/monogomicro/core/partition/AnnealingRefiner.java:39,112-165`

**代码事实**

`mergeSmallCommunities` 把小社区整体并入邻居后，没有重编号，直接 break 重新分组。被并入的社区 id 消失，留下空洞：

```java
for (String member : e.getValue()) {
    labels.put(member, best);
}
changed = true;
break;
```

随后 `AnnealingRefiner` 用 `max(label)+1` 作为 `k`：

```java
this.k = labels.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
```

`communitySizes` 数组包含空槽，`objective` 的均值/方差和 `useCaseSpanNorm` 的 `k-1` 分母都把空社区算进去；`randomNeighborCommunity` 还可能返回空社区，把节点移进去，重新制造碎片。

**实测**

同一张图、同样两个真实社区：

```text
gap labels objective=0.6414213562373096      // labels {a:0, b:2}，中间空 id 1
contiguous objective=0.5                     // labels {a:0, b:1}
```

**影响**

退火是分区质量的关键步骤；这个偏差会系统性地抬高 imbalance/span，影响社区选择。最终报告指标用实际分组，所以不会直接写错报告，但分区结果可能变差。

**建议**

1. 合并小社区后先 renumber，或
2. 在 `AnnealingRefiner` 内构造“实际社区集合 → 连续索引”的映射，`k`/sizes/随机选择都基于实际集合。
3. 注意：这会改变 `docs/audit-baseline/ruoyi-partition-before.json` 的冻结结果，需要先决定基线更新策略（见 Q8）。

---

### R-12（P2，静态）CLI/MCP 的分区与审计参数缺少范围校验

**位置**

- `monogomicro-cli/src/main/java/com/monogomicro/cli/Main.java:115-130`（alpha/beta/minSize/maxServices）
- `monogomicro-mcp/src/main/java/com/monogomicro/mcp/McpServerMain.java:225-237`（inputSchema 没有 minimum）
- `monogomicro-mcp/src/main/java/com/monogomicro/mcp/WorkspaceSession.java:203-221`
- `monogomicro-core/src/main/java/com/monogomicro/core/audit/AuditConfig.java`

`alpha`/`beta` 可传负数，`minSize`/`maxServices` 可传 0 或负数，审计 `topN` 可传负数，阈值可传异常值。运行时不会抛出清晰错误，而是产生无意义的分区/报告。建议统一 `E_BAD_PARAMS`：alpha/beta ∈ [0,1]，minSize/maxServices ≥ 1，topN ≥ 0，阈值 ≥ 0。

---

### R-13（P2，静态）Web 的 `ConsoleState.move` 先改状态后校验

**位置**

- `monogomicro-web/src/main/java/com/monogomicro/web/ConsoleState.java:53-72`

```java
public void move(String fqcn, int targetCommunity) {
    if (!labels.containsKey(fqcn)) throw ...;
    labels.put(fqcn, targetCommunity);                 // 先改
    partition.getCommunities().forEach(c -> c.getClasses().remove(fqcn));
    ...
    throw new IllegalArgumentException("未知社区: " + targetCommunity); // 后验
}
```

目标社区不存在时，类已经从所有社区删除、labels 指向不存在的 id，controller 才返回 400；后续 `/api/state` 和指标会处于不一致状态。应先验证目标 id，再改状态。

---

### R-14（P3，实测）README 与代码漂移

**位置**

- `README.md:119` / `README.zh-CN.md:115`：仍写“15 tools”，实际 17 个（audit 两个工具）。
- `README.md:76` / `README.zh-CN.md:72`：Quick Start 用 `monogomicro-agent-0.2.0.jar`，但源码构建当前产出 `0.3.0`；fresh clone 按文档执行会找不到 jar。
- `README.md:207-210` / `README.zh-CN.md:196-198`：测试章节只列 Louvain/轨迹，没有 71 单测 + 4 IT。
- `README.md:50,58` / `README.zh-CN.md:46,54`：安装示例仍是 `0.2.0`；audit 是 0.3.0 能力。0.3.0 缓发期间，用户按 README 安装 0.2.0 后执行 `m2m audit` 会失败。
- `monogomicro-mcp/src/test/java/com/monogomicro/mcp/McpHttpIT.java:33,97`：注释仍写“15 工具”，断言已是 17。

**建议**：发布前把 README 的工具数、版本号、agent jar 路径、测试说明统一；如果 0.3.0 继续缓发，在 audit 段落明确写“需 0.3.0（未发布）/从源码构建”。

---

### R-15（P3，实测）`.zcode/plans/...` 被提交进仓库

```text
$ git ls-files | grep .zcode
.zcode/plans/plan-sess_8c92ba51-1b28-41ab-b80a-10038bc96a53.md
```

内容是内部执行计划（“批准后我只做两件事…”），不是项目交付物。建议删除，并把 `.zcode/` 加入 `.gitignore`（当前 `.gitignore` 没有这一项）。

---

### R-16（P3，静态）测试覆盖不均、无 CI

- codegen 1.5k 行只有 3 个单测 + 1 个 happy-path IT；web/cli/agent 为 0 测试。
- R-01/R-02/R-03/R-05/R-07/R-08/R-10/R-11 都缺少回归测试；其中 R-01 是数据安全，R-02/R-03 是核心功能/协议。
- 没有 GitHub Actions；建议至少加 `mvn -B verify`（codegen IT 在无 mvn 时会 skip，可作为第一步）。

---

### R-17（P3，静态）低优先级健壮性/可移植性观察

这些没有单独做成 P2，但建议记入 backlog：

1. **Windows 路径假设**：`ProjectScanner` 用 `endsWith("src/main/java")`、`contains("/target/")`、`contains("/m2m-out/")`；`MavenClasspathResolver` 用 `split(":")` 解析 classpath；codegen 用 `/mapper/`、`/src/main/java/com/ruoyi/client/`。如果目标平台包含 Windows，这些都会失效；如果不支持，README 应明确写“仅 macOS/Linux”。
2. **`TraceAnalyzer` 全量读入**：`Files.readAllLines` 把整个 trace 读进内存，再用 per-thread list 保存；长跑应用的 trace 可能很大，建议流式解析。
3. **Maven 输出管道**：`MavenClasspathResolver` 启动 `mvn` 后没有消费 stdout/stderr，`-q` 下通常很小，但异常输出很多时可能阻塞到 5 分钟超时；`mvnAvailable()` 也没有超时。
4. **MCP 异常提示不实**：`McpServerMain.invoke` 的兜底 `catch (Exception)` 返回 hint“详细堆栈见 server stderr 日志”，但没有实际 `log.error`；排查时看不到堆栈。
5. **`AuditReporter` 提前返回**：没有 findings 时直接 return，不打印 per-service fanIn/fanOut 统计（`AuditReporter.java:29-33`）；是否需要保留统计由产品决定。
6. **扫描器只给首个顶层类型建边**：`ProjectScanner` 的 `units.putIfAbsent(fqcn, ...)` 只把文件第一个顶层类型作为依赖归属，其他顶层类型有节点但没有出边（`:172-174`）；对一个文件多个顶层类型的项目会低估依赖。
7. **MCP 结果大小未硬限制**：`audit_architecture` 的 findings、`suggest_refactoring` 的 moves/structures 没有分页/截断，和设计文档“≤4KB”的 token 预算可能冲突（`McpTools.java:418-491`）。
8. **Web 保存提示**：`index.html` 的 `save()` 没有检查 HTTP 状态，失败也弹“已保存”；`/api/save` 也没有 `isLoaded` 检查。
9. **`McpServerMain.Options.parse`**：`--host`/`--port` 缺值时直接数组越界，错误信息不友好。
10. **`PomReader` / scanner 的自污染边界**：scanner 排除了 `/m2m-out/`，但用户自定义输出目录若在项目内，下次 analyze 可能把生成工程扫进来；`PomReader` 也没有排除生成目录。建议统一“生成输出目录”排除规则。
11. **扫描器简单名冲突**：`ProjectScanner.registerSimpleNames` 对 import 做了 `ambiguousSimpleNames` 记录，但对项目自身类型只用 `putIfAbsent`（`:221-235`），不标记歧义；不同包下同名类可能让裸名引用静默解析到错误的类，进而影响依赖边和生成导入。建议统一歧义处理策略。
12. **多基包工程的组件扫描**：`MicroserviceGenerator.commonPackagePrefix` 取所有类的公共包前缀；如果项目同时包含 `com.foo` 与 `org.bar`，前缀退化为空并回退到 `com.generated`（`:337-347`）。生成的 `@SpringBootApplication` 只扫描 `com.generated`，业务 Bean 不会被发现；工程能编译但运行时失败。建议按服务实际包生成 `scanBasePackages` 或在生成时告警。
13. **application.yml 跳过自身的逻辑**：`writeApplicationYml` 用 `idx != port - 8081` 判断“是不是自己”（`:659-665`），依赖社区列表顺序与端口分配顺序完全一致；一旦列表顺序变化就会给自身写 base-url 或漏写对端。建议用当前 `dir`/tag 直接比较。

---

## 4. 亮点与保持项

- 六模块边界清晰；core 无框架依赖，便于嵌入。
- `mvn verify` 真的把生成工程 install、起两个服务、走 HTTP 断言 RPC 桥；这是比“只编译”强得多的验收。
- 审计功能有明确设计文档、冻结基线、RuoYi-Cloud 真实样本记录；指标口径（boundaryCoupling 剔除 shared）在代码注释里写得很清楚。
- MCP 的 workspace 路径校验、结构化错误、进度通知、stdio/HTTP 双传输契约测试都做得不错；R-03 是编码层问题，不是架构问题。
- CLI 版本已改为读 manifest，`--version` 输出正确。

---

## 5. 建议修复顺序与验证计划

### 第一批（发布前必须）

1. R-01：生成器删除/路径守卫 + web/CLI/MCP 统一策略；补空路径/项目根/workspace 根测试。
2. R-04：`server.address=127.0.0.1`、路径校验、前端转义。
3. R-02：agent 入口插桩；补真正启动 agent 的 IT。
4. R-03：MCP HTTP 紧凑 mapper；严格 SSE 测试。

### 第二批（输出正确性）

5. R-05：PomReader 根 POM/直接子元素；补 parent-first 测试。
6. R-06：NaN 契约；补 degenerate 指标断言。
7. R-07：唯一 tag + rename 校验。
8. R-08：重载/JDK 类型/泛型数组的失败策略（生成可编译代码或明确人工项）。

### 第三批（通用性与质量）

9. R-09：mapper XML 相对路径 + 去 RuoYi 硬编码。
10. R-10：shared 词元匹配。
11. R-11：退火空社区（先决定基线策略）。
12. R-12/R-13：输入校验、web 状态一致性。
13. R-14/R-15/R-16：文档、仓库卫生、回归测试/CI。

### 验证门槛

- `mvn -B verify` 全绿。
- 新增回归：空 outDir、项目根 outDir、parent-first POM、tag 冲突、重载接口、`LocalDate` 签名、agent 入口、严格 SSE、空业务服务 audit。
- 如果修改影响分区结果，必须显式更新 `docs/audit-baseline/` 并在文档中记录“行为变化 + 原因”，不能静默漂移。

---

## 6. 开放问题（请智谱 / Kimi 明确表态）

### Q1 生成器的破坏性边界

Web 一键可触发、CLI 可指向项目根、MCP 可指向 workspace 根。应该采用哪种策略？

- A. 所有入口强制 `outDir` 是 workspace 下的新子目录，禁止 cwd/项目根/workspace 根；
- B. CLI 允许任意目录，但必须显式 `--overwrite` 且交互确认；Web/MCP 严格限制；
- C. 生成到临时目录再原子替换，允许覆盖但永不“先删后失败”。

请评审哪种最符合“本地开发工具”的定位，且不会过度限制高级用户。

### Q2 agent 入口插桩方式

- 独立 `AgentBuilder` 织入 `DispatcherServlet`（可能插桩框架）；
- 在 `include` 包内的 Controller/HTTP 入口打点（更业务友好，但依赖框架识别）；
- 其他（Filter/Interceptor/字节码匹配注解）。

需要兼顾 Spring MVC、Spring WebFlux、异步线程池、RuoYi 等场景。

### Q3 MCP HTTP 的 JSON/SSE 策略

- 仅仅换紧凑 mapper 是否足够？
- 是否应该让 SDK 对普通请求返回 `application/json`，只有长任务用 SSE？
- 官方 Inspector / Claude / 其他 MCP 客户端对当前 SSE 的实际兼容性如何？

### Q4 Web 的安全边界

Web 控制台是否永远只做 localhost？如果用户想在局域网共享，应该：

- 只允许 MCP HTTP（带 token/认证）作为远程入口；
- 还是给 web 也加认证/白名单？

### Q5 POM 继承策略

生成的工程目前不继承原 POM 的 `<parent>`，只复制 `dependencyManagement`。对于 parent-first 工程：

- 复制原 parent 坐标并保留继承？
- 用 `help:effective-pom` 展开后内联？
- 继续“只支持显式 BOM”的已知限制，但在生成时明确拒绝/告警？

需要评估“正确性 vs 生成工程可移植性/体积”的取舍。

### Q6 代码生成的名字/签名契约

- 服务名 tag 冲突：自动加 id，还是直接报错要求用户重命名？
- RPC 重载：生成唯一名，还是跳过并写入 MIGRATION.md？
- JDK 类型（`LocalDate` 等）：补 import 全支持，还是只支持白名单并明确失败？

建议给出一个“生成器支持矩阵”，避免用户遇到编译错误才发现。

### Q7 审计无业务服务时的指标契约

`imbalanceCv` 应该是：

- 0（无业务服务，规模失衡无意义）；
- null（字段缺省）；
- 省略字段；
- 或让 CLI/MCP 在 businessServiceCount=0 时直接报错/警告？

需要和 `audit.json` 的 formatVersion 一起考虑。

### Q8 退火空社区修复与冻结基线

修复 R-11 很可能改变分区结果，进而破坏 `docs/audit-baseline/ruoyi-partition-before.json` 的 byte-identical 基线。应：

- 接受一次有意的基线更新，并在 audit-review 记录差异；
- 还是只修指标/报告，不动分区行为（保留已知偏差）；
- 还是给算法加“兼容模式”，默认保持旧行为？

### Q9 Windows 支持范围

项目是否承诺 Windows？如果不承诺，README 应明确写“仅 macOS/Linux”；如果承诺，R-17 中的路径分隔符/classpath 分隔符需要一次性修完。

### Q10 测试与 CI 策略

- 是否上 GitHub Actions `mvn -B verify`？
- codegen IT 需要 mvn、会 install 生成工程、起服务，是否放在 PR 必跑，还是 nightly？
- 是否需要为 web 引入 Spring Boot Test / MockMvc，为 agent 引入真实启动测试？

---

## 7. 附录：关键复现命令

### A. 全量构建

```bash
mvn -B verify
java -jar monogomicro-cli/target/m2m.jar --version
```

### B. 生成器空 outDir（隔离目录）

```java
// 当前工作目录放一个 keep.txt
System.out.println(Path.of("").toAbsolutePath()); // 当前目录
new MicroserviceGenerator(new AnalysisReport(), new PartitionResult(), projectNoPom)
    .generate(Path.of(""));
// 结果：keep.txt 被删除，然后因 "原项目没有 pom.xml" 失败
```

### C. agent 入口不产生 E

```bash
java -javaagent:monogomicro-agent/target/monogomicro-agent-0.3.0.jar=\
out=/tmp/trace.log,include=com.demo.,entry=DispatcherServlet \
-cp classes com.demo.Entry

grep -c $'\tE\t' /tmp/trace.log   # 0
java -jar monogomicro-cli/target/m2m.jar trace /tmp/trace.log
# 共 0 个业务用例
```

### D. MCP HTTP SSE 原始响应

```bash
# 先 initialize 拿 Mcp-Session-Id，再：
curl -sS -D - \
  -H 'Host: 127.0.0.1:<port>' \
  -H "Mcp-Session-Id: <sid>" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  http://127.0.0.1:<port>/mcp
# 观察：data: { 后面的 JSON 行没有 data: 前缀
```

### E. Web 监听地址

```bash
java -jar monogomicro-web/target/monogomicro-web.jar --server.port=18090
lsof -nP -iTCP:18090 -sTCP:LISTEN
# 输出：TCP *:18090 (LISTEN)
```

### F. parent-first POM 坐标

```xml
<project>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
  </parent>
  <groupId>com.real</groupId>
  <artifactId>app</artifactId>
  <version>1.2.3</version>
</project>
```

```java
PomReader r = new PomReader();
r.readProject(Path.of("/tmp/pomtest"));
System.out.println(r.projectGroupId() + " / " + r.projectVersion());
// 实际：org.springframework.boot / 3.3.5
// 期望：com.real / 1.2.3
```

### G. 审计 NaN

```java
AuditReport r = ArchitectureAuditor.audit(new DependencyGraph(),
        LandscapeFactory.from(new AnalysisReport()));
System.out.println(r.getSummary().getImbalanceCv()); // NaN
```

### H. shared 误判

```java
AnalysisReport rep = new AnalysisReport();
rep.setClasses(List.of(new ClassInfo("com.x.Rapid1", "rapid-service", "Rapid1.java")));
ServiceLandscape ls = LandscapeFactory.from(rep);
// business=0, shared=1
```

### I. 退火空社区

```text
labels {a:0, b:2} -> objective 0.6414213562373096
labels {a:0, b:1} -> objective 0.5
```

---

## 8. 评审记录（供回填）

| 条目 | 智谱结论 | Kimi 结论 | 最终决定 |
|---|---|---|---|
| R-01 |  | 成立（P0）。修复核验通过：校验前置 + cwd/项目根/祖先守卫 + CLI/web 显式 overwrite |  |
| R-02 |  | 成立（P1）。修复方向正确（独立 AgentBuilder，JDK 17 手工实测 E/N/X 正常）；**但新增 AgentEntryIT 在本机默认 Maven（JDK 27）下红**——fixture 默认编译为 class v69，ByteBuddy 1.15.10 解析不了、Listener.NoOp 静默吞错。IT 编译需加 `--release 17`；另见新增发现 N-01 |  |
| R-03 |  | 成立（P1）。紧凑 mapper + McpHttpIT 严格单行断言，修复正确 |  |
| R-04 |  | 成立（P1）。localhost 绑定 + 前端 esc + overwrite 显式化已修；`/api/save` `/api/generate` 的 workspace 路径校验未做——**部分修复** |  |
| R-05 |  | 成立（P1）。根 POM 固定 `projectDir/pom.xml` + 直接子元素取值 + parent 回退，修复正确 |  |
| R-06 |  | 成立（P2）。k==0→0，JSON 契约保持 number |  |
| R-07 |  | 成立（P2）。tag 冲突追加社区 id + rename 重名拒绝，双层防护 |  |
| R-08 |  | 成立（P2；静态推断可信：接口重载→同名方法+同 @PostMapping 必然编译失败）。**未修复**——提交信息声称含 R-08，实际 RpcBridgeGenerator 零改动 |  |
| R-09 |  | 成立（P2）。**未修复**——mapper XML 正则与 `com.ruoyi` 硬编码原样 |  |
| R-10 |  | 成立（P2）。词元边界匹配正确，rapid-service 不再误判 |  |
| R-11 |  | 成立（P2）。进退火前 renumber + 只统计非空社区；基线有意更新且 README 透明记录，冻结输入对拍 diff identical（Q/服务数不变实证） |  |
| R-12 |  | 成立（P2）。未修；建议发布前修，一行级范围校验（alpha/beta∈[0,1]、minSize/maxServices≥1、topN≥0），成本低 |  |
| R-13 |  | 成立（P2）。未修；先验后改，一行级调整 |  |
| R-14 |  | 成立（P3）。**未修复**——README 仍是 0.2.0 / 15 tools / agent-0.2.0.jar（提交信息声称含 R-14） |  |
| R-15 |  | 成立（P3）。已删 + .gitignore 入库 |  |
| R-16 |  | 成立（P3）。GH Actions `mvn -B verify` 为底线；当前 HEAD 在默认工具链下构建红（agent IT）恰恰证明需要 CI 守门 |  |
| R-17 |  | 认可为 backlog；其中 #4（MCP 异常无日志）、#7（MCP 结果无截断，违背 ≤4KB 设计约定）、#13（yml 自身判断依赖顺序）建议升 P2 |  |

### 新增发现（Kimi 复核中提出）

**N-01（P2）agent 在不支持的 class file 版本上静默零织入**：ByteBuddy 1.15.10 无法解析 Java 27（v69）类文件，`AgentBuilder.Listener.NoOp` 把每类的解析错误吞掉——agent 启动正常、trace 只有文件头，无任何报错。本机默认 Maven 跑在 Homebrew OpenJDK 27 上，`AgentEntryIT` 因此红。修复组合：① IT 的 fixture 编译加 `--release 17`（与环境解耦）；② agent 换带 stderr 日志的 Listener（织入失败必须可见）；③ 可选升级 ByteBuddy；④ README 写明支持的 JDK 范围。

### 开放问题回填

| 问题 | 智谱意见 | Kimi 意见 | 决定 |
|---|---|---|---|
| Q1 |  | 当前落地（校验前置 + 危险路径守卫 + 显式 overwrite）已是 B 的合理变体，够发布；下一层加固选 C（临时目录 + 原子移动），不急 |  |
| Q2 |  | 独立 AgentBuilder 方案实测有效（JDK 17）；必须补 N-01 的"织入失败可见"与 `--release 17` IT；WebFlux/异步本轮不承诺 |  |
| Q3 |  | 紧凑 mapper 足够；建议后续用官方 MCP Inspector 做一次兼容性冒烟（一次性手动验证即可） |  |
| Q4 |  | web 永远仅 localhost（已落地 127.0.0.1）；远程共享走 MCP HTTP + token（后续）；不给 web 加认证 |  |
| Q5 |  | 短期：生成时告警 + 文档已知限制（现状已记录）；中期：复制原 parent 坐标保留继承；不做 effective-pom 内联（体积/可读性差） |  |
| Q6 |  | tag 冲突自动加 id（已修）；RPC 重载：跳过并写 MIGRATION.md/manualItems（底线 = 不生成编译不过的代码）；JDK 类型：白名单扩 java.time/UUID/Optional，其余显式人工项；支持矩阵写进 README |  |
| Q7 |  | imbalanceCv = 0（已按此修）；契约保持 number 类型不变 |  |
| Q8 |  | 接受一次有意基线更新（zcode 已做且透明记录，正确姿势）；不留兼容模式——算法偏差不值得开关 |  |
| Q9 |  | 明确"仅 macOS/Linux"，README 写明；0.x 阶段不为 Windows 付成本 |  |
| Q10 |  | 上 GH Actions：PR 必跑 `mvn -B verify`（含 codegen IT，runner 自带 mvn）；agent/web 测试随修复补；CI 会立即抓住本次"JDK 27 构建红"这类环境回归 |  |

## 9. Kimi 复核总结（2026-10-17，HEAD=9eaa312）

- **修复真实性**：声称修复 13 项（R-01..R-11、R-14、R-15），实际修复 **10 项**；R-08、R-09、R-14 零改动，属提交信息过度声称，需更正记录或补修。
- **构建状态**：HEAD 在本机默认 Maven（JDK 27）下 `mvn verify` **红**（AgentEntryIT）。根因 N-01 已定位并实证（`--release 17` 后织入恢复）。修复本身在 JDK 17 下有效（手工实测 E/N/X 行齐全）。
- **行为保持**：R-11 的基线有意更新透明、可复现（冻结输入对拍 identical），其余修复未触碰分区行为。
- **剩余待办**（按优先级）：N-01（构建红，最高优先）→ R-08/R-09/R-14（补修或更正记录）→ R-04 尾巴（web 路径校验）→ R-12/R-13（便宜）→ R-16 CI（防止此类环境回归再漏）。
