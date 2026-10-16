package com.monogomicro.core.parser;

import com.monogomicro.core.graph.DependencyGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描器必须跳过本工具的生成输出目录 m2m-out：生成过一次后再分析，
 * 生成物（各服务的 Application 类、svc-* 工程）会被当源码扫入，自我污染分析结果。
 */
class ProjectScannerExclusionTest {

    @TempDir
    Path project;

    private void writeClass(Path base, String pkg, String name) throws IOException {
        Path dir = base.resolve("src/main/java/" + pkg.replace('.', '/'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"),
                "package " + pkg + ";\nclass " + name + " {}");
    }

    @Test
    void skipsGeneratedOutputDirectory() throws Exception {
        writeClass(project.resolve("app"), "com.example", "Real");
        writeClass(project.resolve("m2m-out/services/svc-demo"), "com.example", "GeneratedApp");

        DependencyGraph graph = new ProjectScanner(List.of("com.example"), false).scan(project);

        assertTrue(graph.nodes().stream().anyMatch(c -> c.getFqcn().equals("com.example.Real")),
                "正常源码应被扫描");
        assertFalse(graph.nodes().stream().anyMatch(c -> c.getFqcn().contains("Generated")),
                "m2m-out 生成目录不得被扫入分析");
    }
}
