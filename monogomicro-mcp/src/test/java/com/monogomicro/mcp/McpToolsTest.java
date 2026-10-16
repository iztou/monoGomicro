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

    @Test
    void analyzeExposesClasspathStatusAndPersistWhitelist() throws Exception {
        // 迷你单体（不走 mvn classpath，提速）
        Path project = temp.resolve("proj");
        java.nio.file.Files.createDirectories(project.resolve("src/main/java/com/example"));
        java.nio.file.Files.writeString(project.resolve("src/main/java/com/example/A.java"),
                "package com.example;\nclass A {}");

        WorkspaceSession fresh = new WorkspaceSession();
        McpTools freshTools = new McpTools(fresh);
        Map<String, Object> r = freshTools.analyzeProject(project, List.of("com.example"), false, null);
        assertEquals(true, r.containsKey("jarsResolved"));
        assertEquals(false, r.get("classpathDegraded")); // 未启用 maven classpath，不算降级

        // workspace 根 = <project>/m2m-out；落到其外必须拒绝
        M2mException e = assertThrows(M2mException.class, () -> freshTools.analyzeProject(
                project, List.of("com.example"), false, temp.resolve("escape.json")));
        assertEquals("E_OUT_OF_WORKSPACE", e.getCode());

        // 根内落盘放行
        Map<String, Object> ok = freshTools.analyzeProject(project, List.of("com.example"), false,
                project.resolve("m2m-out/analysis.json"));
        assertTrue(String.valueOf(ok.get("analysisPersisted")).endsWith("m2m-out/analysis.json"));
    }
}
