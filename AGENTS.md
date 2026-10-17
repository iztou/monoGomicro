# monoGomicro 项目说明（会话自动加载）

Java 单体 → 微服务拆分建议工具，IBM Mono2Micro 复刻。2026-09-23 完成 v1 全链路；2026-10-16 完成 MCP 化（M0-M3）。

## 工程结构（六模块 Maven，Java 17）

- `monogomicro-core`：静态分析（JavaParser 符号解析）、加权依赖图、Louvain + 退火分区、共享内核检测、PartitionMetrics —— 无框架纯库，单测在 `core/src/test`
- `monogomicro-agent`：ByteBuddy java agent，运行时轨迹采集（premain，shade 打包）
- `monogomicro-codegen`：分区 → 微服务工程生成（RPC 桥、内核闭包、共享类提升、MIGRATION.md、GenerateOptions dry-run/overwrite）
- `monogomicro-cli`：`m2m analyze|partition|trace|generate`（fat jar：`monogomicro-cli/target/m2m.jar`）
- `monogomicro-web`：Spring Boot + D3 本地控制台（端口默认 8080，冒烟时用过 7777）
- `monogomicro-mcp`：MCP server（官方 Java SDK 2.1.0，stdio，fat jar `monogomicro-mcp/target/m2m-mcp.jar`）——15 工具 + 2 资源 + 进度通知 + 结构化错误；设计文档 `docs/mcp-design.md`（经三轮 Kimi 交叉评审）

## 已验证状态（RuoYi 4.8.3 实测）

- analyze：290 类 / 980 边；partition：Q=0.81、12 服务；generate：**生成工程 `mvn compile` 通过**（结果在 `m2m-out/`，生成样例曾输出到 /tmp/m2m-services）
- MCP：`mvn verify` 43/43（单测 42 + 真子进程契约测试 McpServerIT）；stdio 冒烟——load 12 服务/move 看 delta/undo/dry-run 零触碰/正式生成/进度通知 5 条/拼错类名出相近候选
- web 拖拽重算指标正常；agent demo 轨迹解析正确
- 验证样本：RuoYi 在 `/Users/long/Work/gitRepo/RuoYi`；仓库已推 NAS（remote 名 `nas`，不推 Gitee）

## 关键坑（改代码前必读）

- 基础包名必须避开 Java 关键字（曾用 `com.long.*` 直接编译失败，现为 `com.monogomicro`）
- JavaParser：`getPrimaryType()` 依赖文件名上下文，`parse(String)` 时为空——统一用 `getType(0)`
- 扫描器刻意覆盖：@interface/枚举注册、泛型参数递归、字段级注解、类字面量（同包无 import）、varargs（`asString()` 不含 `...`）
- 权重融合必须放大 1000 倍再取整，否则小边全部截断为 0（图失联）
- 代码生成铁律：服务间禁止类级依赖（跨服务引用一律提升进 common 或走 RPC 桥）；内核依赖必须闭包；拷贝保留原文件名（一个文件可含多顶层类型）；MicroserviceGenerator 一次性实例（dry-run 后正式生成须新建）
- Maven 部分构建 `-pl <module>` 会因兄弟模块不在本地仓库而失败——必须带 `-am` 或全量构建
- MCP SDK 用 jackson2 绑定（mcp-json-jackson2 + databind 2.21.1）：默认 jackson3 绑定的 annotations 沿用 com.fasterxml 包名，shade 合并会同名冲突（JsonSerializeAs 丢失）
- **stdio 按行分帧：客户端发送必须单行 JSON**（JsonIO.mapper 的 INDENT_OUTPUT 会拆行，server 只读到 "{" 即 EOF）
- mcp 模块严禁 System.out（stdout 是 JSON-RPC 通道）；日志 slf4j-simple 默认 stderr

## 约定

- **提交信息一律英文**（2026-10-16 用户定，国际化）；README 双语：英文主（README.md）+ 中文（README.zh-CN.md）互链；注释与内部文档仍中文

