package com.monogomicro.codegen;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.report.JsonIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成服务的运行级集成测试（failsafe，verify 阶段执行）：
 * 迷你单体（consumer 构造器注入 provider 接口，跨服务分区）→ generate →
 * 生成工程 mvn install（编译级）→ java -jar 起两个服务 → HTTP 断言 RPC 桥贯通。
 *
 * 这条链路曾挖出四个真实缺陷：构造器参数类型不改写（编译失败）、pom 缺 -parameters
 * （@RequestParam 运行时炸）、单参 RPC 发裸值 415、桥返回裸 String 反序列化炸——
 * 它们全部编译级通过、只在运行时暴露，本测试是唯一防线。
 */
class GeneratedServicesRpcIT {

    private Path work;
    private final List<Process> servers = new ArrayList<>();
    private final StringBuilder serverLogs = new StringBuilder();

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(mvnAvailable(), "本机无 mvn，跳过生成工程集成测试");
        work = Files.createTempDirectory("m2m-codegen-it");
        writeFixture();
    }

    @AfterEach
    void tearDown() {
        servers.forEach(p -> {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void generatedServicesCompileAndRpcBridgeWorks() throws Exception {
        // 1. 生成
        AnalysisReport report = JsonIO.read(work.resolve("mono/analysis.json"), AnalysisReport.class);
        PartitionResult partition = JsonIO.read(work.resolve("mono/partition.json"), PartitionResult.class);
        Path out = work.resolve("services");
        new MicroserviceGenerator(report, partition, work.resolve("mono")).generate(out);

        // 产物结构：消费端镜像 Client + 提供端 RpcController + 空 common 只有 pom
        assertTrue(Files.isRegularFile(out.resolve(
                "svc-consumer/src/main/java/com/demo/client/IProviderServiceClientImpl.java")));
        assertTrue(Files.isRegularFile(out.resolve(
                "svc-provider/src/main/java/com/demo/rpc/IProviderServiceRpcController.java")));
        assertTrue(Files.isRegularFile(out.resolve("common/pom.xml")));

        // 2. 编译级：生成工程自身 mvn install（-parameters / 依赖 / 改写正确性的总验证）
        runMvn(List.of("-f", out.toString(), "-q", "install", "-DskipTests"), work);
        assertTrue(Files.isRegularFile(out.resolve(
                "svc-provider/target/classes/com/demo/rpc/IProviderServiceRpcController.class")),
                "生成工程应编译出桥类");

        // 3. 运行级：起 provider + consumer（随机端口），HTTP 断言 RPC 桥贯通
        int providerPort = freePort();
        int consumerPort = freePort();
        servers.add(java("-jar", out.resolve("svc-provider/target/svc-provider-1.0.0.jar").toString(),
                "--server.port=" + providerPort));
        servers.add(java("-jar", out.resolve("svc-consumer/target/svc-consumer-1.0.0.jar").toString(),
                "--server.port=" + consumerPort,
                "--svc.provider.base-url=http://localhost:" + providerPort));

        awaitHealthy(consumerPort, "/api/greet?name=it", 90);
        assertEquals("hello it", httpGet(consumerPort, "/api/greet?name=it"));
        assertEquals("hello monoGomicro", httpGet(consumerPort, "/api/greet?name=monoGomicro"));
        // 无参方法穿透 RPC 桥（空 body 契约：曾 400）
        assertEquals("v1", httpGet(consumerPort, "/api/version"));
    }

    // ---------- fixture ----------

    /** 与 RuoYi 同构的显式 BOM 依赖管理；consumer 构造器注入 provider 接口（跨服务分区）。 */
    private void writeFixture() throws Exception {
        Path mono = work.resolve("mono");
        Path src = mono.resolve("src/main/java/com/demo");
        Files.createDirectories(src);
        Files.writeString(mono.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.demo</groupId><artifactId>demo-mono</artifactId><version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.3.5</version>
                                <type>pom</type><scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(src.resolve("IProviderService.java"), """
                package com.demo;

                public interface IProviderService {
                    String greet(String name);

                    String version(); // 无参方法：锁住 RPC 桥的空 body 契约（空 body 曾 400）
                }
                """);
        Files.writeString(src.resolve("ProviderServiceImpl.java"), """
                package com.demo;

                import org.springframework.stereotype.Service;

                @Service
                public class ProviderServiceImpl implements IProviderService {
                    @Override
                    public String greet(String name) {
                        return "hello " + name;
                    }

                    @Override
                    public String version() {
                        return "v1";
                    }
                }
                """);
        Files.writeString(src.resolve("ConsumerController.java"), """
                package com.demo;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api")
                public class ConsumerController {

                    private final IProviderService providerService;

                    public ConsumerController(IProviderService providerService) {
                        this.providerService = providerService;
                    }

                    @GetMapping("/greet")
                    public String greet(@RequestParam String name) {
                        return providerService.greet(name);
                    }

                    @GetMapping("/version")
                    public String version() {
                        return providerService.version();
                    }
                }
                """);

        // analysis.json：与 scanner 真实产出同构（INHERITANCE 3 + FIELD/METHOD_CALL 3）
        AnalysisReport report = new AnalysisReport();
        report.setProject(mono.toString());
        List<ClassInfo> classes = List.of(
                stereotyped("com.demo.ConsumerController", "mono",
                        "src/main/java/com/demo/ConsumerController.java",
                        com.monogomicro.core.model.ClassStereotype.CONTROLLER),
                stereotyped("com.demo.IProviderService", "mono",
                        "src/main/java/com/demo/IProviderService.java",
                        com.monogomicro.core.model.ClassStereotype.SERVICE),
                stereotyped("com.demo.ProviderServiceImpl", "mono",
                        "src/main/java/com/demo/ProviderServiceImpl.java",
                        com.monogomicro.core.model.ClassStereotype.SERVICE_IMPL));
        report.setClasses(classes);
        report.setEdges(List.of(
                edge("com.demo.ProviderServiceImpl", "com.demo.IProviderService",
                        DependencyEdge.EdgeKind.INHERITANCE),
                edge("com.demo.ConsumerController", "com.demo.IProviderService",
                        DependencyEdge.EdgeKind.FIELD),
                edge("com.demo.ConsumerController", "com.demo.IProviderService",
                        DependencyEdge.EdgeKind.METHOD_CALL)));
        JsonIO.write(report, mono.resolve("analysis.json"));

        // partition.json：consumer 单独服务，provider 接口+实现另一服务；空内核
        PartitionResult partition = new PartitionResult();
        PartitionResult.Community c0 = new PartitionResult.Community();
        c0.setId(0); c0.setName("consumer");
        c0.setClasses(new ArrayList<>(List.of("com.demo.ConsumerController")));
        PartitionResult.Community c1 = new PartitionResult.Community();
        c1.setId(1); c1.setName("provider");
        c1.setClasses(new ArrayList<>(List.of("com.demo.IProviderService", "com.demo.ProviderServiceImpl")));
        partition.setCommunities(List.of(c0, c1));
        JsonIO.write(partition, mono.resolve("partition.json"));
    }

    private DependencyEdge edge(String from, String to, DependencyEdge.EdgeKind kind) {
        DependencyEdge e = new DependencyEdge(from, to);
        e.addOccurrence(kind, "it");
        return e;
    }

    private static ClassInfo stereotyped(String fqcn, String module, String sourcePath,
                                         com.monogomicro.core.model.ClassStereotype stereotype) {
        ClassInfo info = new ClassInfo(fqcn, module, sourcePath);
        info.setStereotype(stereotype);
        return info;
    }

    // ---------- 进程与 HTTP ----------

    private static boolean mvnAvailable() {
        try {
            return new ProcessBuilder("mvn", "-v").redirectErrorStream(true)
                    .start().waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private void runMvn(List<String> args, Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("mvn");
        pb.command().addAll(args);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                r.lines().forEach(out::add);
            } catch (Exception ignore) {
                // 进程结束
            }
        }, "it-mvn-drain");
        drain.setDaemon(true);
        drain.start();
        assertTrue(p.waitFor(4, TimeUnit.MINUTES), "生成工程 install 超时");
        assertEquals(0, p.exitValue(), "生成工程 install 失败（编译级回归）：\n"
                + String.join("\n", out.stream().toList()));
    }

    private Process java(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"));
        pb.command().addAll(List.of(args));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 输出持续消费防管道阻塞，并收集用于失败诊断
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (serverLogs) {
                        serverLogs.append(args.length > 0 ? args[0] : "?").append(" | ")
                                .append(line).append('\n');
                    }
                }
            } catch (Exception ignore) {
                // 进程结束
            }
        }, "it-svc-drain");
        drain.setDaemon(true);
        drain.start();
        return p;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitHealthy(int port, String path, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (httpGet(port, path) != null) {
                    return;
                }
            } catch (Exception ignore) {
                // 未就绪
            }
            Thread.sleep(500);
        }
        throw new AssertionError("服务 " + port + " 在 " + timeoutSeconds + "s 内未就绪。server 日志：\n"
                + tail(serverLogs, 3000));
    }

    private static String httpGet(int port, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://localhost:" + port + path).openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        if (code != 200) {
            return null;
        }
        return new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String tail(StringBuilder sb, int max) {
        synchronized (sb) {
            return sb.substring(Math.max(0, sb.length() - max));
        }
    }
}
