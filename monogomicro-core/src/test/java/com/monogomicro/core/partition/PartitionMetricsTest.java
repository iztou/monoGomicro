package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartitionMetricsTest {

    /** A、B 属服务 0；C、D 属服务 1；K 是共享内核。
     *  边：A→B(10, 服务内)、A→C(5, 跨服务)、K→A(100, 内核参与不计入)。 */
    private DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph();
        for (String n : new String[]{"a.A", "a.B", "b.C", "b.D", "k.K"}) {
            g.addNode(new ClassInfo(n, "mod", n + ".java"));
        }
        g.putEdge(edge("a.A", "a.B", 10));
        g.putEdge(edge("a.A", "b.C", 5));
        g.putEdge(edge("k.K", "a.A", 100));
        return g;
    }

    private DependencyEdge edge(String from, String to, int weight) {
        DependencyEdge e = new DependencyEdge(from, to);
        e.setWeight(weight);
        return e;
    }

    private PartitionResult partition() {
        PartitionResult p = new PartitionResult();
        p.setKernelClasses(List.of("k.K"));
        PartitionResult.Community c0 = new PartitionResult.Community();
        c0.setId(0);
        c0.setName("user");
        c0.setClasses(new java.util.ArrayList<>(List.of("a.A", "a.B")));
        PartitionResult.Community c1 = new PartitionResult.Community();
        c1.setId(1);
        c1.setName("order");
        c1.setClasses(new java.util.ArrayList<>(List.of("b.C", "b.D")));
        p.setCommunities(List.of(c0, c1));
        return p;
    }

    @Test
    void crossRatioExcludesKernelEdges() {
        PartitionMetrics.Snapshot s = PartitionMetrics.of(graph(), partition());
        // 参与统计的边：A→B(10) + A→C(5) = 15，跨服务 5；K→A(100) 被内核规则排除
        assertEquals(5.0 / 15.0, s.crossRatio(), 1e-9);
        assertEquals(1, s.kernelSize());
        assertEquals(2, s.communities().size());
        assertEquals(new PartitionMetrics.CommunityView(0, "user", 2), s.communities().get(0));
        assertEquals(new PartitionMetrics.CommunityView(1, "order", 2), s.communities().get(1));
    }

    @Test
    void modularityMatchesDirectComputation() {
        // 接线校验：内部标签推导（内核排除 + 社区归属）与直接调用 Louvain 静态方法一致
        DependencyGraph g = graph();
        PartitionMetrics.Snapshot s = PartitionMetrics.of(g, partition());
        Map<String, Integer> serviceLabels = Map.of("a.A", 0, "a.B", 0, "b.C", 1, "b.D", 1);
        assertEquals(LouvainPartitioner.modularity(g.symmetrized(), serviceLabels),
                s.modularity(), 1e-12);
    }

    @Test
    void metricsFollowPartitionEdits() {
        // 指标从分区对象实时推导：把 C 移进服务 0 后不再有跨服务边
        PartitionResult p = partition();
        p.getCommunities().get(0).getClasses().add("b.C");
        p.getCommunities().get(1).getClasses().remove("b.C");
        PartitionMetrics.Snapshot s = PartitionMetrics.of(graph(), p);
        assertEquals(0.0, s.crossRatio(), 1e-9);
        assertEquals(3, s.communities().get(0).size());
    }
}
