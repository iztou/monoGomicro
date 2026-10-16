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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSessionTest {

    @TempDir
    Path temp;

    private WorkspaceSession session;

    /** a.A/a.B 归服务 0；b.C 归服务 1；k.K 内核。边：A→B(10 内部)、A→C(5 跨)、K→A(100 内核)。 */
    @BeforeEach
    void setUp() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setProject("/demo");
        List<ClassInfo> classes = new ArrayList<>();
        for (String fqcn : new String[]{"a.A", "a.B", "b.C", "k.K"}) {
            classes.add(new ClassInfo(fqcn, fqcn.substring(0, 1), fqcn.replace('.', '/') + ".java"));
        }
        report.setClasses(classes);
        List<DependencyEdge> edges = new ArrayList<>();
        edges.add(edge("a.A", "a.B", 10));
        edges.add(edge("a.A", "b.C", 5));
        edges.add(edge("k.K", "a.A", 100));
        report.setEdges(edges);
        Path analysisFile = temp.resolve("analysis.json");
        JsonIO.write(report, analysisFile);

        PartitionResult partition = new PartitionResult();
        partition.setKernelClasses(new ArrayList<>(List.of("k.K")));
        PartitionResult.Community c0 = new PartitionResult.Community();
        c0.setId(0); c0.setName("alpha"); c0.setClasses(new ArrayList<>(List.of("a.A", "a.B")));
        PartitionResult.Community c1 = new PartitionResult.Community();
        c1.setId(1); c1.setName("beta"); c1.setClasses(new ArrayList<>(List.of("b.C")));
        partition.setCommunities(List.of(c0, c1));
        JsonIO.write(partition, temp.resolve("partition.json"));

        session = new WorkspaceSession();
        session.loadWorkspace(analysisFile, temp.resolve("partition.json"), null);
    }

    private DependencyEdge edge(String from, String to, int weight) {
        DependencyEdge e = new DependencyEdge(from, to);
        e.setWeight(weight);
        return e;
    }

    @Test
    void loadReachesPartitionedState() {
        assertEquals(WorkspaceSession.State.PARTITIONED, session.state());
        assertEquals(4, session.serviceOf().size());
        assertEquals(-1, session.serviceOf().get("k.K"));
        assertEquals(0, session.serviceOf().get("a.A"));
    }

    @Test
    void moveThenUndoRoundtrip() {
        WorkspaceSession.MoveOutcome outcome = session.moveClass("a.A", 1);
        assertEquals(0, outcome.fromService());
        assertEquals(1, outcome.toService());
        assertEquals(1, session.serviceOf().get("a.A"));
        assertEquals(1, session.undoDepth());

        WorkspaceSession.Edit edit = session.undo();
        assertInstanceOf(WorkspaceSession.MoveEdit.class, edit);
        WorkspaceSession.MoveEdit restored = (WorkspaceSession.MoveEdit) edit;
        assertEquals("a.A", restored.fqcn());
        assertEquals(0, restored.fromService());
        assertEquals(0, session.serviceOf().get("a.A"));
        assertEquals(0, session.undoDepth());
    }

    @Test
    void renameThenUndoRoundtrip() {
        String oldName = session.renameService(0, "user-core");
        assertEquals("alpha", oldName);
        assertEquals("user-core", session.partition().getCommunities().get(0).getName());
        assertEquals(1, session.undoDepth());

        WorkspaceSession.Edit edit = session.undo();
        assertInstanceOf(WorkspaceSession.RenameEdit.class, edit);
        assertEquals("alpha", session.partition().getCommunities().get(0).getName());
        assertEquals(0, session.undoDepth());
    }

    @Test
    void renameUnknownServiceIsStructuredError() {
        assertEquals("E_UNKNOWN_SERVICE",
                assertThrows(M2mException.class, () -> session.renameService(99, "x")).getCode());
        assertEquals("E_BAD_PARAMS",
                assertThrows(M2mException.class, () -> session.renameService(0, " ")).getCode());
    }

    @Test
    void ingestTraceParsesUseCasesAndMissingFileErrors() throws Exception {
        Path trace = temp.resolve("trace.log");
        // kind：E=入口（新用例）N=普通调用（记边）X=退出
        StringBuilder sb = new StringBuilder("# monoGomicro trace v1\n");
        sb.append("11\tE\t1000\ta.A\tm1\n");
        sb.append("11\tN\t2000\ta.B\tm2\n");
        sb.append("11\tX\t3000\ta.B\tm2\n");
        sb.append("11\tX\t4000\ta.A\tm1\n");
        java.nio.file.Files.writeString(trace, sb.toString());

        List<com.monogomicro.core.trace.UseCase> useCases = session.ingestTrace(trace);
        assertTrue(useCases.size() >= 1, "至少切出一个用例");
        // E 开用例不压栈，第一个 N 的 caller 为 null——classes 收集的是被调方
        assertTrue(useCases.get(0).getClasses().contains("a.B"));

        assertEquals("E_PROJECT_NOT_FOUND",
                assertThrows(M2mException.class, () -> session.ingestTrace(temp.resolve("nope.log"))).getCode());
    }

    @Test
    void savePartitionWhitelistAndDefault() throws Exception {
        Path saved = session.savePartition(null);
        assertEquals(temp.resolve("partition.json"), saved);
        assertTrue(java.nio.file.Files.exists(saved));

        assertEquals("E_OUT_OF_WORKSPACE",
                assertThrows(M2mException.class,
                        () -> session.savePartition(temp.resolve("../escape.json"))).getCode());
    }

    @Test
    void generateRequiresProjectDirOnLoadSession() {
        // load 会话未提供 projectDir：generate 必须明确报缺参
        M2mException e = assertThrows(M2mException.class,
                () -> session.generateServices(null, null, true, false));
        assertEquals("E_BAD_PARAMS", e.getCode());
    }

    @Test
    void generateOutDirConfinedToWorkspace() {
        assertEquals("E_OUT_OF_WORKSPACE",
                assertThrows(M2mException.class, () -> session.generateServices(
                        temp, temp.resolve("../outside"), true, false)).getCode());
    }

    @Test
    void moveIntoKernelAndBack() {
        session.moveClass("a.A", -1);
        assertEquals(-1, session.serviceOf().get("a.A"));
        assertEquals(List.of("k.K", "a.A"), session.partition().getKernelClasses());
        assertEquals(List.of("a.B"), session.partition().getCommunities().get(0).getClasses());

        session.undo();
        assertEquals(0, session.serviceOf().get("a.A"));
        assertEquals(List.of("k.K"), session.partition().getKernelClasses());
    }

    @Test
    void emptyUndoStackIsStructuredError() {
        M2mException e = assertThrows(M2mException.class, session::undo);
        assertEquals("E_NOTHING_TO_UNDO", e.getCode());
    }

    @Test
    void noOpMoveDoesNotPushUndoOrDuplicateKernel() {
        // 内核类 -1→-1 是 no-op：不得压 undo 栈，否则 undo 时内核列表出现重复项
        WorkspaceSession.MoveOutcome noop = session.moveClass("k.K", -1);
        assertEquals(-1, noop.fromService());
        assertEquals(0, session.undoDepth());
        assertEquals(List.of("k.K"), session.partition().getKernelClasses());

        // 服务内移动同理
        session.moveClass("a.A", 0);
        assertEquals(0, session.undoDepth());
    }

    @Test
    void unknownClassAndServiceAreStructuredErrors() {
        assertEquals("E_UNKNOWN_CLASS",
                assertThrows(M2mException.class, () -> session.moveClass("no.Such", 0)).getCode());
        assertEquals("E_UNKNOWN_SERVICE",
                assertThrows(M2mException.class, () -> session.moveClass("a.A", 99)).getCode());
    }

    @Test
    void partitionOpsBeforeAnalysisAreStructuredErrors() throws Exception {
        WorkspaceSession empty = new WorkspaceSession();
        assertEquals("E_NO_ANALYSIS",
                assertThrows(M2mException.class, () -> empty.computePartition(0.5, 0.5, 5, 12, 42)).getCode());

        // analyze 后未 compute：move 报 E_NO_PARTITION（load 不带 partition 会自动分区，不走这条路径）
        Path project = temp.resolve("proj");
        java.nio.file.Files.createDirectories(project.resolve("src/main/java/com/example"));
        java.nio.file.Files.writeString(project.resolve("src/main/java/com/example/A.java"),
                "package com.example;\nclass A {}");
        WorkspaceSession analyzed = new WorkspaceSession();
        analyzed.analyze(project, List.of("com.example"), false);
        assertEquals(WorkspaceSession.State.ANALYZED, analyzed.state());
        assertEquals("E_NO_PARTITION",
                assertThrows(M2mException.class, () -> analyzed.moveClass("com.example.A", 1)).getCode());
    }

    @Test
    void missingAnalysisFileIsStructuredError() {
        M2mException e = assertThrows(M2mException.class,
                () -> session.loadWorkspace(temp.resolve("nope.json"), null, null));
        assertEquals("E_PROJECT_NOT_FOUND", e.getCode());
    }
}
