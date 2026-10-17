package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.DependencyEdge.EdgeKind;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.trace.UseCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阶段 2 对拍：labels 通用重载与旧路径（PartitionResult 入参）必须逐位一致；
 * 下沉的 imbalance/useCaseSpans 与历史口径一致。
 */
class PartitionMetricsOverloadTest {

    private static DependencyGraph graphOf(int classes, String... edges) {
        DependencyGraph g = new DependencyGraph();
        for (int i = 0; i < classes; i++) {
            g.addNode(new ClassInfo("com.x.C" + i, "m", "src/C" + i + ".java"));
        }
        for (int i = 0; i + 1 < edges.length; i += 2) {
            g.addOccurrence(edges[i], edges[i + 1], DependencyEdge.EdgeKind.METHOD_CALL, "test");
        }
        return g;
    }

    @Test
    void labelsOverloadMatchesPartitionPathExactly() {
        DependencyGraph g = graphOf(6,
                "com.x.C0", "com.x.C1",
                "com.x.C0", "com.x.C2",
                "com.x.C1", "com.x.C3",
                "com.x.C2", "com.x.C3",
                "com.x.C4", "com.x.C0",
                "com.x.C5", "com.x.C4");
        PartitionResult p = new PartitionResult();
        p.setKernelClasses(List.of("com.x.C5"));
        PartitionResult.Community a = new PartitionResult.Community();
        a.setId(0);
        a.setName("a");
        a.setClasses(List.of("com.x.C0", "com.x.C1", "com.x.C4"));
        PartitionResult.Community b = new PartitionResult.Community();
        b.setId(1);
        b.setName("b");
        b.setClasses(List.of("com.x.C2", "com.x.C3"));
        p.setCommunities(List.of(a, b));

        PartitionMetrics.Snapshot viaPartition = PartitionMetrics.of(g, p);

        // labels 含 -1（kernel）——重载须按内核语义剔除，结果与旧路径一致
        Map<String, Integer> labels = Map.of(
                "com.x.C0", 0, "com.x.C1", 0, "com.x.C4", 0,
                "com.x.C2", 1, "com.x.C3", 1,
                "com.x.C5", -1);
        PartitionMetrics.Snapshot viaLabels = PartitionMetrics.of(g, labels, 1, List.of(
                new PartitionMetrics.CommunityView(0, "a", 3),
                new PartitionMetrics.CommunityView(1, "b", 2)));

        assertEquals(viaPartition.crossRatio(), viaLabels.crossRatio(), 0.0, "crossRatio 须逐位一致");
        assertEquals(viaPartition.modularity(), viaLabels.modularity(), 0.0, "modularity 须逐位一致");
        assertEquals(viaPartition.kernelSize(), viaLabels.kernelSize());
    }

    @Test
    void imbalanceMatchesHistoricFormula() {
        // 与 buildResult 原内联公式对拍：σ/mean
        Map<Integer, List<String>> byCommunity = Map.of(
                0, List.of("a", "b", "c", "c2", "c3", "c4", "c5", "c6"),  // 8
                1, List.of("d", "e"),                                      // 2
                2, List.of("f"));                                           // 1
        int k = 3;
        double mean = 11 / (double) k;
        double variance = ((8 - mean) * (8 - mean) + (2 - mean) * (2 - mean) + (1 - mean) * (1 - mean)) / k;
        double expected = Math.sqrt(variance) / mean;
        assertEquals(expected, PartitionMetrics.imbalance(byCommunity), 0.0);
    }

    @Test
    void useCaseSpansCountServiceTouchpointsOnly() {
        UseCase uc1 = new UseCase("browse");
        uc1.getClasses().add("com.x.C0");
        uc1.getClasses().add("com.x.C2");
        uc1.getClasses().add("com.x.C5"); // kernel(-1) 不计
        UseCase uc2 = new UseCase("noop");
        uc2.getClasses().add("com.x.C9"); // 未归属类不计 → 空 touchpoint 跳过
        PartitionMetrics.UseCaseSpans r = PartitionMetrics.useCaseSpans(
                Map.of("com.x.C0", 0, "com.x.C2", 1, "com.x.C5", -1),
                List.of(uc1, uc2));
        assertEquals(1, r.spans().size());
        assertEquals(2, r.spans().get(0).getSpan());
        assertEquals(2.0, r.avg(), 0.0);
    }
}
