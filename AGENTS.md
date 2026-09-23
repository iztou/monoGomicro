# monoGomicro 项目说明（会话自动加载）

Java 单体 → 微服务拆分建议工具，IBM Mono2Micro 复刻。2026-09-23 完成 v1 全链路，全部验收通过。

## 工程结构（五模块 Maven，Java 17）

- `monogomicro-core`：静态分析（JavaParser 符号解析）、加权依赖图、Louvain + 退火分区、共享内核检测 —— 无框架纯库，单测在 `core/src/test`
- `monogomicro-agent`：ByteBuddy java agent，运行时轨迹采集（premain，shade 打包）
- `monogomicro-codegen`：分区 → 微服务工程生成（RPC 桥、内核闭包、共享类提升、MIGRATION.md）
- `monogomicro-cli`：`m2m analyze|partition|trace|generate`（fat jar：`monogomicro-cli/target/m2m.jar`）
- `monogomicro-web`：Spring Boot + D3 本地控制台（端口默认 8080，冒烟时用过 7777）

## 已验证状态（RuoYi 4.8.3 实测）

- analyze：290 类 / 980 边；partition：Q=0.81、12 服务；generate：**生成工程 `mvn compile` 通过**（结果在 `m2m-out/`，生成样例曾输出到 /tmp/m2m-services）
- 测试 5/5 过；web 拖拽重算指标正常；agent demo 轨迹解析正确
- 验证样本：RuoYi 在 `/Users/long/Work/gitRepo/RuoYi`

## 关键坑（改代码前必读）

- 基础包名必须避开 Java 关键字（曾用 `com.long.*` 直接编译失败，现为 `com.monogomicro`）
- JavaParser：`getPrimaryType()` 依赖文件名上下文，`parse(String)` 时为空——统一用 `getType(0)`
- 扫描器刻意覆盖：@interface/枚举注册、泛型参数递归、字段级注解、类字面量（同包无 import）、varargs（`asString()` 不含 `...`）
- 权重融合必须放大 1000 倍再取整，否则小边全部截断为 0（图失联）
- 代码生成铁律：服务间禁止类级依赖（跨服务引用一律提升进 common 或走 RPC 桥）；内核依赖必须闭包；拷贝保留原文件名（一个文件可含多顶层类型）
- Maven 部分构建 `-pl <module>` 会因兄弟模块不在本地仓库而失败——一律全量 `mvn -DskipTests package`

## 约定

- 注释和文档用中文；ponytail 模式（本项目是用户作品集项目，要求生产级质量而非 demo）
- 机器注意：用户是 Java 后端求职中，此项目用于面试展示；技术讨论按面试深度准备
- 下一步候选：生成服务的集成测试、README 演示 GIF、推送 Gitee
