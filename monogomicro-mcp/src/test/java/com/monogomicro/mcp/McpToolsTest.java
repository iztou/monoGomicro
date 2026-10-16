package com.monogomicro.mcp;

import com.monogomicro.core.M2mException;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.report.JsonIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolsTest {

    @TempDir
    Path temp;

    private McpTools tools;
    private WorkspaceSession session;

    @BeforeEach
    void setUp() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setProject("/demo");
        List<ClassInfo> classes = new ArrayList<>();
        for (String fqcn : new String[]{"a.A", "a.B", "b.C", "k.K"}) {
            ClassInfo info = new ClassInfo(fqcn, fqcn.substring(0, 1), fqcn.replace('.', '/') + ".java");
            classes.add(info);
        }
        report.setClasses(classes);
        DependencyEdge e = new DependencyEdge("a.A", "a.B");
        e.setWeight(10);
        report.setEdges(List.of(e));
        JsonIO.write(report, temp.resolve("analysis.json"));

        PartitionResult partition = new PartitionResult();
        partition.setKernelClasses(new ArrayList<>(List.of("k.K")));
        PartitionResult.Community c0 = new PartitionResult.Community();
        c0.setId(0); c0.setName("alpha"); c0.setClasses(new ArrayList<>(List.of("a.A", "a.B")));
        PartitionResult.Community c1 = new PartitionResult.Community();
        c1.setId(1); c1.setName("beta"); c1.setClasses(new ArrayList<>(List.of("b.C")));
        partition.setCommunities(List.of(c0, c1));
        JsonIO.write(partition, temp.resolve("partition.json"));

        session = new WorkspaceSession();
        session.loadWorkspace(temp.resolve("analysis.json"), temp.resolve("partition.json"), null);
        tools = new McpTools(session);
    }

    @Test
    void summaryShape() {
        Map<String, Object> summary = tools.partitionSummary();
        assertEquals("PARTITIONED", summary.get("state"));
        assertEquals(2, ((List<?>) summary.get("services")).size());
        assertEquals(1, summary.get("kernelSize"));
        assertEquals(4, summary.get("totalClasses"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
        assertTrue(metrics.containsKey("crossRatio") && metrics.containsKey("modularity"));
    }

    @Test
    void listClassesPaginationAndScope() {
        Map<String, Object> page1 = tools.listClasses("all", null, 1, 2);
        assertEquals(4, page1.get("total"));
        assertEquals(true, page1.get("truncated"));
        assertEquals(2, ((List<?>) page1.get("classes")).size());

        Map<String, Object> kernel = tools.listClasses("kernel", null, 1, 50);
        assertEquals(1, kernel.get("total"));

        Map<String, Object> svc = tools.listClasses("service:1", null, 1, 50);
        assertEquals(1, svc.get("total"));
        assertEquals("b.C", ((Map<?, ?>) ((List<?>) svc.get("classes")).get(0)).get("fqcn"));

        M2mException bad = assertThrows(M2mException.class, () -> tools.listClasses("service:x", null, 1, 50));
        assertEquals("E_UNKNOWN_SERVICE", bad.getCode());
    }

    @Test
    void findClassesCaseInsensitive() {
        Map<String, Object> r = tools.findClasses("b.c", null); // 小写查询匹配 b.C
        assertEquals(1, ((List<?>) r.get("matches")).size());
        Map<String, Object> r2 = tools.findClasses("a", null);
        assertEquals(2, ((List<?>) r2.get("matches")).size()); // a.A、a.B（k.K 不含 a）
    }

    @Test
    void classInfoUnknownIsStructuredError() {
        M2mException e = assertThrows(M2mException.class, () -> tools.classInfo("no.Such"));
        assertEquals("E_UNKNOWN_CLASS", e.getCode());
    }

    @Test
    void moveReturnsDeltaAndUndoRestores() {
        Map<String, Object> moved = tools.moveClass("a.A", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) moved.get("metricsDelta");
        assertTrue(delta.containsKey("crossRatio") && delta.containsKey("modularity"));
        assertEquals(1, moved.get("undoDepth"));

        Map<String, Object> undone = tools.undo();
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = (Map<String, Object>) undone.get("restored");
        assertEquals("a.A", restored.get("fqcn"));
        assertEquals(0, session.serviceOf().get("a.A"));
    }

    /** 迷你 Maven 单体：sourcePath 与 setUp 的 report（a/A.java 等）对得上，generate 可跑通。 */
    private Path miniMonolith() throws Exception {
        Path project = temp.resolve("mono");
        java.nio.file.Files.createDirectories(project.resolve("a"));
        java.nio.file.Files.createDirectories(project.resolve("b"));
        java.nio.file.Files.createDirectories(project.resolve("k"));
        java.nio.file.Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.demo</groupId><artifactId>demo</artifactId><version>1.0</version>
                </project>
                """);
        java.nio.file.Files.writeString(project.resolve("a/A.java"), "package a;\npublic class A { B b; }");
        java.nio.file.Files.writeString(project.resolve("a/B.java"), "package a;\npublic class B {}");
        java.nio.file.Files.writeString(project.resolve("b/C.java"), "package b;\npublic class C {}");
        java.nio.file.Files.writeString(project.resolve("k/K.java"), "package k;\npublic class K {}");
        return project;
    }

    @Test
    void generationLifecycleDryRunCacheInvalidationAndOverwrite() throws Exception {
        Path project = miniMonolith();
        Path out = temp.resolve("services"); // workspace 根 = temp（analysis.json 所在目录）

        // dry-run：目标目录不产生任何文件
        Map<String, Object> dry = tools.generateServices(project, out, true, false);
        assertEquals(true, dry.get("dryRun"));
        assertTrue((int) dry.get("services") >= 1);
        assertTrue(!java.nio.file.Files.exists(out), "dry-run 不得写盘");

        // 生成缓存生效
        Map<String, Object> items = tools.migrationItems();
        assertEquals(true, items.containsKey("total"));

        // 分区编辑 → 缓存失效
        tools.moveClass("a.A", 1);
        assertEquals("E_NO_GENERATION",
                assertThrows(M2mException.class, tools::migrationItems).getCode());

        // 重新生成（真实落盘）：非空目标 + overwrite=false → 守卫透传
        tools.generateServices(project, out, true, false);
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.writeString(out.resolve("keep.txt"), "x");
        assertEquals("E_OUTPUT_EXISTS",
                assertThrows(M2mException.class,
                        () -> tools.generateServices(project, out, false, false)).getCode());

        // overwrite=true 正式生成，产出工程骨架
        Map<String, Object> real = tools.generateServices(project, out, false, true);
        assertEquals(false, real.get("dryRun"));
        assertTrue(java.nio.file.Files.exists(out.resolve("pom.xml")),
                "聚合 pom 应已生成");

        // 重命名后 undo 能恢复
        tools.renameService(0, "user-core");
        tools.undo();
    }

    @Test
    void metricsShapeAndRenameVisibility() {
        Map<String, Object> m = tools.metrics();
        assertEquals(true, m.containsKey("crossRatio"));
        assertEquals(true, m.containsKey("perService"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> per = (List<Map<String, Object>>) m.get("perService");
        assertEquals(2, per.size());

        tools.renameService(1, "order-service");
        Map<String, Object> summary = tools.partitionSummary();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) summary.get("services");
        assertEquals("order-service", services.get(1).get("name"));
    }

    @Test
    void analyzeExposesClasspathStatusAndPersistWhitelist() throws Exception {
        // 迷你单体（不走 mvn classpath，提速）
        Path project = temp.resolve("proj");
        java.nio.file.Files.createDirectories(project.resolve("src/main/java/com/example"));
        java.nio.file.Files.writeString(project.resolve("src/main/java/com/example/A.java"),
                "package com.example;\nclass A {}");

        WorkspaceSession fresh = new WorkspaceSession();
        McpTools freshTools = new McpTools(fresh);
        Map<String, Object> r = freshTools.analyzeProject(project, List.of("com.example"), false, null, null);
        assertEquals(true, r.containsKey("jarsResolved"));
        assertEquals(false, r.get("classpathDegraded")); // 未启用 maven classpath，不算降级

        // workspace 根 = <project>/m2m-out；落到其外必须拒绝
        M2mException e = assertThrows(M2mException.class, () -> freshTools.analyzeProject(
                project, List.of("com.example"), false, temp.resolve("escape.json"), null));
        assertEquals("E_OUT_OF_WORKSPACE", e.getCode());

        // 根内落盘放行
        Map<String, Object> ok = freshTools.analyzeProject(project, List.of("com.example"), false,
                project.resolve("m2m-out/analysis.json"), null);
        assertTrue(String.valueOf(ok.get("analysisPersisted")).endsWith("m2m-out/analysis.json"));
    }

    @Test
    void analyzeReportsProgressEvery50Files() throws Exception {
        Path project = temp.resolve("bulk");
        Path pkg = project.resolve("src/main/java/com/example");
        java.nio.file.Files.createDirectories(pkg);
        for (int i = 1; i <= 120; i++) {
            java.nio.file.Files.writeString(pkg.resolve("C" + i + ".java"),
                    "package com.example;\nclass C" + i + " {}");
        }
        List<Integer> ticks = new java.util.ArrayList<>();
        new McpTools(new WorkspaceSession()).analyzeProject(
                project, List.of("com.example"), false, null, ticks::add);
        // 节流：每 50 个文件一次，120 个文件应收到 50 与 100（150 未达到）
        assertEquals(List.of(50, 100), ticks);
    }

    @Test
    void unknownClassHintCarriesCandidates() {
        // 设计 §9 承诺：E_UNKNOWN_CLASS 附相近候选（简单名/末段匹配前 5 个）
        M2mException exact = assertThrows(M2mException.class,
                () -> tools.classInfo("a.Aa"));
        assertTrue(exact.getHint().contains("相近候选"), "应带候选：" + exact.getHint());
        assertTrue(exact.getHint().contains("a.A"));

        M2mException svc = assertThrows(M2mException.class,
                () -> tools.moveClass("a.A", 99));
        assertTrue(svc.getHint().contains("0:alpha"), "服务清单应入 hint：" + svc.getHint());
        assertTrue(svc.getHint().contains("1:beta"));
    }
}
