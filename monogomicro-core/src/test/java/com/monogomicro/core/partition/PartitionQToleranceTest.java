package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.trace.UseCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-09 回归：同一分区的两个 Q 口径必须一致到百分位内。
 *
 * partition.json 的 Q（PartitionEngine 在融合图上计算：权重按 max 归一化 + ×1000 取整）
 * 与 PartitionMetrics 实时 Q（原始权重诱导子图）语义不同：
 * - 纯静态输入：融合是等比缩放，两 Q 只差取整误差（千分位内）；
 * - 含动态轨迹：融合把动态边归一化到与静态同量级，动态占比越大差异越大
 *   （RuoYi 实测 0.002；人为把动态边压到跨簇上可放大到 0.05+）。
 * A-06 修复前差 0.22（kernel 度数进分母不进分子），本测试锁住该量级回归。
 */
class PartitionQToleranceTest {

    private static DependencyGraph threeClusters() {
        DependencyGraph graph = new DependencyGraph();
        String[][] clusters = {
                {"c1.A", "c1.B", "c1.C", "c1.D", "c1.E"},
                {"c2.A", "c2.B", "c2.C", "c2.D", "c2.E"},
                {"c3.A", "c3.B", "c3.C", "c3.D", "c3.E"},
        };
        for (String[] cluster : clusters) {
            for (String fqcn : cluster) {
                graph.addNode(new ClassInfo(fqcn, "demo",
                        "src/main/java/" + fqcn.replace('.', '/') + ".java"));
            }
            for (int i = 0; i + 1 < cluster.length; i++) {
                edge(graph, cluster[i], cluster[i + 1], 3);
                edge(graph, cluster[i + 1], cluster[i], 3);
            }
            for (int i = 1; i < cluster.length; i++) {
                edge(graph, cluster[0], cluster[i], 2);
            }
        }
        edge(graph, "c1.A", "c2.A", 1);
        edge(graph, "c2.B", "c3.B", 1);
        return graph;
    }

    /** 纯静态：融合是等比缩放，两口径只剩整数化误差。 */
    @Test
    void staticOnlyQsDifferOnlyByRounding() {
        DependencyGraph graph = threeClusters();
        PartitionResult result = new PartitionEngine(graph, List.of(), new PartitionOptions()).run();

        double engineQ = result.getModularity();
        double liveQ = PartitionMetrics.of(graph, result).modularity();
        assertTrue(Double.isFinite(engineQ) && engineQ > 0.3,
                "簇结构明显，引擎 Q 应显著为正；实际：" + engineQ);
        assertTrue(Math.abs(engineQ - liveQ) < 0.01,
                "纯静态两口径只剩取整误差；实测差 " + Math.abs(engineQ - liveQ)
                        + "：engine=" + engineQ + " live=" + liveQ);
    }

    /** 动态用例只强化既有簇内边（RuoYi 的典型形态）：差异保持百分位内。 */
    @Test
    void dynamicReinforcingExistingStructureStaysClose() {
        DependencyGraph graph = threeClusters();
        UseCase uc1 = new UseCase("登录");
        uc1.addCall("c1.A", "c1.B");
        uc1.addCall("c1.B", "c1.C");
        UseCase uc2 = new UseCase("报表");
        uc2.addCall("c3.A", "c3.B");
        uc2.addCall("c3.B", "c3.C");

        PartitionResult result = new PartitionEngine(graph, List.of(uc1, uc2), new PartitionOptions()).run();

        double engineQ = result.getModularity();
        double liveQ = PartitionMetrics.of(graph, result).modularity();
        assertTrue(Math.abs(engineQ - liveQ) < 0.01,
                "动态只强化簇内边时差异应保持百分位内；实测差 " + Math.abs(engineQ - liveQ)
                        + "：engine=" + engineQ + " live=" + liveQ);
    }

    private static void edge(DependencyGraph graph, String from, String to, int weight) {
        DependencyEdge e = new DependencyEdge(from, to);
        e.setWeight(weight);
        graph.putEdge(e);
    }
}
