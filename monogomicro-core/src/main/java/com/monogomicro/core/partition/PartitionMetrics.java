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
 *
 * F-09 口径说明：本类 modularity（诱导子图、原始权重）与 partition.json 里存的 Q
 * （PartitionEngine 在融合图上计算：权重按 max 归一化 + ×1000 取整）语义不同——
 * 纯静态输入时融合是等比缩放，两 Q 只差取整误差（千分位级）；含动态轨迹时融合把
 * 动态边归一化到与静态同量级，动态占比越大差异越大（RuoYi 实测 0.002，
 * 人为把动态边压到跨簇上可放大一个数量级）。两口径各自自洽，断言传等值；
 * 差异量级契约由 PartitionQToleranceTest 锁定（回归见 A-06：修复前系统性偏差 0.22）。
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
        // 模度只计服务类：-1（kernel/shared）若留在 labels 会被 modularity 视为同一幻影社区，
        // shared 间的边计入社区内聚而抬高 Q——kernel 语义 = 留在图中但不进标签
        Map<String, Integer> serviceOnly = new HashMap<>();
        serviceLabels.forEach((fqcn, c) -> {
            if (c >= 0) {
                serviceOnly.put(fqcn, c);
            }
        });
        // A-06：模度必须在"已标注节点的诱导子图"上计算——此前把全图 symmetrized 传入，
        // kernel 节点的度数计入 m2/degree 分母却不进分子，Q 被系统性压低
        // （同一分区 partition.json 0.77 vs 本口径 0.55）
        double modularity = LouvainPartitioner.modularity(inducedSubgraph(graph, serviceOnly), serviceOnly);
        return new Snapshot(crossRatio, modularity, kernelSize, communities);
    }

    /** 仅保留 labels 中已标注节点的对称化诱导子图（边两端都必须在 labels 中）。 */
    private static Map<String, Map<String, Long>> inducedSubgraph(DependencyGraph graph,
                                                                  Map<String, Integer> labels) {
        Map<String, Map<String, Long>> sym = new LinkedHashMap<>();
        for (String n : labels.keySet()) {
            sym.put(n, new LinkedHashMap<>());
        }
        graph.forEach(e -> {
            if (sym.containsKey(e.getFrom()) && sym.containsKey(e.getTo())) {
                long w = e.getWeight();
                sym.get(e.getFrom()).merge(e.getTo(), w, Long::sum);
                sym.get(e.getTo()).merge(e.getFrom(), w, Long::sum);
            }
        });
        return sym;
    }

    /**
     * 服务规模失衡度（σ/mean）。自 buildResult 原样下沉：计算逻辑与遍历顺序
     * （byCommunity 的容器序）保持一致，保证对拍逐位不变。
     */
    public static double imbalance(Map<Integer, List<String>> byCommunity) {
        int k = byCommunity.size();
        if (k == 0) {
            return 0; // 无服务时规模失衡无意义；返回 0 保证 JSON 契约是 number（NaN 会被写成字符串 "NaN"）
        }
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
