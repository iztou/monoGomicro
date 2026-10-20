package com.monogomicro.core.audit;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.partition.PartitionMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 体检引擎：依赖图 + 现状边界 → {@link AuditReport}（架构指标 + 六类反模式）。
 *
 * <p>口径纪律（设计 §5.2）：
 * <ul>
 *   <li>主指标 boundaryCoupling 剔除 shared 服务（编译期依赖非跨进程调用），复用
 *       {@link PartitionMetrics#of(DependencyGraph, Map, int, List)} 的 kernel=-1 语义；</li>
 *   <li>每服务 fanIn/fanOut 为<b>原始有向图</b>一遍扫边聚合（非 buildResult 的对称图 /2 口径）；</li>
 *   <li>扇入类统计（#5/#6）一律扫边聚合，禁止逐类 inEdges（O(V²)）。</li>
 * </ul>
 */
public final class ArchitectureAuditor {

    private ArchitectureAuditor() {
    }

    public static AuditReport audit(DependencyGraph graph, ServiceLandscape landscape) {
        return audit(graph, landscape, new AuditConfig());
    }

    public static AuditReport audit(DependencyGraph graph, ServiceLandscape landscape, AuditConfig cfg) {
        // I-03：公共 API 不能只靠 CLI 入口校验——库调用方传入非法阈值（负数/Infinity）
        // 会让反模式静默消失，产出"正常"的误导性报告
        cfg.validate();
        List<ServiceLandscape.Service> business = landscape.businessServices();
        List<ServiceLandscape.Service> sharedSvcs = landscape.sharedServices();

        // 服务名 ↔ 编号：业务 0..n-1；shared 在辅助口径里续编 n..（主口径统一 -1）
        Map<String, Integer> idOf = new HashMap<>();
        for (int i = 0; i < business.size(); i++) {
            idOf.put(business.get(i).name(), i);
        }
        for (int i = 0; i < sharedSvcs.size(); i++) {
            idOf.put(sharedSvcs.get(i).name(), business.size() + i);
        }
        Map<String, Integer> mainLabels = new HashMap<>();
        Map<String, Integer> inclLabels = new HashMap<>();
        for (ServiceLandscape.Service s : business) {
            for (String fqcn : s.fqcns()) {
                mainLabels.put(fqcn, idOf.get(s.name()));
                inclLabels.put(fqcn, idOf.get(s.name()));
            }
        }
        for (ServiceLandscape.Service s : sharedSvcs) {
            int sid = idOf.get(s.name());
            for (String fqcn : s.fqcns()) {
                mainLabels.put(fqcn, -1);
                inclLabels.put(fqcn, sid);
            }
        }

        // ---------- 一遍扫边 ----------
        Map<String, Long> intra = new HashMap<>();
        Map<String, Long> fanIn = new HashMap<>();
        Map<String, Long> fanOut = new HashMap<>();
        Map<String, Map<String, Long>> svcGraph = new LinkedHashMap<>(); // 业务服务有向图
        // 每个业务类：出边按目标服务聚合（含 SHARED 桶），分母为全部出边
        Map<String, Map<String, Long>> classOut = new HashMap<>();
        Map<String, Long> classOutTotal = new HashMap<>();
        Map<String, Set<String>> classInheritTo = new HashMap<>(); // 类 --继承--> 服务（错位升级用）
        // shared 类被哪些业务服务依赖（#5 扇入服务数）
        Map<String, Set<String>> sharedFaninServices = new HashMap<>();

        for (DependencyEdge e : graph.edges()) {
            String sa = landscape.serviceOf(e.getFrom()).orElse(null);
            String sb = landscape.serviceOf(e.getTo()).orElse(null);
            if (sa == null || sb == null) {
                continue; // landscape 外（excluded）
            }
            boolean aShared = landscape.isSharedService(sa);
            boolean bShared = landscape.isSharedService(sb);
            boolean inheritance = e.kindSet().contains(DependencyEdge.EdgeKind.INHERITANCE);

            if (!aShared) {
                // 类级出边统计（#6 分母含投向 shared 的部分）
                Map<String, Long> out = classOut.computeIfAbsent(e.getFrom(), k -> new HashMap<>());
                out.merge(sb, (long) e.getWeight(), Long::sum);
                classOutTotal.merge(e.getFrom(), (long) e.getWeight(), Long::sum);
                if (inheritance) {
                    classInheritTo.computeIfAbsent(e.getFrom(), k -> new HashSet<>()).add(sb);
                }
            }
            if (bShared && !aShared) {
                sharedFaninServices.computeIfAbsent(e.getTo(), k -> new HashSet<>()).add(sa);
            }
            if (aShared || bShared) {
                continue; // shared 边不参与服务级统计（口径见设计 §5.2）
            }
            if (sa.equals(sb)) {
                intra.merge(sa, (long) e.getWeight(), Long::sum);
            } else {
                fanOut.merge(sa, (long) e.getWeight(), Long::sum);
                fanIn.merge(sb, (long) e.getWeight(), Long::sum);
                svcGraph.computeIfAbsent(sa, k -> new LinkedHashMap<>()).merge(sb, (long) e.getWeight(), Long::sum);
            }
        }

        // ---------- 指标 ----------
        PartitionMetrics.Snapshot main = PartitionMetrics.of(graph, mainLabels, 0, List.of());
        PartitionMetrics.Snapshot incl = PartitionMetrics.of(graph, inclLabels, 0, List.of());

        AuditReport report = new AuditReport();
        report.setGeneratedAt(LocalDateTime.now().withNano(0).toString());
        AuditReport.MetricsSummary sum = report.getSummary();
        sum.setBoundaryCoupling(main.crossRatio());
        sum.setBoundaryCouplingInclShared(incl.crossRatio());
        sum.setModularity(main.modularity());
        sum.setClassCount(landscape.classCount());
        sum.setBusinessServiceCount(business.size());
        sum.setSharedServiceCount(sharedSvcs.size());

        Map<Integer, List<String>> byCommunity = new HashMap<>();
        mainLabels.forEach((fqcn, c) -> {
            if (c >= 0) {
                byCommunity.computeIfAbsent(c, k -> new ArrayList<>()).add(fqcn);
            }
        });
        sum.setImbalanceCv(PartitionMetrics.imbalance(byCommunity));
        int maxSize = business.stream().mapToInt(s -> s.fqcns().size()).max().orElse(0);
        int minSize = business.stream().mapToInt(s -> s.fqcns().size()).min().orElse(0);
        sum.setImbalanceMaxMin(minSize == 0 ? 0 : (double) maxSize / minSize);

        for (ServiceLandscape.Service s : business) {
            report.getServices().add(statOf(s, intra, fanIn, fanOut));
        }
        for (ServiceLandscape.Service s : sharedSvcs) {
            AuditReport.ServiceStat stat = statOf(s, intra, fanIn, fanOut);
            report.getServices().add(stat);
        }

        // ---------- 反模式 ----------
        List<AuditReport.Finding> findings = new ArrayList<>();
        findings.addAll(serviceCycles(svcGraph));
        findings.addAll(godServices(business, cfg));
        findings.addAll(mergePairs(business, fanIn, fanOut, svcGraph, cfg));
        findings.addAll(hollowServices(business, fanIn, fanOut, cfg));
        findings.addAll(sharedAbuse(sharedSvcs, sharedFaninServices, business.size(), cfg));
        findings.addAll(misplacedClasses(business, landscape, classOut, classOutTotal, classInheritTo, cfg));
        findings.forEach(f -> f.setId(findingId(f)));
        report.setFindings(findings);
        report.setConfig(cfg.describe());
        return report;
    }

    private static AuditReport.ServiceStat statOf(ServiceLandscape.Service s, Map<String, Long> intra,
                                                  Map<String, Long> fanIn, Map<String, Long> fanOut) {
        AuditReport.ServiceStat stat = new AuditReport.ServiceStat();
        stat.setName(s.name());
        stat.setShared(s.shared());
        stat.setSize(s.fqcns().size());
        stat.setIntraWeight(intra.getOrDefault(s.name(), 0L));
        stat.setFanIn(fanIn.getOrDefault(s.name(), 0L));
        stat.setFanOut(fanOut.getOrDefault(s.name(), 0L));
        return stat;
    }

    // ---------- #1 服务循环依赖：服务图 Tarjan SCC ----------

    private static List<AuditReport.Finding> serviceCycles(Map<String, Map<String, Long>> svcGraph) {
        List<List<String>> sccs = tarjan(svcGraph);
        List<AuditReport.Finding> out = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() < 2) {
                continue;
            }
            List<String> sorted = new ArrayList<>(scc);
            sorted.sort(String::compareTo);
            // 环上最强边（帮助读者定位元凶）
            List<String> topEdges = new ArrayList<>();
            svcGraph.forEach((a, adj) -> {
                if (!scc.contains(a)) {
                    return;
                }
                adj.forEach((b, w) -> {
                    if (scc.contains(b)) {
                        topEdges.add(a + " -> " + b + " (w=" + w + ")");
                    }
                });
            });
            AuditReport.Finding f = new AuditReport.Finding();
            f.setType("serviceCycle");
            f.setSeverity("critical");
            f.setServices(sorted);
            f.setDetail("服务级循环依赖（" + sorted.size() + " 个服务成环）；环边："
                    + String.join("、", topEdges));
            out.add(f);
        }
        return out;
    }

    /** Tarjan 强连通分量（递归版；服务数小，深度安全）。 */
    private static List<List<String>> tarjan(Map<String, Map<String, Long>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        for (String v : graph.keySet()) {
            if (!index.containsKey(v)) {
                strongConnect(v, graph, index, low, stack, onStack, sccs, counter);
            }
        }
        // 孤立服务不在 svcGraph 键集里——环判定只关心有出边的服务，无需补
        return sccs;
    }

    private static void strongConnect(String v, Map<String, Map<String, Long>> graph,
                                      Map<String, Integer> index, Map<String, Integer> low,
                                      Deque<String> stack, Set<String> onStack,
                                      List<List<String>> sccs, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : graph.getOrDefault(v, Map.of()).keySet()) {
            if (!index.containsKey(w)) {
                strongConnect(w, graph, index, low, stack, onStack, sccs, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!w.equals(v));
            sccs.add(scc);
        }
    }

    // ---------- #2 上帝服务 ----------

    private static List<AuditReport.Finding> godServices(List<ServiceLandscape.Service> business, AuditConfig cfg) {
        List<AuditReport.Finding> out = new ArrayList<>();
        if (business.isEmpty()) {
            return out;
        }
        double mean = business.stream().mapToInt(s -> s.fqcns().size()).average().orElse(0);
        int maxSize = business.stream().mapToInt(s -> s.fqcns().size()).max().orElse(0);
        for (ServiceLandscape.Service s : business) {
            int size = s.fqcns().size();
            if (size > Math.max(mean * cfg.getGodServiceSizeRatio(), cfg.getGodServiceMinSize())
                    && size == maxSize) {
                AuditReport.Finding f = new AuditReport.Finding();
                f.setType("godService");
                f.setSeverity("warning");
                f.setServices(List.of(s.name()));
                f.getMetrics().put("size", size);
                f.getMetrics().put("avgSize", round(mean));
                f.getMetrics().put("share", round(size / (double) landscapeClassCount(business)));
                f.setDetail("上帝服务：" + size + " 类（均值 " + round(mean) + "，占总类数 "
                        + Math.round(100.0 * size / landscapeClassCount(business)) + "%）——建议审视拆分");
                out.add(f);
            }
        }
        return out;
    }

    private static int landscapeClassCount(List<ServiceLandscape.Service> business) {
        return business.stream().mapToInt(s -> s.fqcns().size()).sum();
    }

    // ---------- #3 该合并对 ----------

    private static List<AuditReport.Finding> mergePairs(List<ServiceLandscape.Service> business,
                                                        Map<String, Long> fanIn, Map<String, Long> fanOut,
                                                        Map<String, Map<String, Long>> svcGraph, AuditConfig cfg) {
        List<AuditReport.Finding> out = new ArrayList<>();
        List<String> names = business.stream().map(ServiceLandscape.Service::name).sorted().toList();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i);
                String b = names.get(j);
                long ab = svcGraph.getOrDefault(a, Map.of()).getOrDefault(b, 0L);
                long ba = svcGraph.getOrDefault(b, Map.of()).getOrDefault(a, 0L);
                long bidirectional = Math.min(ab, ba);
                long fa = fanIn.getOrDefault(a, 0L) + fanOut.getOrDefault(a, 0L);
                long fb = fanIn.getOrDefault(b, 0L) + fanOut.getOrDefault(b, 0L);
                long minFlow = Math.min(fa, fb);
                // 注意取等号：两服务"除彼此外几乎没有外部流量"时双向占比恰为 0.5（数学上限），
                // 严格大于将永不命中纯互指场景
                if (minFlow == 0 || bidirectional < cfg.getMergePairRatio() * minFlow) {
                    continue;
                }
                AuditReport.Finding f = new AuditReport.Finding();
                f.setType("mergePair");
                f.setSeverity("warning");
                f.setServices(List.of(a, b));
                f.getMetrics().put("wAB", ab);
                f.getMetrics().put("wBA", ba);
                f.getMetrics().put("flowA", fa);
                f.getMetrics().put("flowB", fb);
                f.getMetrics().put("bidirectionalShare", round(bidirectional / (double) minFlow));
                f.setDetail("该合并候选：" + a + " 与 " + b + " 互为对方主要外部流量"
                        + "（双向 " + bidirectional + "，占较弱侧总流量 "
                        + Math.round(100.0 * bidirectional / minFlow) + "%）——建议审视合并");
                out.add(f);
            }
        }
        return out;
    }

    // ---------- #4 哑服务 ----------

    private static List<AuditReport.Finding> hollowServices(List<ServiceLandscape.Service> business,
                                                            Map<String, Long> fanIn, Map<String, Long> fanOut,
                                                            AuditConfig cfg) {
        List<AuditReport.Finding> out = new ArrayList<>();
        for (ServiceLandscape.Service s : business) {
            long in = fanIn.getOrDefault(s.name(), 0L);
            long out2 = fanOut.getOrDefault(s.name(), 0L);
            // in > 0 前置：纯输出（无人依赖）的小服务是调用方而非"附属"，避免字面语义误伤
            if (s.fqcns().size() < cfg.getHollowMaxSize() && in > 0
                    && out2 > (long) cfg.getHollowOutInRatio() * in) {
                AuditReport.Finding f = new AuditReport.Finding();
                f.setType("hollowService");
                f.setSeverity("info");
                f.setServices(List.of(s.name()));
                f.getMetrics().put("fanIn", in);
                f.getMetrics().put("fanOut", out2);
                f.setDetail("哑服务：" + s.name() + " 出边远大于入边（" + out2 + "/" + in
                        + "），规模仅 " + s.fqcns().size() + " 类——像是附属逻辑，建议审视归属");
                out.add(f);
            }
        }
        return out;
    }

    // ---------- #5 共享库滥用 ----------

    private static List<AuditReport.Finding> sharedAbuse(List<ServiceLandscape.Service> sharedSvcs,
                                                         Map<String, Set<String>> sharedFaninServices,
                                                         int businessCount, AuditConfig cfg) {
        List<AuditReport.Finding> out = new ArrayList<>();
        // A-23：业务服务 < 3 时"扇入 ≥ 半数"是同义反复（1/1、2/2 全部误报 warning）
        if (businessCount < 3 || sharedSvcs.isEmpty()) {
            return out;
        }
        int half = (businessCount + 1) / 2;
        record Entry(String fqcn, int services) {
        }
        List<Entry> entries = new ArrayList<>();
        sharedSvcs.forEach(s -> s.fqcns().forEach(fqcn -> {
            int n = sharedFaninServices.getOrDefault(fqcn, Set.of()).size();
            if (n >= half) {
                entries.add(new Entry(fqcn, n));
            }
        }));
        entries.sort((x, y) -> Integer.compare(y.services(), x.services()));
        for (int i = 0; i < Math.min(entries.size(), cfg.getSharedAbuseTopN()); i++) {
            Entry e = entries.get(i);
            boolean all = e.services() == businessCount;
            AuditReport.Finding f = new AuditReport.Finding();
            f.setType("sharedAbuse");
            f.setSeverity(all ? "warning" : "info");
            f.setClasses(List.of(e.fqcn()));
            f.getMetrics().put("faninServices", e.services());
            f.getMetrics().put("businessServices", businessCount);
            f.setDetail("共享库滥用嫌疑：" + e.fqcn() + " 被 " + e.services() + "/" + businessCount
                    + " 个业务服务依赖" + (all ? "（全部）" : "") + "——广扇入类是否都该在 shared？");
            out.add(f);
        }
        return out;
    }

    // ---------- #6 归属错位类 ----------

    private static List<AuditReport.Finding> misplacedClasses(List<ServiceLandscape.Service> business,
                                                              ServiceLandscape landscape,
                                                              Map<String, Map<String, Long>> classOut,
                                                              Map<String, Long> classOutTotal,
                                                              Map<String, Set<String>> classInheritTo,
                                                              AuditConfig cfg) {
        record Candidate(String fqcn, String own, String best, double pin, double pmax, boolean inherit) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ServiceLandscape.Service s : business) {
            for (String fqcn : s.fqcns()) {
                long total = classOutTotal.getOrDefault(fqcn, 0L);
                if (total <= 0) {
                    continue;
                }
                Map<String, Long> out = classOut.getOrDefault(fqcn, Map.of());
                double pin = out.getOrDefault(s.name(), 0L) / (double) total;
                String best = null;
                double pmax = 0;
                for (Map.Entry<String, Long> e : out.entrySet()) {
                    if (!e.getKey().equals(s.name()) && !landscape.isSharedService(e.getKey())) {
                        double p = e.getValue() / (double) total;
                        if (p > pmax) {
                            pmax = p;
                            best = e.getKey();
                        }
                    }
                }
                if (best != null && pmax - pin > cfg.getMisplacedMargin()) {
                    boolean inherit = classInheritTo.getOrDefault(fqcn, Set.of()).contains(best);
                    candidates.add(new Candidate(fqcn, s.name(), best, pin, pmax, inherit));
                }
            }
        }
        candidates.sort((x, y) -> Double.compare(y.pmax() - y.pin(), x.pmax() - x.pin()));
        List<AuditReport.Finding> out = new ArrayList<>();
        for (int i = 0; i < Math.min(candidates.size(), cfg.getMisplacedTopN()); i++) {
            Candidate c = candidates.get(i);
            AuditReport.Finding f = new AuditReport.Finding();
            f.setType("misplaced");
            f.setSeverity(c.inherit() ? "critical" : "warning");
            f.setServices(List.of(c.own(), c.best()));
            f.setClasses(List.of(c.fqcn()));
            f.getMetrics().put("ownAffinity", round(c.pin()));
            f.getMetrics().put("bestAffinity", round(c.pmax()));
            f.getMetrics().put("inheritance", c.inherit());
            f.setDetail("归属错位嫌疑：" + c.fqcn() + " 在 " + c.own() + "，但出边投向 "
                    + c.best() + " 的占比（" + round(c.pmax()) + "）显著高于投向本服务（"
                    + round(c.pin()) + "）" + (c.inherit() ? "，且存在继承关系（更应尊重的强耦合）" : ""));
            out.add(f);
        }
        return out;
    }

    // ---------- 工具 ----------

    /** 稳定 id：type 前缀 + 服务名/关键类集合 的 SHA-256 短 hash（可读 + 可引用）。 */
    private static String findingId(AuditReport.Finding f) {
        String material = String.join(",", f.getServices()) + "|" + String.join(",", f.getClasses());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(f.getType()).append(':');
            for (int i = 0; i < 3; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必存在；退化路径保证不因 id 中断体检
            return f.getType() + ":" + Integer.toHexString(material.hashCode());
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