- 注释和文档用中文；ponytail 模式（本项目是用户作品集项目，要求生产级质量而非 demo）
- 机器注意：用户是 Java 后端求职中，此项目用于面试展示；技术讨论按面试深度准备
- 设计类工作流：用户常拿文档给 Kimi 交叉评审后再由我复核验证（事实断言逐一到代码核实）
- 生成服务运行级 IT 已落地（codegen failsafe：生成→install→双服务启动→RPC 桥 HTTP 断言，曾修复 4 个仅运行时暴露的生成器缺陷：构造器参数改写/-parameters/单参 415/桥 JSON 序列化+repackage）；已知边界：原单体纯 parent 继承式依赖管理时生成工程缺 BOM
- README 演示 GIF 已落地（docs/demo.gif，1062×608、85 帧、~537KB）：vhs 在 macOS 直接 abort trap 不可用；工具链为 asciinema rec + agg（--theme monokai --font-size 14 --speed 1.6）。重录要点：驱动脚本 /tmp/m2m-demo-script.sh（逐字符打字效果）；headless 录制时 stty 改尺寸无效，须事后 sed cast 头 `"term":{"cols":80,"rows":24}` → 124×30（agg 按 cast 头栅格排版，宽了才不折行）；长命令用 `$M` 变量 + `../RuoYi` 相对路径压到 124 列内
- **Maven 坐标已切发布形态（2026-10-16）**：groupId `io.github.iztou` / version `0.1.0`（Central namespace，与 Java 包名 `com.monogomicro` 有意解耦，包名不动）；根 pom 带 url/licenses/developers/scm + `release` profile（source/javadoc/gpg/central-publishing，默认关闭）。发布：`mvn -Prelease deploy -pl .,monogomicro-core,monogomicro-codegen,monogomicro-mcp`（mcp 发布件是 fat jar，reduced-pom 已去掉被 shade 的兄弟依赖）
- **分发已落地（2026-10-16，0.1.0 首发即全渠道）**：Maven Central 五构件（parent/core/codegen/mcp/cli）PUBLISHED；Homebrew tap `iztou/homebrew-tap`（m2m / m2m-mcp formula，源=Central fat jar）+ jbang 直跑均本机实测通过。发布实操要点：Portal API `POST /api/v1/publisher/deployment/{id}` 可替代网页点 Publish（status 端点常年 500，用 deployments 列表端点查）；**勿单独跑 `central-publishing:publish` goal**（不带生命周期会发出缺签名的残次部署——只发 parent 且无 .asc，报 Missing signature）；gpg 2.5 在 macOS `--send-keys` 报 Invalid argument，用 `curl -X POST https://keyserver.ubuntu.com/pks/add --data-urlencode keytext@pubkey.asc` 绕过；brew `write_jar_script` 正确参数是 `java_version: "17"` 关键字（位置第三参是 JVM opts）。已知小债：CLI `--version` 硬编码 "m2m 1.0.0" 未随 0.1.0 同步（Main.java:21，下版改读 manifest）
- **MCP HTTP 传输已落地（2026-10-16）**：`--http [--host --port]` 切 Streamable HTTP（SDK `HttpServletStreamableServerTransportProvider` + 内嵌 Jetty 12 ee10），stdio 默认不变；工具注册泛型化共用；`LoopbackHostValidator` 防 DNS rebinding；契约测试 McpHttpIT（裸 socket 客户端）。**坑：SDK 的 SSE 响应只在 JSON 首行前写 data: 前缀、多行本体到空行截止——按事件边界收集解析；java.net.http ofString 对该 chunked SSE 在 failsafe 环境行为不稳，IT 客户端用裸 socket**
- **0.2.0 已发布（2026-10-16）**：五构件一发（parent/core/codegen/mcp/cli@0.2.0，含 MCP HTTP 传输）；版本号改读 jar manifest（core.Versions + shade 注入 Implementation-Version——picocli 注解值须编译期常量，CLI 走 `@Command(versionProvider=)`，MCP serverInfo 直接调）；README 双语（英文主 + zh-CN）；提交信息改英文
- **audit（微服务架构体检）已全部完成（2026-10-17，main = develop = a166518）**：七阶段闭环 + Kimi 两轮评审修正（B1-B5）。RuoYi-Cloud 真实样本冒烟核验通过——亮点：业务服务编译期零直接依赖（边界耦合率 0.0000、跨服务全走 Feign api 模块）、system 模块 71 类上帝服务、dict 域 13 类与 system 零边（自包含子域堆积）；零 delta 建议是数学事实非 bug。入口：CLI `m2m audit -a [-s services.json] [-c audit-config.json] [--suggest]`、MCP `audit_architecture`/`suggest_refactoring`（共 17 工具）、web `POST /api/audit`。全仓 71 单测 + 4 IT。设计/评审文档 docs/audit-design.md、docs/audit-review.md；行为保持对拍基线 docs/audit-baseline/（**冻结 analysis 输入版**）
- **0.3.0 缓发（2026-10-17 用户定）**：Central 月流量配额耗尽，下月再发（deploy + tag v0.3.0 + brew formula sha 更新 + README 安装段版本号）。代码已在 main，发布是纯操作
- 下一步候选：维护态。remote：`github` + `nas` 双推；tap iztou/homebrew-tap
- 下一步候选：完成 audit 七阶段后合 develop → main，发布 0.3.0。remote 现状：`github`（公开仓库 iztou/monoGomicro）+ `nas`（私有备份），推送时两个都推；tap 仓库 iztou/homebrew-tap

