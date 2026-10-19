package com.monogomicro.core.parser;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.DependencyEdge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-16：扫描器类型位置覆盖——record 组件、cast、instanceof、catch、throws、
 * 局部变量、方法引用都必须产生依赖边（此前只覆盖字段/方法签名/注解/import/
 * 方法调用/new/类字面量，漏边会让共享内核闭包判断失真、生成工程编译失败）。
 * 每个位置一个目标类型，逐位置断言边存在，防止回归时整段失效却无人察觉。
 */
class ProjectScannerTypePositionTest {

    @TempDir
    Path project;

    private void write(Path pkgDir, String name, String content) throws IOException {
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve(name + ".java"), content);
    }

    @Test
    void edgesFromAllTypePositions() throws Exception {
        Path pkgDir = project.resolve("src/main/java/com/demo");
        String pkg = "package com.demo;\n";
        // 目标类型：每个提取位置一个，互不重叠
        for (String t : List.of("RecTarget", "CastTarget", "InstanceOfTarget",
                "CatchTarget", "ThrowsTarget", "LocalTarget", "MethodRefTarget", "TopRecTarget")) {
            if (t.equals("ThrowsTarget") || t.equals("CatchTarget")) {
                write(pkgDir, t, pkg + "public class " + t + " extends Exception {}");
            } else {
                write(pkgDir, t, pkg + "public class " + t + " {}");
            }
        }
        // 顶层 record（另一个形态：decl 自身即 RecordDeclaration）
        write(pkgDir, "TopRecord", pkg + "public record TopRecord(TopRecTarget t) {}\n");
        write(pkgDir, "Holder", pkg + """
                public class Holder {
                    // record 组件
                    public record R(RecTarget rec) {}
                    // throws 声明 + catch 参数 + instanceof + cast + 局部变量 + 方法引用
                    public void m(Object o) throws ThrowsTarget {
                        if (o instanceof InstanceOfTarget) {
                            CastTarget c = (CastTarget) o;
                        }
                        try {
                            throw new ThrowsTarget();
                        } catch (CatchTarget e) {
                            // 吞掉
                        }
                        LocalTarget local = new LocalTarget();
                        Runnable r = MethodRefTarget::new;
                    }
                }
                """);
        // MethodRefTarget 需要可供引用的构造器（默认即有）；new 表达式会带一条边，不影响断言

        DependencyGraph graph = new ProjectScanner(List.of("com.demo"), false).scan(project);
        Set<String> edgeTargets = graph.edges().stream()
                .filter(e -> e.getFrom().equals("com.demo.Holder"))
                .map(DependencyEdge::getTo)
                .collect(Collectors.toSet());

        for (String t : List.of("RecTarget", "CastTarget", "InstanceOfTarget",
                "CatchTarget", "ThrowsTarget", "LocalTarget", "MethodRefTarget")) {
            assertTrue(edgeTargets.contains("com.demo." + t),
                    "缺少 Holder -> " + t + " 的边（对应提取位置失效）；现有边：" + edgeTargets);
        }
        // 顶层 record 形态
        Set<String> topRecordTargets = graph.edges().stream()
                .filter(e -> e.getFrom().equals("com.demo.TopRecord"))
                .map(DependencyEdge::getTo)
                .collect(Collectors.toSet());
        assertTrue(topRecordTargets.contains("com.demo.TopRecTarget"),
                "顶层 record 组件缺少 TopRecord -> TopRecTarget 的边；现有边：" + topRecordTargets);
    }
}
