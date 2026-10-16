package com.monogomicro.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.monogomicro.core.M2mException;
import com.monogomicro.core.report.JsonIO;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * MCP server 入口，双传输形态：
 * <ul>
 *   <li>默认 stdio（子进程，本地一对一，Claude Desktop / IDE 场景）；</li>
 *   <li>{@code --http [--host h] [--port p]}：MCP Streamable HTTP（{@code POST /mcp}），
 *       内嵌 Jetty 常驻服务，团队内网共享场景。HTTP 形态下所有连接共享同一个
 *       {@link WorkspaceSession}（单工作区语义，后连接者可见先前的分析/分区）。</li>
 * </ul>
 *
 * 纪律：stdio 形态的 stdout 是 JSON-RPC 通道——本类严禁 System.out；日志走 slf4j-simple（默认输出 stderr）。
 * 工具实现集中在 {@link McpTools}（纯 Java）；本类只做参数装配、调用与结果序列化。
 * 结果同时给 structuredContent 与文本 JSON（双通道，兼容新旧客户端）。
 */
public final class McpServerMain {

    private static final Logger log = LoggerFactory.getLogger(McpServerMain.class);

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of("type", "object");

    private final WorkspaceSession session = new WorkspaceSession();
    private final McpTools tools = new McpTools(session);

