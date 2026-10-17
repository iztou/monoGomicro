package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.trace.UseCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分区指标实时计算（自 web 控制台下沉，MCP / CLI / audit 可复用）。
 *
 * 语义与 web 版保持一致：
 * - 内核类（label = -1）参与的边不计入服务间通信统计；
 * - crossRatio = 跨服务边权重 / 参与统计的边总权重（原始有向图口径）；
 * - modularity 基于对称化图与「仅服务类」的标签计算。
 *
 * 口径提示：{@link PartitionEngine#buildResult} 里的 perCommunity fanIn/fanOut/intraWeight
 * 基于融合对称图且 /2 还原（无向口径），与本类的原始图口径**语义不同**，不可混用。
 */
public final class PartitionMetrics {

    public record CommunityView(int id, String name, int size) {}

    public record Snapshot(double crossRatio, double modularity, int kernelSize,
                           List<CommunityView> communities) {}

    /** 用例跨度统计结果：逐用例跨度 + 平均值（一次遍历产出，避免重复扫描）。 */
    public record UseCaseSpans(List<PartitionResult.UseCaseSpan> spans, double avg) {}

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
        List<CommunityView> views = partition.getCommunities().stream()
                .map(c -> new CommunityView(c.getId(), c.getName(), c.getClasses().size()))
                .toList();
        return of(graph, serviceLabels, partition.getKernelClasses().size(), views);
    }

    /**
     * 通用重载：任意「fqcn → 服务编号」标签（audit 的 ServiceLandscape 编号后同样适用）。
     * labels 中缺项或值为 -1 的类按内核语义剔除；communities 视图由调用方提供。
     */
    public static Snapshot of(DependencyGraph graph, Map<String, Integer> serviceLabels,
                              int kernelSize, List<CommunityView> communities) {
        long total = 0;
        long cross = 0;
        for (DependencyEdge e : graph.edges()) {
            Integer ca = serviceLabels.get(e.getFrom());
            Integer cb = serviceLabels.get(e.getTo());
            if (ca == null || ca < 0 || cb == null || cb < 0) {
                continue; // 内核类/未归属类参与的边不计入服务间通信
            }
            total += e.getWeight();
            if (!ca.equals(cb)) {
                cross += e.getWeight();
            }
        }
        double crossRatio = total == 0 ? 0 : (double) cross / total;
        double modularity = LouvainPartitioner.modularity(graph.symmetrized(), serviceLabels);
        return new Snapshot(crossRatio, modularity, kernelSize, communities);
    }

    /**
     * 服务规模失衡度（σ/mean）。自 buildResult 原样下沉：计算逻辑与遍历顺序
     * （byCommunity 的容器序）保持一致，保证对拍逐位不变。
     */
    public static double imbalance(Map<Integer, List<String>> byCommunity) {
        int k = byCommunity.size();
        int totalClasses = byCommunity.values().stream().mapToInt(List::size).sum();
        double mean = totalClasses / (double) k;
        double variance = 0;
        for (List<String> members : byCommunity.values()) {
            variance += (members.size() - mean) * (members.size() - mean);
        }
        variance /= Math.max(1, k);
        return mean == 0 ? 0 : Math.sqrt(variance) / mean;
    }

    /**
     * 用例跨度：每个用例触达的服务数（labels 缺项/-1 不计）。自 buildResult 原样下沉，
     * 遍历顺序 = useCases 列表序，与原实现逐位一致。
     */
    public static UseCaseSpans useCaseSpans(Map<String, Integer> serviceLabels, List<UseCase> useCases) {
        List<PartitionResult.UseCaseSpan> spans = new ArrayList<>();
        if (useCases.isEmpty()) {
            return new UseCaseSpans(spans, 0);
        }
        double sum = 0;
        int counted = 0;
        for (UseCase uc : useCases) {
            java.util.Set<Integer> touched = new java.util.HashSet<>();
            for (String cls : uc.getClasses()) {
                Integer c = serviceLabels.get(cls);
                if (c != null && c >= 0) {
                    touched.add(c);
                }
            }
            if (touched.isEmpty()) {
                continue;
            }
            PartitionResult.UseCaseSpan span = new PartitionResult.UseCaseSpan();
            span.setName(uc.getName());
            span.setSpan(touched.size());
            span.setCommunities(new ArrayList<>(touched));
            spans.add(span);
            sum += touched.size();
            counted++;
        }
        return new UseCaseSpans(spans, counted == 0 ? 0 : sum / counted);
    }
}
