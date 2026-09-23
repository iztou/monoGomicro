package com.monogomicro.core.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesUseCasesAndEdges() throws Exception {
        String trace = """
                # monoGomicro trace v1
                1\tE\t100\tcom.x.Web\tindex
                1\tN\t101\tcom.x.Web\tindex
                1\tN\t102\tcom.x.Svc\tlist
                1\tN\t103\tcom.x.Repo\tfind
                1\tX\t104\tcom.x.Repo\tfind
                1\tX\t105\tcom.x.Svc\tlist
                1\tX\t106\tcom.x.Web\tindex
                1\tE\t200\tcom.x.Web\tsave
                1\tN\t201\tcom.x.Web\tsave
                1\tN\t202\tcom.x.Svc\tsave
                1\tX\t203\tcom.x.Svc\tsave
                1\tX\t204\tcom.x.Web\tsave
                """;
        Path file = tempDir.resolve("trace.log");
        Files.writeString(file, trace);

        List<UseCase> useCases = new TraceAnalyzer().analyze(file);
        assertEquals(2, useCases.size());

        UseCase first = useCases.get(0);
        assertTrue(first.getName().startsWith("com.x.Web.index#"));
        assertEquals(3, first.getClasses().size());
        assertEquals(2, first.getEdgeWeights().size());
        assertEquals(1L, first.getEdgeWeights().get("com.x.Web->com.x.Svc"));
        assertEquals(1L, first.getEdgeWeights().get("com.x.Svc->com.x.Repo"));

        Map<String, Map<String, Long>> dyn = new TraceAnalyzer().dynamicWeights(useCases);
        assertEquals(2L, dyn.get("com.x.Web").get("com.x.Svc")); // index + save 两次
    }

    @Test
    void interleavedThreadsStayIndependent() throws Exception {
        String trace = """
                1\tE\t100\tcom.x.A\tm
                2\tE\t101\tcom.x.B\tm
                1\tN\t102\tcom.x.A\tm
                2\tN\t103\tcom.x.B\tm
                1\tN\t104\tcom.x.C\tm
                2\tN\t105\tcom.x.D\tm
                1\tX\t106\tcom.x.C\tm
                2\tX\t107\tcom.x.D\tm
                1\tX\t108\tcom.x.A\tm
                2\tX\t109\tcom.x.B\tm
                """;
        Path file = tempDir.resolve("trace2.log");
        Files.writeString(file, trace);
        List<UseCase> useCases = new TraceAnalyzer().analyze(file);
        assertEquals(2, useCases.size());
        assertEquals(1L, useCases.get(0).getEdgeWeights().get("com.x.A->com.x.C"));
        assertEquals(1L, useCases.get(1).getEdgeWeights().get("com.x.B->com.x.D"));
    }
}