    /** 启动参数（--http / --host / --port），默认 stdio + 127.0.0.1:8082。 */
    record Options(boolean http, String host, int port) {
        static Options parse(String[] args) {
            boolean http = false;
            String host = "127.0.0.1";
            int port = 8082;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--http" -> http = true;
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException(
                            "未知参数：" + args[i] + "（支持 --http --host <h> --port <p>）");
                }
            }
            return new Options(http, host, port);
        }
    }

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);
        if (opts.http()) {
            startHttp(opts);
        } else {
            startStdio();
        }
    }

    private static void startStdio() throws InterruptedException {
        McpSyncServer server = build(new StdioServerTransportProvider(McpJsonDefaults.getMapper()));
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            stop.countDown();
        }));
        // stdio 会话由 transport 线程驱动；主线程挂起等待进程退出（客户端关闭 stdin / 杀进程）
        stop.await();
    }

    private static void startHttp(Options opts) throws Exception {
        HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(new JacksonMcpJsonMapper(JsonIO.mapper()))
                        .mcpEndpoint("/mcp")
                        .keepAliveInterval(Duration.ofSeconds(30))
                        // 本地/内网服务防 DNS rebinding：Host 头必须是监听地址或 loopback
                        .securityValidator(new LoopbackHostValidator(opts.host(), opts.port()))
                        .build();
        McpSyncServer server = buildHttp(provider);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(provider), "/mcp");
        Server jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(opts.host());
        connector.setPort(opts.port());
        jetty.addConnector(connector);
        jetty.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                jetty.stop();
            } catch (Exception e) {
                log.warn("Jetty 停止异常: {}", e.toString());
            }
            server.closeGracefully();
            provider.closeGracefully();
        }));
        jetty.start();
        log.info("monogomicro MCP HTTP 监听 http://{}:{}/mcp（单工作区，所有连接共享状态）；"
                        + "默认字符集 {}（非 UTF-8 时中文输出可能错位，建议 -Dfile.encoding=UTF-8）",
                opts.host(), opts.port(), java.nio.charset.Charset.defaultCharset());
        jetty.join();
    }

    /** 组装 server（stdio 路径）；transport 注入，契约测试可用管道流驱动同一条链路。 */
    static McpSyncServer build(StdioServerTransportProvider transport) {
        return register(McpServer.sync(transport)).build();
    }

    /** 组装 server（Streamable HTTP 路径），工具/资源注册与 stdio 完全同构。 */
    static McpSyncServer buildHttp(HttpServletStreamableServerTransportProvider transport) {
        return register(McpServer.sync(transport)).build();
    }

    /** 工具 + 资源注册的公共装配（stdio 与 HTTP 两种 specification 共用此基类链）。 */
    private static <S extends McpServer.SyncSpecification<S>> McpServer.SyncSpecification<S> register(
            McpServer.SyncSpecification<S> spec) {
        McpServerMain mcp = new McpServerMain();
        return spec.serverInfo("monogomicro", "1.0.0")
                .tools(mcp.toolSpecs())
                .resources(mcp.resourceSpecs());
    }

    /**
     * Host 头白名单校验（MCP 规范对本地 HTTP server 的 DNS rebinding 防护要求）：
     * 只放行监听地址与 loopback 变体（带端口与不带端口两种形式）。
     */
    static final class LoopbackHostValidator implements ServerTransportSecurityValidator {
        private final Set<String> allowed;

        LoopbackHostValidator(String bindHost, int port) {
            Set<String> hosts = new HashSet<>();
            for (String h : new String[]{bindHost, "127.0.0.1", "localhost", "[::1]"}) {
                hosts.add(h + ":" + port);
                hosts.add(h);
            }
            this.allowed = Set.copyOf(hosts);
        }

        @Override
        public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
            String host = headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("host"))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
            if (host == null || !allowed.contains(host.toLowerCase())) {
                throw new ServerTransportSecurityException(403,
                        "Host 头不在允许清单（防 DNS rebinding）：" + host);
            }
        }
    }

    // ---------- 工具注册 ----------

    private McpServerFeatures.SyncToolSpecification[] toolSpecs() {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        specs.add(tool("analyze_project",
                "静态分析目标单体项目，构建类级加权依赖图并载入会话。前置：无。后续：compute_partition。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "projectDir", Map.of("type", "string", "description", "单体项目根目录（绝对路径）"),
                                "packagePrefixes", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "业务包前缀，如 com.ruoyi"),
                                "useMavenClasspath", Map.of("type", "boolean",
                                        "description", "是否调 mvn 解析依赖 jar（默认 true，精度高但慢）"),
                                "persistAnalysisPath", Map.of("type", "string",
                                        "description", "可选：analysis.json 落盘路径")),
                        "required", List.of("projectDir", "packagePrefixes")),
                false,
                        (exchange, progressToken, args) -> tools.analyzeProject(
                        Path.of(str(args, "projectDir")),
                        strList(args, "packagePrefixes"),
                        boolOr(args, "useMavenClasspath", true),
                        pathOpt(args, "persistAnalysisPath"),
                        progressSink(exchange, progressToken))));

        specs.add(tool("load_workspace",
                "从已有 analysis.json（可选 partition.json）载入会话；不带分区时自动计算一版纯静态分区。"
                        + "projectDir 仅在后续要生成工程时必填（analyze 会话不需要）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "analysisPath", Map.of("type", "string"),
                                "partitionPath", Map.of("type", "string",
                                        "description", "可选；缺省则自动分区"),
                                "projectDir", Map.of("type", "string",
                                        "description", "可选：单体项目根，generate 阶段需要")),
                        "required", List.of("analysisPath")),
                false,
                        (exchange, progressToken, args) -> tools.loadWorkspace(
                        Path.of(str(args, "analysisPath")),
                        pathOpt(args, "partitionPath"),
                        pathOpt(args, "projectDir"))));

        specs.add(tool("compute_partition",
                "基于当前分析结果计算微服务分区（Louvain + 模拟退火）。前置：analyze_project 或 load_workspace。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "alpha", Map.of("type", "number", "description", "静态图权重占比，默认 0.5"),
                                "beta", Map.of("type", "number", "description", "动态图权重占比，默认 0.5"),
                                "minSize", Map.of("type", "integer", "description", "服务最小类数，默认 5"),
                                "maxServices", Map.of("type", "integer", "description", "服务数上限，默认 12"),
                                "seed", Map.of("type", "integer", "description", "随机种子，默认 42")),
                        "required", List.of()),
                false,
                        (exchange, progressToken, args) -> tools.computePartition(
                        dblOr(args, "alpha", 0.5),
                        dblOr(args, "beta", 0.5),
                        intOr(args, "minSize", 5),
                        intOr(args, "maxServices", 12),
                        longOr(args, "seed", 42))));

        specs.add(tool("get_partition_summary",
                "分区概览：服务列表（id/名称/规模）、核心指标（跨服务通信占比 crossRatio、模度 modularity）、"
                        + "共享内核大小。每轮对话的锚点，先看这个再看细节。",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                true,
                        (exchange, progressToken, args) -> tools.partitionSummary()));

        specs.add(tool("list_classes",
                "分页列出类清单。scope 取值：all / kernel / service:<id>（id 见 get_partition_summary）；"
                        + "stereotype 可选过滤（CONTROLLER/SERVICE/SERVICE_IMPL/MAPPER/DOMAIN/UTIL/CONFIG/ASPECT/OTHER）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "scope", Map.of("type", "string", "description", "all | kernel | service:<id>，默认 all"),
                                "stereotype", Map.of("type", "string"),
                                "page", Map.of("type", "integer", "description", "从 1 开始，默认 1"),
                                "pageSize", Map.of("type", "integer", "description", "默认 50，上限 200")),
                        "required", List.of()),
                true,
                        (exchange, progressToken, args) -> tools.listClasses(
                        strOr(args, "scope", "all"),
                        strOpt(args, "stereotype"),
                        intOr(args, "page", 1),
                        intOr(args, "pageSize", 50))));

        specs.add(tool("get_class_info",
                "单个类的详情与归属依据：构造型、模块、出入边 top5（按权重，带来源样例）、"
                        + "neighborConcentration（最强依赖去向分布——解释它为什么属于当前服务）。",
                Map.of("type", "object",
                        "properties", Map.of("fqcn", Map.of("type", "string", "description", "全限定类名")),
                        "required", List.of("fqcn")),
                true,
                        (exchange, progressToken, args) -> tools.classInfo(str(args, "fqcn"))));

        specs.add(tool("find_classes",
                "按简单名/全限定名子串搜索类（不区分大小写），返回前 20 个匹配及其所属服务。"
                        + "不确定 fqcn 时先用这个。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "stereotype", Map.of("type", "string")),
                        "required", List.of("query")),
                true,
                        (exchange, progressToken, args) -> tools.findClasses(str(args, "query"), strOpt(args, "stereotype"))));

        specs.add(tool("move_class",
                "把一个类移到另一个服务（targetService 填服务 id；-1 = 移入共享内核）。"
                        + "返回指标变化 delta（crossRatio/modularity 前后对比）与受影响的跨服务边，便于立即评估去留。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "fqcn", Map.of("type", "string"),
                                "targetService", Map.of("type", "integer", "description", "服务 id；-1 = 共享内核")),
                        "required", List.of("fqcn", "targetService")),
                false,
                        (exchange, progressToken, args) -> tools.moveClass(str(args, "fqcn"), reqInt(args, "targetService"))));

        specs.add(tool("undo",
                "撤销最近一次 move_class / rename_service（栈深上限 50）。",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                false,
                        (exchange, progressToken, args) -> tools.undo()));

        specs.add(tool("ingest_trace",
                "导入 monogomicro-agent 产出的运行时轨迹并解析为业务用例；"
                        + "下一次 compute_partition 将融合动态行为（双图权重）。前置：analyze_project 或 load_workspace。",
                Map.of("type", "object",
                        "properties", Map.of("tracePath", Map.of("type", "string",
                                "description", "agent 轨迹文件路径（-javaagent out 参数指定的 trace.log）")),
                        "required", List.of("tracePath")),
                false,
                        (exchange, progressToken, args) -> tools.ingestTrace(pathOpt(args, "tracePath"))));

        specs.add(tool("rename_service",
                "给服务起语义名（影响展示与 generate 的 svc-<tag> 目录名）。看类列表后命名是推荐工作流。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "serviceId", Map.of("type", "integer"),
                                "name", Map.of("type", "string", "description", "如 user-service / order-service")),
                        "required", List.of("serviceId", "name")),
                false,
                        (exchange, progressToken, args) -> tools.renameService(reqInt(args, "serviceId"), str(args, "name"))));

        specs.add(tool("generate_services",
                "按当前分区生成微服务工程。默认 dryRun=true 只返回统计与迁移项不落盘；"
                        + "确认后 dryRun=false 正式生成（目标目录非空需 overwrite=true，将清空重建）。"
                        + "前置：PARTITIONED；load_workspace 会话需显式 projectDir。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "projectDir", Map.of("type", "string",
                                        "description", "单体项目根；analyze 会话可省略自动继承"),
                                "outDir", Map.of("type", "string",
                                        "description", "输出目录，默认 <workspace>/services"),
                                "dryRun", Map.of("type", "boolean", "description", "默认 true"),
                                "overwrite", Map.of("type", "boolean", "description", "默认 false")),
                        "required", List.of()),
                false,
                        (exchange, progressToken, args) -> tools.generateServices(
                                pathOpt(args, "projectDir"),
                                pathOpt(args, "outDir"),
                                boolOr(args, "dryRun", true),
                                boolOr(args, "overwrite", false))));

        specs.add(tool("save_partition",
                "把当前分区（含编辑）保存为 partition.json（workspace 根内），供 CLI / web 复用。默认 <workspace>/partition.json。",
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", List.of()),
                false,
                        (exchange, progressToken, args) -> tools.savePartition(pathOpt(args, "path"))));

        specs.add(tool("get_metrics",
                "指标细档：实时 crossRatio/modularity + 每服务规模与跨服务通信量（crossOut/crossIn）"
                        + "+ compute_partition 时的 alpha/beta/用例跨度（标注不实时）。",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                true,
                        (exchange, progressToken, args) -> tools.metrics()));

        specs.add(tool("get_migration_items",
                "最近一次 generate 的人工迁移清单（事务/反射等无法自动改写项）。分区被编辑后缓存失效，需重新 generate。",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                true,
                        (exchange, progressToken, args) -> tools.migrationItems()));

        return specs.toArray(new McpServerFeatures.SyncToolSpecification[0]);
    }

    // ---------- 资源注册（客户端按需拉取大对象，不占对话上下文） ----------

    private McpServerFeatures.SyncResourceSpecification[] resourceSpecs() {
        List<McpServerFeatures.SyncResourceSpecification> specs = new ArrayList<>();

        specs.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder("m2m://analysis", "analysis")
                        .description("原始分析结果（analysis.json 等价物：类清单 + 加权依赖边）")
                        .mimeType("application/json").build(),
                (exchange, request) -> readResource("m2m://analysis",
                        () -> session.read(session::report))));

        specs.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder("m2m://partition", "partition")
                        .description("当前分区（含未保存编辑，与 save_partition 落盘内容一致）")
                        .mimeType("application/json").build(),
                (exchange, request) -> readResource("m2m://partition",
                        () -> session.read(session::partition))));

        return specs.toArray(new McpServerFeatures.SyncResourceSpecification[0]);
    }

    private McpSchema.ReadResourceResult readResource(String uri,
                                                      java.util.function.Supplier<Object> supplier) {
        Object payload = supplier.get();
        String text;
        if (payload == null) {
            text = "{\"note\": \"会话尚未载入数据，先 analyze_project 或 load_workspace\"}";
        } else {
            try {
                text = JsonIO.mapper().writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                text = "{\"error\": \"序列化失败: " + e.getMessage() + "\"}";
            }
        }
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json", text)));
    }

    // ---------- 调用与错误包装 ----------

    /** exchange 供进度通知，progressToken 为客户端在 _meta 里提供的令牌（可 null = 不通知）。 */
    private interface ToolCall {
        Object invoke(io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                      Object progressToken, Map<String, Object> args);
    }

    /** progressToken → IntConsumer 桥：token 为 null（客户端未订阅进度）时返回 null，零开销。 */
    private static java.util.function.IntConsumer progressSink(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange, Object progressToken) {
        if (exchange == null || progressToken == null) {
            return null;
        }
        return files -> exchange.progressNotification(new McpSchema.ProgressNotification(
                progressToken, (double) files, null, "已解析 " + files + " 个源文件", null));
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                          Map<String, Object> inputSchema,
                                                          boolean readOnly, ToolCall call) {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(false)
                .idempotentHint(readOnly)
                .build();
        McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .annotations(annotations)
                .outputSchema(OUTPUT_SCHEMA)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(
                        call, exchange, request.progressToken(), request.arguments()))
                .build();
    }

    private McpSchema.CallToolResult invoke(ToolCall call,
                                            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                            Object progressToken, Map<String, Object> args) {
        try {
            Object result = call.invoke(exchange, progressToken, args == null ? Map.of() : args);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(JsonIO.mapper().writeValueAsString(result))
                    .build();
        } catch (M2mException e) {
            return errorResult(e.getCode(), e.getMessage(), e.getHint());
        } catch (JsonProcessingException e) {
            return errorResult("E_INTERNAL", "结果序列化失败：" + e.getMessage(), null);
        } catch (Exception e) {
            return errorResult("E_INTERNAL", "内部错误：" + e,
                    "详细堆栈见 server stderr 日志");
        }
    }

    /** 工具级失败（isError）而非协议错误：对 agent 是可恢复的正常分支。 */
    private McpSchema.CallToolResult errorResult(String code, String message, String hint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        if (hint != null) {
            payload.put("hint", hint);
        }
        try {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .structuredContent(payload)
                    .addTextContent(JsonIO.mapper().writeValueAsString(payload))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("{\"code\":\"" + code + "\"}")
                    .build();
        }
    }

    // ---------- 参数装配 ----------

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new M2mException("E_BAD_PARAMS", "缺少必填参数：" + key,
                    "对照工具 inputSchema 的 required 字段补全后重试");
        }
        return String.valueOf(v);
    }

    /** 必填整数参数（缺省时明确报参数错误，不用魔法值兜底误导 LLM）。 */
    private static int reqInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (!(v instanceof Number n)) {
            throw new M2mException("E_BAD_PARAMS", "缺少必填整数参数：" + key,
                    "对照工具 inputSchema 的 required 字段补全后重试");
        }
        return n.intValue();
    }

    private static String strOr(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static String strOpt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static Path pathOpt(Map<String, Object> args, String key) {
        String v = strOpt(args, key);
        return v == null ? null : Path.of(v);
    }

    private static List<String> strList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            throw new M2mException("E_BAD_PARAMS", "参数 " + key + " 须为非空字符串数组", null);
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static int intOr(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static long longOr(Map<String, Object> args, String key, long def) {
        Object v = args.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    private static double dblOr(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static boolean boolOr(Map<String, Object> args, String key, boolean def) {
        Object v = args.get(key);
        return v instanceof Boolean b ? b : def;
    }
}
