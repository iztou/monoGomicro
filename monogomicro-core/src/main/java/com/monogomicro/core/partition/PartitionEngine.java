package com.monogomicro.core.partition;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.ClassStereotype;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.trace.TraceAnalyzer;
import com.monogomicro.core.trace.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 分区引擎编排：
 * 融合静态+动态图 -> 第一轮 Louvain -> 共享内核检测并剥离 ->
 * 第二轮 Louvain -> 小社区合并 -> 退火精化 -> 命名与指标。
 */
public class PartitionEngine {

    private static final Logger log = LoggerFactory.getLogger(PartitionEngine.class);

    private static final Set<String> NOISE_TOKENS = Set.of(
            "sys", "gen", "ruoyi", "ruo", "yi", "wx", "app", "web", "controller", "service", "impl",
            "mapper", "domain", "entity", "util", "utils", "manager", "info", "base", "i");

    private final DependencyGraph graph;
    private final List<UseCase> useCases;
    private final PartitionOptions options;

    public PartitionEngine(DependencyGraph graph, List<UseCase> useCases, PartitionOptions options) {
        this.graph = graph;
        this.useCases = useCases == null ? List.of() : useCases;
        this.options = options;
    }

    public PartitionResult run() {
        Map<String, Map<String, Long>> staticSym = graph.symmetrized();
        Map<String, Map<String, Long>> dynamicSym = new TraceAnalyzer().dynamicWeights(useCases);
        Map<String, Map<String, Long>> fused = fuse(staticSym, dynamicSym);

        // 第一轮：全图分区，只为识别共享内核
        Map<String, Integer> round1 = new LouvainPartitioner(options.getSeed()).partition(fused);
        Set<String> kernel = new SharedKernelDetector().detect(graph, round1,
                options.getKernelMinCommunities(), options.getKernelFanInRatio());
        log.info("共享内核识别：{} 个类（如 BaseController/AjaxResult 类角色）", kernel.size());

        // 剥离内核后的服务图
        Map<String, Map<String, Long>> serviceSym = strip(fused, kernel);

        // 第二轮：服务分区 + 精化
        Map<String, Integer> labels = new LouvainPartitioner(options.getSeed() + 1).partition(serviceSym);
        mergeSmallCommunities(labels, serviceSym);
        AnnealingRefiner.Options annealOpts = new AnnealingRefiner.Options();
        annealOpts.minCommunitySize = options.getMinCommunitySize();
        annealOpts.seed = options.getSeed();
        labels = new AnnealingRefiner(serviceSym, labels, useCasesExcludingKernel(kernel), annealOpts).refine();
        // 社区级合并：退火只做单点移动，碎片社区需要整体归并
        mergeToTargetCount(labels, serviceSym, options.getMaxServices());
        attachIsolated(labels, serviceSym);
        labels = renumber(labels);

        return buildResult(labels, serviceSym, kernel);
    }

    /** 归一化融合：fused = α·static/maxStatic + β·dynamic/maxDynamic（按边并集）。
     *  归一化后放大 1000 倍再取整——直接转 long 会把小权重边截断成 0，导致全图失联。 */
    private static final double SCALE = 1000.0;

