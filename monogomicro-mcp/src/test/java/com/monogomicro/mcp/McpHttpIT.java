package com.monogomicro.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.monogomicro.core.report.JsonIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP 传输契约测试（failsafe，package 后运行）：真实子进程
 * {@code java -jar target/m2m-mcp.jar --http --port <free>} 走 MCP Streamable HTTP 完整生命周期——
 * initialize（捕获 Mcp-Session-Id）→ initialized 通知（202）→ tools/list（15 工具）→
 * 业务调用（空会话 E_NO_PARTITION 错误链路）→ 伪造 Host 头拒绝（DNS rebinding 防护）。
 * 与 {@link McpServerIT}（stdio）互补：同一套工具注册，两条传输各自锁一遍协议层。
 *
 * 客户端用裸 socket 手写 HTTP/1.1（chunked 解析），与 curl 行为一致：锁定的是 server 的
 * 协议事实。java.net.http.HttpClient 在 failsafe 环境下对 SDK 的 chunked SSE 响应稳定丢尾
 * （实测 10383/11931 字节，独立 JVM 又正常），不作测试载体。
 */
class McpHttpIT {

    private Process server;
    private int port;
    private String sessionId;
    private final StringBuilder serverLog = new StringBuilder();
    private int nextId = 1;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();
        server = new ProcessBuilder(
                        ProcessHandle.current().info().command().orElse("java"),
                        "-jar", "target/m2m-mcp.jar", "--http", "--port", String.valueOf(port))
                .redirectErrorStream(false)
                .start();
        // server stderr 持续收集（slf4j-simple 日志 + 未捕获异常都走这里），失败时随断言输出
        Thread collector = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    synchronized (serverLog) {
                        serverLog.append(l).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程结束
            }
        }, "mcp-http-it-stderr");
        collector.setDaemon(true);
        collector.start();
        awaitReady();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.destroyForcibly();
        }
    }

    @Test
    @Timeout(120)
    void fullLifecycleOverStreamableHttp() throws Exception {
        // 1. initialize：捕获会话 id，服务器自报身份
        JsonNode init = parse(post("127.0.0.1:" + port, jsonRpc("initialize", """
                {"protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"m2m-http-it","version":"0.0.1"}}""")));
        assertEquals("monogomicro", init.path("result").path("serverInfo").path("name").asText());
        assertNotNull(sessionId, "initialize 响应应携带 Mcp-Session-Id");
        assertFalse(sessionId.isBlank());

        // 2. initialized 通知（无 id 无响应体）——Streamable HTTP 约定 202 Accepted
        assertEquals(202, post("127.0.0.1:" + port, jsonRpc("notifications/initialized", null)).status());

        // 3. tools/list：与 stdio 同一套注册，15 个工具（SSE 响应，取 data: 行）
        JsonNode tools = parse(post("127.0.0.1:" + port, jsonRpc("tools/list", "{}")));
        assertEquals(17, tools.path("result").path("tools").size());

        // 4. 业务调用（空会话）：工具级错误可恢复返回，验证状态机链路在 HTTP 会话上同样成立
        JsonNode summary = parse(post("127.0.0.1:" + port, jsonRpc("tools/call",
                "{\"name\":\"get_partition_summary\",\"arguments\":{}}")));
        assertTrue(summary.path("result").path("isError").asBoolean());
        assertEquals("E_NO_PARTITION",
                summary.path("result").path("structuredContent").path("code").asText());
    }

    @Test
    @Timeout(60)
    void spoofedHostHeaderRejected() throws Exception {
        // 先完成握手拿到合法会话，再伪造 Host 头（DNS rebinding 负路径）
        parse(post("127.0.0.1:" + port, jsonRpc("initialize", """
                {"protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"m2m-http-it","version":"0.0.1"}}""")));
        assertEquals(403, post("evil.example.com", jsonRpc("tools/list", "{}")).status());
    }

    // ---------- 进程与裸 HTTP 帮手 ----------

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** TCP 探测就绪（不能用 GET：GET /mcp 会打开 SSE 推送流且不关闭）。 */
    private void awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!server.isAlive()) {
                StringBuilder err = new StringBuilder("server 进程提前退出：");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
                    r.lines().limit(30).forEach(l -> err.append('\n').append(l));
                }
                fail(err.toString());
            }
            try (Socket probe = new Socket()) {
                probe.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (Exception retry) {
                Thread.sleep(300);
            }
        }
        fail("HTTP server 30s 未就绪");
    }

    private String jsonRpc(String method, String params) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"method\":\"").append(method).append('"');
        if (params != null) {
            sb.append(",\"params\":").append(params);
        }
        if (!method.startsWith("notifications/")) {
            sb.append(",\"id\":").append(nextId++);
        }
        return sb.append('}').toString();
    }

    /** HTTP 响应三元组（头已小写化，便于大小写不敏感读取）。 */
    record Resp(int status, Map<String, String> headers, String body) {
        String contentType() {
            return headers.getOrDefault("content-type", "");
        }
    }

    /** 裸 socket POST /mcp：读完整响应（支持 chunked），自动携带/捕获 Mcp-Session-Id。 */
    private Resp post(String host, String json) throws Exception {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String head = "POST /mcp HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + (sessionId != null ? "Mcp-Session-Id: " + sessionId + "\r\n" : "")
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(30_000);
            OutputStream out = sock.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            InputStream in = new BufferedInputStream(sock.getInputStream());

            String statusLine = readLine(in);
            assertNotNull(statusLine, "无响应状态行");
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }
            String sid = headers.get("mcp-session-id");
            if (sid != null && !sid.isBlank()) {
                sessionId = sid;
            }

            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            if (headers.getOrDefault("transfer-encoding", "").contains("chunked")) {
                while (true) {
                    int size = Integer.parseInt(readLine(in).trim(), 16);
                    if (size == 0) {
                        break;
                    }
                    copyN(in, bodyBuf, size);
                    readLine(in); // chunk 数据后的 CRLF
                }
            } else if (headers.containsKey("content-length")) {
                copyN(in, bodyBuf, Long.parseLong(headers.get("content-length")));
            } else {
                in.transferTo(bodyBuf);
            }
            return new Resp(status, headers, bodyBuf.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * 响应可能是纯 JSON，也可能是 SSE 流（text/event-stream）。SDK 的 SSE 事件只在 JSON
     * 首行前写一个 data: 前缀，JSON 本体（INDENT_OUTPUT 多行）原样跟随直到空行（事件边界）——
     * 因此解析从 data: 行起收集到空行为止，而非逐行找 data: 前缀。
     */
    private JsonNode parse(Resp resp) throws Exception {
        assertEquals(200, resp.status(), "body=" + resp.body());
        String ct = resp.contentType();
        String body = resp.body();
        try {
            if (ct.contains("text/event-stream")) {
                StringBuilder data = new StringBuilder();
                boolean collecting = false;
                for (String l : body.split("\n", -1)) {
                    if (!collecting) {
                        if (l.startsWith("data:")) {
                            collecting = true;
                            data.append(l.length() > 5 && l.charAt(5) == ' ' ? l.substring(6) : l.substring(5));
                        }
                    } else if (l.isEmpty()) {
                        break; // SSE 事件结束（空行）
                    } else {
                        data.append('\n').append(l);
                    }
                }
                assertFalse(data.isEmpty(), "SSE 响应无 data 行：" + body);
                return JsonIO.mapper().readTree(data.toString());
            }
            return JsonIO.mapper().readTree(body);
        } catch (JsonProcessingException e) {
            synchronized (serverLog) {
                fail("响应体 JSON 解析失败（len=" + body.length() + " ct=" + ct + "）：[" + body
                        + "]\n=== server stderr ===\n" + serverLog, e);
            }
            return null;
        }
    }

    /** 按字节读一行（\n 结尾，剥离 \r）——chunk 大小是字节数，不能按字符读。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            buf.write(c);
        }
        String s = buf.toString(StandardCharsets.UTF_8);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buffer = new byte[4096];
        long left = n;
        while (left > 0) {
            int r = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (r < 0) {
                break;
            }
            out.write(buffer, 0, r);
            left -= r;
        }
    }
}
