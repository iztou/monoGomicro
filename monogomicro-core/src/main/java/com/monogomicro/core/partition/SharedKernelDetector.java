package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.DependencyEdge;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 共享内核检测：被大量类依赖、且扇入横跨多个社区的工具/基类/DTO
 * （如 RuoYi 的 BaseController、AjaxResult）不应划给任何单个服务，
 * 而应作为共享库。判定依据纯粹看数字（扇入广度），不猜构造型。
 */
public class SharedKernelDetector {

    /**
     * @param graph   依赖图
     * @param labels  第一轮分区结果（节点 -> 社区）
     * @param minCommunities 扇入至少横跨的社区数
     * @param fanInRatio     扇入类数 / 总类数 的最低比例
     * @return 共享内核类集合
     */
    public Set<String> detect(DependencyGraph graph, Map<String, Integer> labels,
                              int minCommunities, double fanInRatio) {
        int total = graph.nodeCount();
        double minFanIn = Math.max(3, (int) Math.ceil(fanInRatio * total));

        Set<String> kernel = new HashSet<>();
        for (var node : graph.nodes()) {
            var inEdges = graph.inEdges(node.getFqcn());
            if (inEdges.size() < minFanIn) {
                continue;
            }
            Set<Integer> sourceCommunities = new HashSet<>();
            for (DependencyEdge e : inEdges) {
                Integer c = labels.get(e.getFrom());
                if (c != null) {
                    sourceCommunities.add(c);
                }
            }
            if (sourceCommunities.size() >= minCommunities) {
                kernel.add(node.getFqcn());
            }
        }

        // 内核不应吞掉应用：超过 30% 类进入内核则收紧门槛重试
        double cap = 0.3 * total;
        while (kernel.size() > cap && (minCommunities < 10 || fanInRatio < 0.6)) {
            if (fanInRatio < 0.6) {
                fanInRatio += 0.05;
            } else {
                minCommunities += 1;
            }
            double finalRatio = fanInRatio;
            int finalMinCommunities = minCommunities;
            double finalMinFanIn = Math.max(3, (int) Math.ceil(finalRatio * total));
            kernel.removeIf(fqcn -> !qualifies(graph, labels, fqcn, finalMinCommunities, finalMinFanIn));
        }
        return kernel;
    }

    private boolean qualifies(DependencyGraph graph, Map<String, Integer> labels, String fqcn,
                              int minCommunities, double minFanIn) {
        var inEdges = graph.inEdges(fqcn);
        if (inEdges.size() < minFanIn) {
            return false;
        }
        Set<Integer> communities = new HashSet<>();
        for (DependencyEdge e : inEdges) {
            Integer c = labels.get(e.getFrom());
            if (c != null) {
                communities.add(c);
            }
        }
        return communities.size() >= minCommunities;
    }
}