    private Map<String, Map<String, Long>> fuse(Map<String, Map<String, Long>> staticSym,
                                                Map<String, Map<String, Long>> dynamicSym) {
        long maxStatic = maxWeight(staticSym);
        long maxDynamic = maxWeight(dynamicSym);
        Map<String, Map<String, Long>> fused = new LinkedHashMap<>();
        Set<String> nodes = new HashSet<>(staticSym.keySet());
        nodes.addAll(dynamicSym.keySet());
        for (String node : nodes) {
            fused.put(node, new LinkedHashMap<>());
        }
        if (maxStatic == 0 && maxDynamic == 0) {
            return fused;
        }
        for (Map.Entry<String, Map<String, Long>> e : staticSym.entrySet()) {
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                fused.get(e.getKey()).merge(f.getKey(),
                        (long) (SCALE * options.getAlpha() * f.getValue() / maxStatic), Long::sum);
            }
        }
        for (Map.Entry<String, Map<String, Long>> e : dynamicSym.entrySet()) {
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                fused.get(e.getKey()).merge(f.getKey(),
                        (long) (SCALE * options.getBeta() * f.getValue() / maxDynamic), Long::sum);
            }
        }
        return fused;
    }

    private long maxWeight(Map<String, Map<String, Long>> sym) {
        long max = 0;
        for (Map<String, Long> adj : sym.values()) {
            for (Long w : adj.values()) {
                max = Math.max(max, w);
            }
        }
        return max;
    }

    private Map<String, Map<String, Long>> strip(Map<String, Map<String, Long>> sym, Set<String> remove) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : sym.entrySet()) {
            if (remove.contains(e.getKey())) {
                continue;
            }
            Map<String, Long> adj = new LinkedHashMap<>();
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                if (!remove.contains(f.getKey())) {
                    adj.put(f.getKey(), f.getValue());
                }
            }
            result.put(e.getKey(), adj);
        }
        return result;
    }

    private List<UseCase> useCasesExcludingKernel(Set<String> kernel) {
        List<UseCase> filtered = new ArrayList<>();
        for (UseCase uc : useCases) {
            Set<String> classes = new HashSet<>(uc.getClasses());
            classes.removeAll(kernel);
            if (!classes.isEmpty()) {
                UseCase copy = new UseCase(uc.getName());
                copy.getClasses().addAll(classes);
                copy.getEdgeWeights().putAll(uc.getEdgeWeights());
                filtered.add(copy);
            }
        }
        return filtered;
    }

    /** 规模低于阈值的社区整体并入连接最紧的邻居社区 */
    private void mergeSmallCommunities(Map<String, Integer> labels, Map<String, Map<String, Long>> sym) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Integer, List<String>> byCommunity = groupBy(labels);
            for (Map.Entry<Integer, List<String>> e : byCommunity.entrySet()) {
                if (e.getValue().size() >= options.getMinCommunitySize()) {
                    continue;
                }
                Map<Integer, Long> connection = new HashMap<>();
                for (String member : e.getValue()) {
                    for (Map.Entry<String, Long> f : sym.getOrDefault(member, Map.of()).entrySet()) {
                        Integer other = labels.get(f.getKey());
                        if (other != null && other.intValue() != e.getKey()) {
                            connection.merge(other, f.getValue(), Long::sum);
                        }
                    }
                }
                if (connection.isEmpty()) {
                    continue; // 孤立小社区无处可并，保留
                }
                int best = connection.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(e.getKey());
                for (String member : e.getValue()) {
                    labels.put(member, best);
                }
                changed = true;
                break; // 重新分组后再继续
            }
        }
    }

    private Map<Integer, List<String>> groupBy(Map<String, Integer> labels) {
        Map<Integer, List<String>> by = new HashMap<>();
        labels.forEach((node, c) -> by.computeIfAbsent(c, k -> new ArrayList<>()).add(node));
        return by;
    }

    /** 社区数超过上限时，把最小社区整体并入连接权重最大的邻居社区 */
    private void mergeToTargetCount(Map<String, Integer> labels, Map<String, Map<String, Long>> sym, int max) {
        while (true) {
            Map<Integer, List<String>> by = groupBy(labels);
            if (by.size() <= max) {
                return;
            }
            Integer smallest = by.entrySet().stream()
                    .min(java.util.Comparator.<Map.Entry<Integer, List<String>>>comparingInt(e -> e.getValue().size())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse(null);
            if (smallest == null) {
                return;
            }
            Map<Integer, Long> connection = new HashMap<>();
            for (String member : by.get(smallest)) {
                for (Map.Entry<String, Long> f : sym.getOrDefault(member, Map.of()).entrySet()) {
                    Integer other = labels.get(f.getKey());
                    if (other != null && other.intValue() != smallest) {
                        connection.merge(other, f.getValue(), Long::sum);
                    }
                }
            }
            int target;
            if (connection.isEmpty()) {
                // 孤立社区：并入最大的社区，避免死循环
                target = by.entrySet().stream()
                        .max(java.util.Comparator.comparingInt(e -> e.getValue().size()))
                        .map(Map.Entry::getKey).orElse(smallest);
                if (target == smallest) {
                    return;
                }
            } else {
                target = connection.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(smallest);
            }
            for (String member : by.get(smallest)) {
                labels.put(member, target);
            }
        }
    }

    /** 剥离内核后变成孤岛的类，挂到原静态图中最强邻居所在的社区 */
    private void attachIsolated(Map<String, Integer> labels, Map<String, Map<String, Long>> sym) {
        Map<Integer, Integer> sizes = new HashMap<>();
        labels.values().forEach(c -> sizes.merge(c, 1, Integer::sum));
        int largest = sizes.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        for (Map.Entry<String, Integer> e : labels.entrySet()) {
            if (!sym.getOrDefault(e.getKey(), Map.of()).isEmpty()) {
                continue;
            }
            String best = null;
            long bestWeight = -1;
            for (DependencyEdge edge : graph.outEdges(e.getKey())) {
                Integer owner = labels.get(edge.getTo());
                if (owner != null && edge.getWeight() > bestWeight) {
                    bestWeight = edge.getWeight();
                    best = edge.getTo();
                }
            }
            for (DependencyEdge edge : graph.inEdges(e.getKey())) {
                Integer owner = labels.get(edge.getFrom());
                if (owner != null && edge.getWeight() > bestWeight) {
                    bestWeight = edge.getWeight();
                    best = edge.getFrom();
                }
            }
            if (best != null) {
                e.setValue(labels.get(best));
            } else {
                e.setValue(largest);
            }
        }
    }

    private Map<String, Integer> renumber(Map<String, Integer> labels) {
        Map<Integer, Integer> renumber = new HashMap<>();
        Map<String, Integer> result = new LinkedHashMap<>();
        labels.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue())
                .forEach(e -> {
                    int id = renumber.computeIfAbsent(e.getValue(), k -> renumber.size());
                    result.put(e.getKey(), id);
                });
        return result;
    }

    private PartitionResult buildResult(Map<String, Integer> labels,
                                        Map<String, Map<String, Long>> serviceSym,
                                        Set<String> kernel) {
        PartitionResult result = new PartitionResult();
        result.setAlpha(options.getAlpha());
        result.setBeta(options.getBeta());
        result.setKernelClasses(new ArrayList<>(kernel));
        result.setModularity(LouvainPartitioner.modularity(serviceSym, labels));

        Map<Integer, List<String>> byCommunity = groupBy(labels);
        int k = byCommunity.size();

        List<PartitionResult.Community> communities = new ArrayList<>();
        Map<String, Integer> finalLabels = labels;
        byCommunity.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            PartitionResult.Community c = new PartitionResult.Community();
            c.setId(e.getKey());
            c.setName(communityName(e.getValue()));
            c.setClasses(e.getValue());
            communities.add(c);
        });
        result.setCommunities(communities);

        // 指标
        PartitionResult.Metrics metrics = new PartitionResult.Metrics();
        long total = 0;
        long cross = 0;
        Map<Integer, Long> fanIn = new HashMap<>();
        Map<Integer, Long> fanOut = new HashMap<>();
        Map<Integer, Long> intra = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : serviceSym.entrySet()) {
            Integer ca = finalLabels.get(e.getKey());
            if (ca == null) {
                continue;
            }
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                Integer cb = finalLabels.get(f.getKey());
                long w = f.getValue();
                if (cb == null) {
                    continue;
                }
                total += w;
                if (ca.equals(cb)) {
                    intra.merge(ca, w, Long::sum);
                } else {
                    cross += w;
                    fanOut.merge(ca, w, Long::sum);
                    fanIn.merge(cb, w, Long::sum);
                }
            }
        }
        metrics.setCrossRatio(total == 0 ? 0 : (double) cross / total);

        List<PartitionResult.CommunityStat> stats = new ArrayList<>();
        for (PartitionResult.Community c : communities) {
            PartitionResult.CommunityStat stat = new PartitionResult.CommunityStat();
            stat.setId(c.getId());
            stat.setName(c.getName());
            stat.setSize(c.getClasses().size());
            stat.setIntraWeight(intra.getOrDefault(c.getId(), 0L) / 2); // 对称图计了双向
            stat.setFanIn(fanIn.getOrDefault(c.getId(), 0L) / 2);
            stat.setFanOut(fanOut.getOrDefault(c.getId(), 0L) / 2);
            stats.add(stat);
        }
        metrics.setPerCommunity(stats);

        double mean = labels.size() / (double) k;
        double variance = 0;
        for (List<String> members : byCommunity.values()) {
            variance += (members.size() - mean) * (members.size() - mean);
        }
        variance /= Math.max(1, k);
        metrics.setImbalance(mean == 0 ? 0 : Math.sqrt(variance) / mean);

        List<PartitionResult.UseCaseSpan> spans = new ArrayList<>();
        if (!useCases.isEmpty()) {
            double sum = 0;
            int counted = 0;
            for (UseCase uc : useCases) {
                Set<Integer> touched = new HashSet<>();
                for (String cls : uc.getClasses()) {
                    Integer c = finalLabels.get(cls);
                    if (c != null) {
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
            metrics.setAvgUseCaseSpan(counted == 0 ? 0 : sum / counted);
            metrics.setUseCaseSpans(spans);
        }
        result.setMetrics(metrics);
        return result;
    }

    /** 社区自动命名：从类名分词，去掉通用噪音词，业务类（Controller/ServiceImpl）计双票 */
    private String communityName(List<String> classes) {
        Map<String, Integer> votes = new HashMap<>();
        for (String fqcn : classes) {
            ClassInfo info = graph.node(fqcn);
            if (info == null) {
                continue;
            }
            int weight = (info.getStereotype() == ClassStereotype.CONTROLLER
                    || info.getStereotype() == ClassStereotype.SERVICE_IMPL) ? 2 : 1;
            String[] camel = info.getSimpleName().split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (String token : camel) {
                String t = token.toLowerCase(Locale.ROOT);
                if (t.length() >= 3 && !NOISE_TOKENS.contains(t)) {
                    votes.merge(t, weight, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + "-service")
                .orElse("service");
    }
}
