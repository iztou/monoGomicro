package com.monogomicro.core.audit;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.partition.PartitionEngine;
import com.monogomicro.core.partition.PartitionMetrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优化建议：现状边界 vs 参考分区的对比（v2 设计 §5.5）。
 *
 * <p>定位纪律：参考分区是<b>参照物不是真理</b>——历史包袱、团队边界、发布节奏都是算法
 * 看不见的约束。每条移动类群建议附<b>沙盘 delta</b>（landscape 副本上推演，不修改输入）；
 * delta 变差的建议<b>输出并标注 warning，不自动过滤</b>（定论 Q3：这类条目往往揭示
 * 算法看不见的团队/历史约束）。
 *
 * <p>参考分区参数（定论 Q7）：maxServices = max(12, 现状业务服务数 × 1.5)，seed 固定 42，
 * 报告注明参数保证可复现——默认 12 对 >12 服务的现状会系统性建议合并。
 */
public final class RefactoringAdvisor {

    /** 移动类群建议。 */
    public static class MoveSuggestion {
        private String from;
        private String to;
        private List<String> classes = new ArrayList<>();
        private double boundaryCouplingBefore;
        private double boundaryCouplingAfter;
        private double modularityBefore;
        private double modularityAfter;
        /** 沙盘 delta 是否变差（变差仍输出，标注 warning——定论 Q3）。 */
        private boolean worsened;
        private String referenceCommunity;

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public List<String> getClasses() {
            return classes;
        }

        public void setClasses(List<String> classes) {
            this.classes = classes;
        }

        public double getBoundaryCouplingBefore() {
            return boundaryCouplingBefore;
        }

        public void setBoundaryCouplingBefore(double v) {
            this.boundaryCouplingBefore = v;
        }

        public double getBoundaryCouplingAfter() {
            return boundaryCouplingAfter;
        }

        public void setBoundaryCouplingAfter(double v) {
            this.boundaryCouplingAfter = v;
        }

        public double getModularityBefore() {
            return modularityBefore;
        }

        public void setModularityBefore(double v) {
            this.modularityBefore = v;
        }

        public double getModularityAfter() {
            return modularityAfter;
        }

        public void setModularityAfter(double v) {
            this.modularityAfter = v;
        }

        public boolean isWorsened() {
            return worsened;
        }

        public void setWorsened(boolean worsened) {
            this.worsened = worsened;
        }

        public String getReferenceCommunity() {
            return referenceCommunity;
        }

        public void setReferenceCommunity(String v) {
            this.referenceCommunity = v;
        }
    }

    /** 结构性建议（拆分/合并审视项，无沙盘 delta——结构变化的指标推演需要完整重分区）。 */
    public static class StructureSuggestion {
        private String kind; // split | merge
        private String service;
        private String detail;
        private Map<String, Integer> overlap = new LinkedHashMap<>(); // 现状服务 → 参考社区交集类数

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public Map<String, Integer> getOverlap() {
            return overlap;
        }

        public void setOverlap(Map<String, Integer> overlap) {
            this.overlap = overlap;
        }
    }

    /** 建议清单（移动 + 结构审视 + 元信息）。 */
    public static class Advice {
        private List<MoveSuggestion> moves = new ArrayList<>();
        private List<StructureSuggestion> structures = new ArrayList<>();
        /** 参考分区参数（可复现性）。 */
        private Map<String, Object> referencePartition = new LinkedHashMap<>();
        private String disclaimer = "参考分区是参照物不是真理：历史包袱、团队边界、发布节奏都是算法看不见的约束。"
                + "delta 变差的条目（worsened=true）往往揭示这类约束，请人工判断。";

        public List<MoveSuggestion> getMoves() {
            return moves;
        }

        public void setMoves(List<MoveSuggestion> moves) {
            this.moves = moves;
        }

        public List<StructureSuggestion> getStructures() {
            return structures;
        }

        public void setStructures(List<StructureSuggestion> structures) {
            this.structures = structures;
        }

        public Map<String, Object> getReferencePartition() {
            return referencePartition;
        }

        public void setReferencePartition(Map<String, Object> v) {
            this.referencePartition = v;
        }

        public String getDisclaimer() {
            return disclaimer;
        }

        public void setDisclaimer(String disclaimer) {
            this.disclaimer = disclaimer;
        }
    }

    private RefactoringAdvisor() {
    }

    public static Advice advise(DependencyGraph graph, ServiceLandscape landscape) {
        return advise(graph, landscape, new PartitionOptions());
    }

