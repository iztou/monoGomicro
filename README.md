# monoGomicro

Java 单体应用 → 微服务拆分建议工具（IBM Mono2Micro 的开源复刻）。

输入一个 Java/Maven 单体项目，输出：微服务分区建议（可交互编辑）、共享内核识别、
可直接编译的微服务工程骨架（跨服务调用自动改写为 REST）、人工迁移清单。

![CLI 三步演示：analyze → partition → generate（RuoYi 4.8.3 实录）](docs/demo.gif)

## 与 IBM Mono2Micro 的对标

| Mono2Micro 能力 | 本项目实现 | 模块 |
|---|---|---|
| 静态分析 | JavaParser 符号解析（源码 + JDK + Maven 依赖 jar），import/泛型参数/字段注解/枚举/@interface/类字面量/varargs 全覆盖 | core |
| 运行时分析 | ByteBuddy java agent 插桩，按 HTTP 入口切分业务用例，重建调用链 | agent |
| AI 分区建议 | 双图融合 + Louvain 社区发现 + 模拟退火多目标优化（通信成本/用例跨度/规模均衡） | core |
| 图形视图与分区编辑 | 本地 Web 控制台：D3 力导向图 + 拖拽调整 + 指标实时重算 | web |
| 代码重构 | 分区 → Maven 多模块微服务工程：跨服务注入改写为 REST 客户端/Controller 桥、共享 DTO 自动提取、内核依赖闭包、MIGRATION.md | codegen |

## 架构

```
monogomicro-core      解析器 / 依赖图 / 分区算法 / 指标 —— 无框架纯库
monogomicro-agent     运行时插桩 agent（ByteBuddy，premain）
monogomicro-codegen   分区 → 微服务工程生成（源码改写）
monogomicro-cli       m2m 命令行（analyze / partition / trace / generate）
monogomicro-web       本地控制台（Spring Boot 薄 REST + D3 单页）
```

## 快速开始

```bash
mvn -DskipTests package

# 1. 静态分析（自动解析 Maven classpath）
java -jar monogomicro-cli/target/m2m.jar analyze /path/to/monolith -p com.your.pkg -o m2m-out

# 2. 分区建议（可选融合运行时轨迹）
java -jar monogomicro-cli/target/m2m.jar partition -a m2m-out/analysis.json -o m2m-out

# 3.（可选）运行时采集：启动你的应用
java -javaagent:monogomicro-agent/target/monogomicro-agent-0.1.0.jar=\
out=/tmp/trace.log,include=com.your.pkg.,entry=DispatcherServlet -jar app.jar
#    然后带轨迹重新分区：
java -jar monogomicro-cli/target/m2m.jar partition -a m2m-out/analysis.json -t /tmp/trace.log -o m2m-out

# 4. 生成微服务工程（输出目录可 mvn compile）
java -jar monogomicro-cli/target/m2m.jar generate \
  -a m2m-out/analysis.json -p m2m-out/partition.json \
  --project /path/to/monolith -o m2m-out/services

# 5.（可选）可视化控制台：http://localhost:8080
java -jar monogomicro-web/target/monogomicro-web.jar
```

## MCP 模式（LLM 在环）

