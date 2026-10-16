package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分区指标实时计算（自 web 控制台下沉，MCP / CLI 可复用）。
 *
 * 语义与 web 版保持一致：
 * - 内核类（label = -1）参与的边不计入服务间通信统计；
 * - crossRatio = 跨服务边权重 / 参与统计的边总权重；
 * - modularity 基于对称化图与「仅服务类」的标签计算。
 */
public final class PartitionMetrics {

    public record CommunityView(int id, String name, int size) {}

    public record Snapshot(double crossRatio, double modularity, int kernelSize,
                           List<CommunityView> communities) {}

    private PartitionMetrics() {
    }

    public static Snapshot of(DependencyGraph graph, PartitionResult partition) {
        // 从分区对象推导 fqcn -> 社区标签（内核 -1）；分区被 move/rename 编辑后依然正确
        Map<String, Integer> labels = new LinkedHashMap<>();
        for (String kernel : partition.getKernelClasses()) {
            labels.put(kernel, -1);
        }
        for (PartitionResult.Community c : partition.getCommunities()) {
            for (String fqcn : c.getClasses()) {
                labels.put(fqcn, c.getId());
            }
        }
        Map<String, Integer> serviceLabels = new HashMap<>();
        labels.forEach((fqcn, c) -> {
            if (c >= 0) {
                serviceLabels.put(fqcn, c);
            }
        });

        long total = 0;
        long cross = 0;
        for (DependencyEdge e : graph.edges()) {
            Integer ca = serviceLabels.get(e.getFrom());
            Integer cb = serviceLabels.get(e.getTo());
            if (ca == null || cb == null) {
                continue; // 内核类参与的边不计入服务间通信
            }
            total += e.getWeight();
            if (!ca.equals(cb)) {
                cross += e.getWeight();
            }
        }
        double crossRatio = total == 0 ? 0 : (double) cross / total;
        double modularity = LouvainPartitioner.modularity(graph.symmetrized(), serviceLabels);
        List<CommunityView> views = partition.getCommunities().stream()
                .map(c -> new CommunityView(c.getId(), c.getName(), c.getClasses().size()))
                .toList();
        return new Snapshot(crossRatio, modularity, partition.getKernelClasses().size(), views);
    }
}
