package com.monogomicro.codegen;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.ClassStereotype;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-01/F-02 回归：provider 接口被内核闭包提升进 common 后，RPC 桥必须路由到
 * 实现类（SERVICE_IMPL）所在服务——而不是把 controller 写进 common、让 client
 * 引用不存在的 ${svc.common.base-url}。
 * 扫描器的 ClassInfo.interfaces 是纯简单名，matchInterface 曾因此恒不匹配（死代码）。
 */
class BridgeRerouteTest {

    @TempDir
    Path project;

    private void writeSource(String fqcn, String content) throws Exception {
        Path file = project.resolve("src/main/java/" + fqcn.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void bridgeReroutesToImplOwnerWhenInterfacePromotedToCommon() throws Exception {
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.demo</groupId><artifactId>t</artifactId><version>1</version>
                </project>""");
        writeSource("com.demo.kernel.KernelUser", """
                package com.demo.kernel;
                import com.demo.api.ISysX;
                public class KernelUser { private ISysX x; }
                """);
        writeSource("com.demo.api.ISysX", """
                package com.demo.api;
                public interface ISysX { String greet(String name); }
                """);
        writeSource("com.demo.impl.XImpl", """
                package com.demo.impl;
                import com.demo.api.ISysX;
                public class XImpl implements ISysX {
                    public String greet(String name) { return "hi " + name; }
                }
                """);
        writeSource("com.demo.web.Consumer", """
                package com.demo.web;
                import com.demo.api.ISysX;
                public class Consumer { private ISysX x; }
                """);

        AnalysisReport report = new AnalysisReport();
        report.setProject(project.toString());
        report.setClasses(List.of(
                cls("com.demo.kernel.KernelUser", ClassStereotype.OTHER, null),
                cls("com.demo.api.ISysX", ClassStereotype.SERVICE, null),
                cls("com.demo.impl.XImpl", ClassStereotype.SERVICE_IMPL, List.of("ISysX")),
                cls("com.demo.web.Consumer", ClassStereotype.CONTROLLER, null)));
        report.setEdges(List.of(
                edge("com.demo.kernel.KernelUser", "com.demo.api.ISysX"),   // 内核类引用接口 → 闭包把接口拉进 common
                edge("com.demo.impl.XImpl", "com.demo.api.ISysX"),          // 实现关系
                edge("com.demo.web.Consumer", "com.demo.api.ISysX")));      // 跨服务注入

        PartitionResult partition = new PartitionResult();
        partition.setKernelClasses(new ArrayList<>(List.of("com.demo.kernel.KernelUser")));
        PartitionResult.Community consumer = new PartitionResult.Community();
        consumer.setId(0);
        consumer.setName("consumer");
        consumer.setClasses(new ArrayList<>(List.of("com.demo.web.Consumer")));
        PartitionResult.Community provider = new PartitionResult.Community();
        provider.setId(1);
        provider.setName("provider");
        provider.setClasses(new ArrayList<>(List.of("com.demo.api.ISysX", "com.demo.impl.XImpl")));
        partition.setCommunities(List.of(consumer, provider));

        Path out = project.getParent().resolve("out-reroute");
        GenerationResult result = new MicroserviceGenerator(report, partition, project).generate(out);

        // 桥必须路由到实现类所在的 svc-provider，controller 不得落在 common
        assertTrue(result.getGeneratedBridges().stream()
                        .anyMatch(b -> b.startsWith("svc-consumer -> svc-provider")),
                "桥应路由到 impl 属主服务；实际：" + result.getGeneratedBridges());
        assertTrue(Files.isRegularFile(out.resolve(
                "svc-provider/src/main/java/com/demo/rpc/ISysXRpcController.java")),
                "RpcController 应生成在 impl 所在服务");
        assertFalse(Files.exists(out.resolve(
                "common/src/main/java/com/demo/rpc")),
                "common 里不得出现 RpcController（svc.common.base-url 无定义，启动即炸）");
        String client = Files.readString(out.resolve(
                "common/src/main/java/com/demo/client/ISysXClientImpl.java"));
        assertTrue(client.contains("${svc.provider.base-url}"),
                "client 应引用 impl 属主的 base-url key");
        assertFalse(client.contains("svc.common"), "client 不得引用 svc.common.*");
    }

    /**
     * I-04 回归：接口被内核闭包提升进 common 后，若实现类（reroute 目标）恰好在
     * 消费者自己的服务里，reroute 后两端同属一个服务——必须跳过桥改写（保持本地注入），
     * 否则生成"HTTP 打自己"的自桥。修复前 reroute 后没有重做同属判定。
     */
    @Test
    void rerouteToImplInConsumerServiceSkipsSelfBridge() throws Exception {
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.demo</groupId><artifactId>t</artifactId><version>1</version>
                </project>""");
        writeSource("com.demo.kernel.KernelUser", """
                package com.demo.kernel;
                import com.demo.api.ISysY;
                public class KernelUser { private ISysY y; }
                """);
        writeSource("com.demo.api.ISysY", """
                package com.demo.api;
                public interface ISysY { String hello(); }
                """);
        // impl 与消费者在同一个社区：reroute 目标 == 消费者服务
        writeSource("com.demo.impl.YImpl", """
                package com.demo.impl;
                import com.demo.api.ISysY;
                public class YImpl implements ISysY {
                    public String hello() { return "hi"; }
                }
                """);
        writeSource("com.demo.web.Consumer", """
                package com.demo.web;
                import com.demo.api.ISysY;
                public class Consumer { private ISysY y; }
                """);
        writeSource("com.demo.other.Other", """
                package com.demo.other;
                public class Other { public void run() {} }
                """);

        AnalysisReport report = new AnalysisReport();
        report.setProject(project.toString());
        report.setClasses(List.of(
                cls("com.demo.kernel.KernelUser", ClassStereotype.OTHER, null),
                cls("com.demo.api.ISysY", ClassStereotype.SERVICE, null),
                cls("com.demo.impl.YImpl", ClassStereotype.SERVICE_IMPL, List.of("ISysY")),
                cls("com.demo.web.Consumer", ClassStereotype.CONTROLLER, null),
                cls("com.demo.other.Other", ClassStereotype.OTHER, null)));
        report.setEdges(List.of(
                edge("com.demo.kernel.KernelUser", "com.demo.api.ISysY"),   // 闭包把接口拉进 common
                edge("com.demo.impl.YImpl", "com.demo.api.ISysY"),
                edge("com.demo.web.Consumer", "com.demo.api.ISysY")));      // 提升前像跨服务注入

        PartitionResult partition = new PartitionResult();
        partition.setKernelClasses(new ArrayList<>(List.of("com.demo.kernel.KernelUser")));
        PartitionResult.Community consumer = new PartitionResult.Community();
        consumer.setId(0);
        consumer.setName("consumer");
        consumer.setClasses(new ArrayList<>(List.of("com.demo.web.Consumer", "com.demo.impl.YImpl")));
        PartitionResult.Community other = new PartitionResult.Community();
        other.setId(1);
        other.setName("other");
        // 接口初始属 other 社区：快照里 Consumer->ISysY 是跨服务注入；
        // 随后内核闭包把它提升进 common，reroute 目标（impl 属主）恰是消费者自己的服务
        other.setClasses(new ArrayList<>(List.of("com.demo.other.Other", "com.demo.api.ISysY")));
        partition.setCommunities(List.of(consumer, other));

        Path out = project.getParent().resolve("out-selfbridge");
        GenerationResult result = new MicroserviceGenerator(report, partition, project).generate(out);

        assertTrue(result.getGeneratedBridges().stream()
                        .noneMatch(b -> b.startsWith("svc-consumer -> svc-consumer")),
                "reroute 后同属一个服务，不得生成自桥；实际：" + result.getGeneratedBridges());
        // 本地注入保持原样：字段类型仍是接口（接口已随闭包进 common，编译可过）
        Path consumerSrc = out.resolve(
                "svc-consumer/src/main/java/com/demo/web/Consumer.java");
        String src = Files.readString(consumerSrc);
        assertTrue(src.contains("private ISysY y;"), "本地注入不应被改写为 HTTP client");
    }

    private static ClassInfo cls(String fqcn, ClassStereotype stereotype, List<String> interfaces) {
        ClassInfo info = new ClassInfo(fqcn, "demo",
                "src/main/java/" + fqcn.replace('.', '/') + ".java");
        info.setStereotype(stereotype);
        if (interfaces != null) {
            info.setInterfaces(interfaces);
        }
        return info;
    }

    private static DependencyEdge edge(String from, String to) {
        DependencyEdge e = new DependencyEdge(from, to);
        e.addOccurrence(DependencyEdge.EdgeKind.FIELD, "test");
        return e;
    }
}