`monogomicro-mcp` 把流水线暴露为 [MCP](https://modelcontextprotocol.io) server（stdio 传输），
Claude Desktop / 任意 MCP 客户端可对话式完成「分析 → 摘要 → 追问归属依据 → 移动类看指标变化 → 撤销」：

```json
// Claude Desktop -> Settings -> Developer -> Edit Config (claude_desktop_config.json)
{
  "mcpServers": {
    "monogomicro": {
      "command": "java",
      "args": ["-jar", "/绝对路径/monogomicro-mcp/target/m2m-mcp.jar"]
    }
  }
}
```

工具一览（15 个）：`analyze_project` / `load_workspace` / `ingest_trace` / `compute_partition` /
`generate_services`（默认 dry-run）/ `save_partition`（流水线）；`get_partition_summary` /
`list_classes` / `get_class_info` / `find_classes` / `get_metrics` / `get_migration_items`
（观察，渐进式披露，单工具返回 ≤4KB）；`move_class`（返回指标 delta）/ `rename_service` /
`undo`（编辑）。另有资源 `m2m://analysis`、`m2m://partition` 供客户端按需拉取大对象。
错误统一结构化（`{code, message, hint}`，拼错类名/服务 id 附相近候选）；写路径限制在 workspace 根内；
`analyze_project` 支持进度通知（客户端传 `_meta.progressToken` 即每 50 个源文件收到进度）。
设计文档见 `docs/mcp-design.md`；契约测试（真子进程 stdio JSON-RPC 全链路）随 `mvn verify` 运行。

调试与体验：用官方 Inspector 图形化调用全部工具——

```bash
npx @modelcontextprotocol/inspector java -jar monogomicro-mcp/target/m2m-mcp.jar
# 打开提示的 URL：左侧 Tools 列表逐个试；Resources 页看 m2m://analysis
```

一段真实对话（RuoYi，Claude Desktop）：

> **我**：帮我看看这个单体怎么拆
> **AI**：`load_workspace` 载入分析结果…12 个服务，模度 0.55，跨服务通信占比 19.9%，
>         共享内核 7 个类。`get_partition_summary` 摘要如下：job-service(16) / file-service(12) / …
> **我**：SysUserServiceImpl 为什么在 user-service？
> **AI**：`get_class_info` → SERVICE_IMPL，25 条出边中 8 条连接 user-service 的类
>         （`neighborConcentration`），最强依赖 SysUserMapper(weight 12) 同服务。
> **我**：把它移到 job-service 试试
> **AI**：`move_class` → crossRatio 0.1994 → **0.2134**，modularity 0.5476 → **0.5379**（两项均变差）。
>         建议撤销：`undo` 已恢复，指标回落。

## RuoYi 4.8.3 实测（285 源文件）

| 阶段 | 结果 |
|---|---|
| analyze | 290 类注册 / 980 条加权依赖边 / 92 依赖 jar 自动解析 |
| partition | 模度 Q=0.81，12 个服务，跨服务通信占比 ~15% |
| 已知边界恢复 | ruoyi-quartz → job 服务（16/19 类）、ruoyi-generator → table 服务、system 各功能域切成 user/role/post/dict 等垂直切片 |
| generate | 12 服务 + common 共享内核 / 16 组 RPC 桥 / 13 处注入自动改写 / 21 个 mapper XML 迁移 |
| 验收 | **生成工程 `mvn compile` 通过**，残余 4 项列入 MIGRATION.md |

## 算法要点

- **依赖边权重**：继承 3 / 字段 2 / 方法调用·参数·注解·构造 1，来源行号全程留痕
- **双图融合**：`α·静态/max + β·动态/max`（默认 0.5/0.5），放大 1000 倍取整防小边截断
- **Louvain**：完整两阶段（局部移动 + 图聚合），手写实现
- **退火精化**：目标函数 = 0.5·跨服务通信占比 + 0.3·用例跨度（Mono2Micro 核心洞察：用例调用链跨服务越多延迟越差）+ 0.2·规模失衡度
- **共享内核**：高扇入且横跨 ≥3 社区的类自动剥离；生成期再做内核依赖闭包 + 跨服务共享类提升（服务间禁止类级依赖）
- **RPC 改写规则**：跨服务服务接口的字段注入 → 消费端镜像客户端（RestTemplate）+ 提供端 `@RestController` 桥；varargs/泛型/多参数原样镜像

## 能力边界（不装全能）

- 事务/锁/反射跨服务边界的语义保持无法全自动，全部列入 MIGRATION.md
- 客户端返回值强转依赖 JSON 反序列化，关键接口上线前建议补显式 DTO
- 运行时分析质量取决于用例覆盖度；纯静态模式是保底
- 目标栈为 Spring Boot（原版是 Open Liberty）；依赖管理须显式声明
  （根 pom 带 `dependencyManagement`/BOM——RuoYi 式写法；纯 parent 继承式管理的单体
  生成工程会缺 BOM 版本，需手工补）
- 运行生成的服务需要数据库与配置迁移（本工具负责编译级正确 + 迁移清单；
  RPC 桥运行级正确由 `codegen` 的 failsafe 集成测试守护：生成工程 install →
  双服务启动 → HTTP 断言桥贯通）

## 测试

`mvn test`：Louvain 双簇恢复、单社区、空图；轨迹解析（单线程用例切分、多线程交织）。

## 许可证

[Apache-2.0](LICENSE)
