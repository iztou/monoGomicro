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

- 注释和文档用中文；ponytail 模式（本项目是用户作品集项目，要求生产级质量而非 demo）
- 机器注意：用户是 Java 后端求职中，此项目用于面试展示；技术讨论按面试深度准备
- 设计类工作流：用户常拿文档给 Kimi 交叉评审后再由我复核验证（事实断言逐一到代码核实）
- 生成服务运行级 IT 已落地（codegen failsafe：生成→install→双服务启动→RPC 桥 HTTP 断言，曾修复 4 个仅运行时暴露的生成器缺陷：构造器参数改写/-parameters/单参 415/桥 JSON 序列化+repackage）；已知边界：原单体纯 parent 继承式依赖管理时生成工程缺 BOM
- README 演示 GIF 已落地（docs/demo.gif，1062×608、85 帧、~537KB）：vhs 在 macOS 直接 abort trap 不可用；工具链为 asciinema rec + agg（--theme monokai --font-size 14 --speed 1.6）。重录要点：驱动脚本 /tmp/m2m-demo-script.sh（逐字符打字效果）；headless 录制时 stty 改尺寸无效，须事后 sed cast 头 `"term":{"cols":80,"rows":24}` → 124×30（agg 按 cast 头栅格排版，宽了才不折行）；长命令用 `$M` 变量 + `../RuoYi` 相对路径压到 124 列内
- 下一步候选：（可选）MCP HTTP 传输、公开托管（已推 NAS 私有仓库 remote `nas`，不推 Gitee）

