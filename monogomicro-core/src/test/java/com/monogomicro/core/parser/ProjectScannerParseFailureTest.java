package com.monogomicro.core.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-05 回归：语法错误文件不得"成功但静默缺类"。
 *
 * JavaParser 对语法错误常走恢复路径（present CU + problems + 0 顶层类型），
 * 只判 cu==null 会漏掉最常见的形态——parseFailures 必须非空且 WARN 可见。
 * I-05：同一文件"一好一坏"（有顶层类型但 problems 非空）也必须计入。
 * 复用：同一 scanner 实例多次 scan 不得跨轮累积 parseFailures。
 */
class ProjectScannerParseFailureTest {

    @TempDir
    Path temp;

    private Path projectWith(String... files) throws Exception {
        Path project = temp.resolve("proj-" + System.nanoTime());
        Path pkg = project.resolve("src/main/java/p");
        Files.createDirectories(pkg);
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(pkg.resolve(files[i]), files[i + 1]);
        }
        return project;
    }

    @Test
    void recoveredParseErrorIsCountedNotSilentlyDropped() throws Exception {
        Path project = projectWith(
                "A.java", "package p;\npublic class A { public void m() {} }\n",
                "B.java", "package p;\npublic class B { void broken( { } }\n");
        ProjectScanner scanner = new ProjectScanner(List.of("p"), false);
        var graph = scanner.scan(project);

        assertEquals(1, graph.nodeCount(), "只有 A 应注册；B 语法错误被丢弃");
        assertTrue(graph.hasNode("p.A"));
        assertEquals(1, scanner.parseFailures().size(), "恢复型语法错误必须进 parseFailures");
        assertTrue(scanner.parseFailures().get(0).endsWith("B.java"));
    }

    /**
     * I-05：好类型照常注册，"文件里有语法问题"必须可见（problems 非空即计入）。
     * 注：JavaParser 3.26.2 对多数语法错误恢复为 0 类型（走 F-05 分支整文件丢弃），
     * 语句级可恢复错误（如悬空 else）才会保留类型 + problems 并存。
     */
    @Test
    void mixedGoodAndBrokenTypesInOneFileIsVisible() throws Exception {
        Path project = projectWith(
                "C.java", "package p;\npublic class C { int x; void flaky() { if (true) { } else } }\n");
        ProjectScanner scanner = new ProjectScanner(List.of("p"), false);
        var graph = scanner.scan(project);

        assertTrue(graph.hasNode("p.C"), "好类型 C 必须正常注册");
        assertTrue(scanner.parseFailures().stream().anyMatch(f -> f.endsWith("C.java")),
                "带语法问题的文件必须计入 parseFailures；实际：" + scanner.parseFailures());
    }

    /** A-15：全部文件都坏时报 E_PARSE_FAILED，而不是误导性的"包前缀不匹配"。 */
    @Test
    void allFilesBrokenReportsParseFailureCode() throws Exception {
        Path project = projectWith(
                "B.java", "package p;\npublic class B { void broken( { } }\n");
        ProjectScanner scanner = new ProjectScanner(List.of("p"), false);
        com.monogomicro.core.M2mException e = assertThrows(
                com.monogomicro.core.M2mException.class, () -> scanner.scan(project));
        assertEquals("E_PARSE_FAILED", e.getCode(),
                "全部解析失败应归因语法错误而非包前缀；实际：" + e.getCode());
        assertTrue(scanner.parseFailures().size() >= 1);
    }

    /** F-05 复用：实例重复 scan 不跨轮累积（修复前 parseFailures 只在字段初始化清一次）。 */
    @Test
    void parseFailuresDoNotAccumulateAcrossScanRounds() throws Exception {
        Path project = projectWith(
                "A.java", "package p;\npublic class A { public void m() {} }\n",
                "B.java", "package p;\npublic class B { void broken( { } }\n");
        ProjectScanner scanner = new ProjectScanner(List.of("p"), false);
        scanner.scan(project);
        int first = scanner.parseFailures().size();
        scanner.scan(project);
        assertEquals(first, scanner.parseFailures().size(),
                "第二轮 scan 后 parseFailures 数量翻倍 = 跨轮累积：" + scanner.parseFailures());
    }
}