    public static Advice advise(DependencyGraph graph, ServiceLandscape landscape, PartitionOptions base) {
        List<ServiceLandscape.Service> business = landscape.businessServices();

        // ---- 参考分区（shared 类剔除后跑引擎；参数适配 Q7）----
        DependencyGraph businessGraph = restrictTo(graph, business);
        PartitionOptions opts = new PartitionOptions();
        opts.setAlpha(base.getAlpha());
        opts.setBeta(base.getBeta());
        opts.setMinCommunitySize(base.getMinCommunitySize());
        opts.setSeed(base.getSeed());
        int maxServices = Math.max(base.getMaxServices(), (int) Math.ceil(business.size() * 1.5));
        opts.setMaxServices(maxServices);
        PartitionResult reference = new PartitionEngine(businessGraph, List.of(), opts).run();

        // 参考社区名（参考分区已自动命名）
        Map<Integer, String> refName = new HashMap<>();
        for (PartitionResult.Community c : reference.getCommunities()) {
            refName.put(c.getId(), c.getName());
        }
        // fqcn → 参考社区 索引（O(1) 查询；逐社区 contains 是 O(社区×类)）
        Map<String, Integer> refLabel = new HashMap<>();
        for (PartitionResult.Community c : reference.getCommunities()) {
            for (String fqcn : c.getClasses()) {
                refLabel.put(fqcn, c.getId());
            }
        }

        Advice advice = new Advice();
        advice.getReferencePartition().put("maxServices", maxServices);
        advice.getReferencePartition().put("seed", opts.getSeed());
        advice.getReferencePartition().put("minCommunitySize", opts.getMinCommunitySize());
        advice.getReferencePartition().put("serviceCount", reference.getCommunities().size());

        // ---- 贪心对齐：交集类数降序配对 ----
        List<ServiceLandscape.Service> orderedBusiness = new ArrayList<>(business);
        Map<Integer, List<String>> refClasses = new HashMap<>();
        for (PartitionResult.Community c : reference.getCommunities()) {
            refClasses.put(c.getId(), c.getClasses());
        }
        record Pair(String svc, int community, int overlap) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (ServiceLandscape.Service s : orderedBusiness) {
            Set<String> own = new HashSet<>(s.fqcns());
            for (Map.Entry<Integer, List<String>> e : refClasses.entrySet()) {
                int overlap = (int) e.getValue().stream().filter(own::contains).count();
                pairs.add(new Pair(s.name(), e.getKey(), overlap));
            }
        }
        pairs.sort((x, y) -> Integer.compare(y.overlap(), x.overlap()));
        Set<String> usedSvc = new HashSet<>();
        Set<Integer> usedCommunity = new HashSet<>();
        Map<String, Integer> aligned = new LinkedHashMap<>(); // svc -> community
        Map<String, Map<Integer, Integer>> overlapMatrix = new LinkedHashMap<>(); // 结构审视用
        for (ServiceLandscape.Service s : orderedBusiness) {
            overlapMatrix.put(s.name(), new LinkedHashMap<>());
        }
        for (Pair p : pairs) {
            overlapMatrix.get(p.svc()).merge(p.community(), p.overlap(), Integer::sum);
            if (p.overlap() == 0 || usedSvc.contains(p.svc()) || usedCommunity.contains(p.community())) {
                continue;
            }
            aligned.put(p.svc(), p.community());
            usedSvc.add(p.svc());
            usedCommunity.add(p.community());
        }

        // ---- 移动类群建议（沙盘 delta 在 landscape 副本上推演）----
        // 目标解析：参考社区对齐了某现状服务 → 移到该服务（边才可能变内部，delta 有意义）；
        // 未对齐社区（现状缺这个服务）→ 以参考社区名新建服务承接
        Map<Integer, String> communityToSvc = new HashMap<>();
        aligned.forEach((svcName, comm) -> communityToSvc.put(comm, svcName));
        for (Map.Entry<String, Integer> e : aligned.entrySet()) {
            String svc = e.getKey();
            int community = e.getValue();
            Set<String> refSet = new HashSet<>(refClasses.get(community));
            // 现状属 svc 但参考属其他社区的类 → 按目标社区聚合
            Map<Integer, List<String>> moveOut = new LinkedHashMap<>();
            ServiceLandscape.Service own = business.stream()
                    .filter(s -> s.name().equals(svc)).findFirst().orElseThrow();
            for (String fqcn : own.fqcns()) {
                Integer target = refLabel.get(fqcn);
                if (target != null && target != community && !refSet.contains(fqcn)) {
                    moveOut.computeIfAbsent(target, k -> new ArrayList<>()).add(fqcn);
                }
            }
            for (Map.Entry<Integer, List<String>> m : moveOut.entrySet()) {
                String to = communityToSvc.getOrDefault(m.getKey(),
                        refName.getOrDefault(m.getKey(), "community-" + m.getKey()));
                advice.getMoves().add(simulateMove(graph, landscape, svc, to, m.getValue(), m.getKey()));
            }
        }
        advice.getMoves().sort((a, b) -> Double.compare(deltaGain(b), deltaGain(a)));

        // ---- 结构审视：拆分（多参考社区大交集且无一过半）/ 合并（未配对项）----
        for (Map.Entry<String, Map<Integer, Integer>> e : overlapMatrix.entrySet()) {
            if (aligned.containsKey(e.getKey())) {
                continue;
            }
            int total = business.stream().filter(s -> s.name().equals(e.getKey()))
                    .findFirst().map(s -> s.fqcns().size()).orElse(0);
            List<Map.Entry<Integer, Integer>> tops = e.getValue().entrySet().stream()
                    .filter(x -> x.getValue() > 0)
                    .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                    .toList();
            StructureSuggestion sg = new StructureSuggestion();
            sg.setService(e.getKey());
            tops.forEach(t -> sg.getOverlap().put(refName.getOrDefault(t.getKey(),
                    "community-" + t.getKey()), t.getValue()));
            if (tops.size() >= 2 && tops.get(0).getValue() <= total / 2) {
                sg.setKind("split");
                sg.setDetail("与多个参考社区都有大交集且无一过半——建议审视拆分（参考重叠分布见 overlap）");
            } else {
                sg.setKind("merge");
                sg.setDetail("与任何参考社区重叠都不显著——建议审视是否并入邻近服务（或保留现状："
                        + "可能是算法看不见的独立部署诉求）");
            }
            advice.getStructures().add(sg);
        }
        return advice;
    }

