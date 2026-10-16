package com.monogomicro.mcp;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.report.JsonIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试（failsafe 集成测试，package 后运行）：拉起真实子进程 stdio server
 * （java -jar target/m2m-mcp.jar），走完整 JSON-RPC 生命周期——握手 → tools/resources 列表 →
 * 业务链路（load/rename/save/dry-run 生成/资源读取/正式生成）→ 错误路径。
 * 防的是「单测全绿但协议层断了」这类集成回归；进程边界与 Claude Desktop 完全一致。
 */
class McpServerIT {

    private Process server;
    private PrintWriter stdin;
    private final ConcurrentLinkedQueue<String> stdoutLines = new ConcurrentLinkedQueue<>();
    private final StringBuilder stderrLog = new StringBuilder();
    private Path fixtureDir;
    private int nextId = 1;

    /** fat jar 由 shade 在 package 阶段产出（failsafe 在其后运行，保证存在）。 */
    private static String fatJar() {
        return "target/m2m-mcp.jar";
    }

    @BeforeEach
    void startServer() throws Exception {
        assertTrue(Files.isRegularFile(Path.of(fatJar())), "fat jar 未产出：" + fatJar());

        fixtureDir = Files.createTempDirectory("m2m-it");
        writeFixture();

        server = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", fatJar())
                .redirectErrorStream(false)
                .start();
        stdin = new PrintWriter(server.getOutputStream(), true);

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
                r.lines().forEach(stdoutLines::add);
            } catch (Exception ignore) {
                // 进程结束
            }
        }, "it-stdout-reader");
        reader.setDaemon(true);
        reader.start();
        // stderr 持续消费并收集：日志写满管道缓冲会卡死 server；超时时打印排障
        Thread stderrDrain = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(server.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    synchronized (stderrLog) {
                        stderrLog.append(line).append('\n');
                    }
                }
            } catch (Exception ignore) {
                // 进程结束
            }
        }, "it-stderr-drain");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        Map<String, Object> init = call("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "it", "version", "0")));
        assertNotNull(((Map<?, ?>) init.get("result")).get("serverInfo"));
        notify_("notifications/initialized");
    }

    @AfterEach
    void stopServer() {
        if (server != null && server.isAlive()) {
            server.destroyForcibly();
        }
    }

    @Test
    @Timeout(180)
    void fullProtocolLifecycle() throws Exception {
        Map<String, Object> tools = call("tools/list", Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolList = (List<Map<String, Object>>) ((Map<?, ?>) tools.get("result")).get("tools");
        assertEquals(15, toolList.size());
        assertTrue(toolList.stream().allMatch(t -> t.get("annotations") != null), "每个工具都应带 annotations");

        Map<String, Object> resources = call("resources/list", Map.of());
        Object resourceList = ((Map<?, ?>) resources.get("result")).get("resources");
        assertEquals(2, ((List<?>) resourceList).size());

        Map<String, Object> loaded = toolCall("load_workspace",
                Map.of("analysisPath", fixtureDir.resolve("analysis.json").toString(),
                        "projectDir", fixtureDir.resolve("mono").toString()));
        assertEquals("PARTITIONED", textPayload(loaded).get("state"));

        Map<String, Object> renamed = toolCall("rename_service",
                Map.of("serviceId", 0, "name", "user-core"));
        assertEquals("user-core", textPayload(renamed).get("name"));

        Map<String, Object> saved = toolCall("save_partition", Map.of());
        assertTrue(Files.exists(fixtureDir.resolve("partition.json")));
        assertTrue(String.valueOf(textPayload(saved).get("saved")).contains("partition.json"));

        Map<String, Object> generated = toolCall("generate_services",
                Map.of("outDir", fixtureDir.resolve("services").toString(), "dryRun", true));
        assertEquals(true, textPayload(generated).get("dryRun"));
        assertFalse(Files.exists(fixtureDir.resolve("services")), "dry-run 不得写盘");

        Map<String, Object> migration = toolCall("get_migration_items", Map.of());
        assertTrue(textPayload(migration).containsKey("total"));

        Map<String, Object> resource = call("resources/read", Map.of("uri", "m2m://partition"));
        assertNotNull(((Map<?, ?>) resource.get("result")).get("contents"));

        Map<String, Object> real = toolCall("generate_services",
                Map.of("outDir", fixtureDir.resolve("services").toString(),
                        "dryRun", false, "overwrite", true));
        assertEquals(false, textPayload(real).get("dryRun"));
        assertTrue(Files.exists(fixtureDir.resolve("services/pom.xml")), "聚合 pom 已生成");

        Map<String, Object> bad = toolCall("move_class",
                Map.of("fqcn", "no.Such", "targetService", 0));
        Map<?, ?> result = (Map<?, ?>) bad.get("result");
        assertEquals(Boolean.TRUE, result.get("isError"));
        assertEquals("E_UNKNOWN_CLASS", textPayload(bad).get("code"));
    }

    // ---------- 协议驱动 ----------

    private Map<String, Object> call(String method, Object params) throws InterruptedException {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        Map<String, Object> response = awaitResponse(id, 120);
        if (response == null) {
            synchronized (stderrLog) {
                throw new AssertionError("响应超时：" + method + "。server 存活=" + server.isAlive()
                        + "，stderr：\n" + stderrLog.substring(0, Math.min(stderrLog.length(), 3000)));
            }
        }
        return response;
    }

    private void notify_(String method) {
        send(Map.of("jsonrpc", "2.0", "method", method));
    }

    private Map<String, Object> toolCall(String name, Map<String, Object> arguments)
            throws InterruptedException {
        Map<String, Object> r = call("tools/call", Map.of("name", name, "arguments", arguments));
        Map<?, ?> result = (Map<?, ?>) r.get("result");
        assertNotNull(result, "工具调用缺少 result：" + name);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> textPayload(Map<String, Object> toolResult) {
        Map<?, ?> result = (Map<?, ?>) toolResult.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        try {
            return JsonIO.mapper().readValue((String) content.get(0).get("text"), Map.class);
        } catch (Exception e) {
            throw new AssertionError("content[0].text 不是合法 JSON：" + content.get(0).get("text"), e);
        }
    }

    private void send(Map<String, Object> message) {
        try {
            // stdio 按行分帧：必须单行 JSON（JsonIO.mapper 带 INDENT_OUTPUT 会拆行，server 只读到 "{"）
            stdin.println(JsonIO.mapper().copy()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(message));
            stdin.flush();
        } catch (Exception e) {
            throw new AssertionError("发送失败", e);
        }
    }

    private Map<String, Object> awaitResponse(int id, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (server != null && !server.isAlive()) {
                throw new AssertionError("server 进程提前退出，exit=" + server.exitValue());
            }
            for (String line : stdoutLines) {
                if (line.contains("\"id\":" + id) || line.contains("\"id\": " + id)) {
                    try {
                        return JsonIO.mapper().readValue(line, Map.class);
                    } catch (Exception e) {
                        throw new AssertionError("响应行不是合法 JSON：" + line, e);
                    }
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    // ---------- fixture ----------

    private void writeFixture() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setProject("/it");
        List<ClassInfo> classes = new ArrayList<>();
        for (String fqcn : new String[]{"a.A", "a.B", "b.C", "k.K"}) {
            classes.add(new ClassInfo(fqcn, "mono", fqcn.replace('.', '/') + ".java"));
        }
        report.setClasses(classes);
        DependencyEdge e1 = new DependencyEdge("a.A", "a.B");
        e1.setWeight(10);
        DependencyEdge e2 = new DependencyEdge("a.A", "b.C");
        e2.setWeight(5);
        report.setEdges(List.of(e1, e2));
        JsonIO.write(report, fixtureDir.resolve("analysis.json"));

        PartitionResult partition = new PartitionResult();
        partition.setKernelClasses(new ArrayList<>(List.of("k.K")));
        PartitionResult.Community c0 = new PartitionResult.Community();
        c0.setId(0); c0.setName("alpha"); c0.setClasses(new ArrayList<>(List.of("a.A", "a.B")));
        PartitionResult.Community c1 = new PartitionResult.Community();
        c1.setId(1); c1.setName("beta"); c1.setClasses(new ArrayList<>(List.of("b.C")));
        partition.setCommunities(List.of(c0, c1));
        JsonIO.write(partition, fixtureDir.resolve("seed-partition.json"));

        Path mono = fixtureDir.resolve("mono");
        for (String pkg : new String[]{"a", "b", "k"}) {
            Files.createDirectories(mono.resolve(pkg));
        }
        Files.writeString(mono.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.demo</groupId><artifactId>demo</artifactId><version>1.0</version>
                </project>
                """);
        Files.writeString(mono.resolve("a/A.java"), "package a;\npublic class A { B b; }");
        Files.writeString(mono.resolve("a/B.java"), "package a;\npublic class B {}");
        Files.writeString(mono.resolve("b/C.java"), "package b;\npublic class C {}");
        Files.writeString(mono.resolve("k/K.java"), "package k;\npublic class K {}");
    }
}