    /** 沙盘：在 landscape 副本上把类群从 from 移到 to，重算双指标。 */
    private static MoveSuggestion simulateMove(DependencyGraph graph, ServiceLandscape landscape,
                                               String from, String to, List<String> classes,
                                               int referenceCommunity) {
        PartitionMetrics.Snapshot before = snapshotOf(graph, landscape);

        List<ServiceLandscape.Service> copy = new ArrayList<>();
        for (ServiceLandscape.Service s : landscape.services()) {
            if (s.name().equals(from)) {
                List<String> remaining = new ArrayList<>(s.fqcns());
                remaining.removeAll(classes);
                copy.add(new ServiceLandscape.Service(from, remaining, s.shared()));
            } else {
                copy.add(s);
            }
        }
        // to 若是 shared 或不存在的新名，追加一个服务承接
        boolean exists = copy.stream().anyMatch(s -> s.name().equals(to));
        if (exists) {
            for (int i = 0; i < copy.size(); i++) {
                ServiceLandscape.Service s = copy.get(i);
                if (s.name().equals(to)) {
                    List<String> merged = new ArrayList<>(s.fqcns());
                    merged.addAll(classes);
                    copy.set(i, new ServiceLandscape.Service(to, merged, s.shared()));
                }
            }
        } else {
            copy.add(new ServiceLandscape.Service(to, new ArrayList<>(classes), false));
        }
        PartitionMetrics.Snapshot after = snapshotOf(graph, new ServiceLandscape(copy));

        MoveSuggestion m = new MoveSuggestion();
        m.setFrom(from);
        m.setTo(to);
        m.setClasses(classes);
        m.setBoundaryCouplingBefore(before.crossRatio());
        m.setBoundaryCouplingAfter(after.crossRatio());
        m.setModularityBefore(before.modularity());
        m.setModularityAfter(after.modularity());
        m.setReferenceCommunity("community-" + referenceCommunity);
        // 变差判定：耦合上升或模度下降（任一即标）
        m.setWorsened(after.crossRatio() > before.crossRatio() + 1e-9
                || after.modularity() < before.modularity() - 1e-9);
        return m;
    }

    private static PartitionMetrics.Snapshot snapshotOf(DependencyGraph graph, ServiceLandscape landscape) {
        Map<String, Integer> labels = new HashMap<>();
        int i = 0;
        for (ServiceLandscape.Service s : landscape.businessServices()) {
            for (String fqcn : s.fqcns()) {
                labels.put(fqcn, i);
            }
            i++;
        }
        for (ServiceLandscape.Service s : landscape.sharedServices()) {
            for (String fqcn : s.fqcns()) {
                labels.put(fqcn, -1);
            }
        }
        return PartitionMetrics.of(graph, labels, 0, List.of());
    }

    /** 剔除 shared 类后的子图（参考分区输入）。 */
    private static DependencyGraph restrictTo(DependencyGraph graph, List<ServiceLandscape.Service> keep) {
        Set<String> keepFqcns = new HashSet<>();
        keep.forEach(s -> keepFqcns.addAll(s.fqcns()));
        DependencyGraph sub = new DependencyGraph();
        graph.nodes().stream()
                .filter(c -> keepFqcns.contains(c.getFqcn()))
                .forEach(sub::addNode);
        graph.edges().stream()
                .filter(e -> keepFqcns.contains(e.getFrom()) && keepFqcns.contains(e.getTo()))
                .forEach(sub::putEdge);
        return sub;
    }

    /** 沙盘综合收益（排序用）：模度提升 - 耦合提升。 */
    private static double deltaGain(MoveSuggestion m) {
        return (m.getModularityAfter() - m.getModularityBefore())
                - (m.getBoundaryCouplingAfter() - m.getBoundaryCouplingBefore());
    }
}
