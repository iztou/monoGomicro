package com.monogomicro.core.audit;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 3：坏边界 fixture——六类反模式必须被检出且分档正确；好边界零误报；
 * 指标双口径（boundaryCoupling 剔 shared / 辅助口径含 shared）与边界条件。
 */
class ArchitectureAuditorTest {

    private static final class Fixture {
        final DependencyGraph graph = new DependencyGraph();
        final List<ServiceLandscape.Service> services = new ArrayList<>();

        Fixture svc(String name, int classCount, boolean shared) {
            List<String> fqcns = new ArrayList<>();
            for (int i = 0; i < classCount; i++) {
                String fqcn = "com.t." + shortName(name) + "." + shortName(name) + i;
                graph.addNode(new ClassInfo(fqcn, name, "src/" + fqcn + ".java"));
                fqcns.add(fqcn);
            }
            services.add(new ServiceLandscape.Service(name, fqcns, shared));
            return this;
        }

        Fixture svc(String name, int classCount) {
            return svc(name, classCount, false);
        }

        /** fromSvc 第 idxFrom 类 → toSvc 第 idxTo 类，weight 次 METHOD_CALL。 */
        Fixture edge(String fromSvc, int idxFrom, String toSvc, int idxTo, int weight) {
            String from = cls(fromSvc, idxFrom);
            String to = cls(toSvc, idxTo);
            for (int i = 0; i < weight; i++) {
                graph.addOccurrence(from, to, DependencyEdge.EdgeKind.METHOD_CALL, "test");
            }
            return this;
        }

        Fixture inherit(String fromSvc, int idxFrom, String toSvc, int idxTo) {
            graph.addOccurrence(cls(fromSvc, idxFrom), cls(toSvc, idxTo),
                    DependencyEdge.EdgeKind.INHERITANCE, "extends");
            return this;
        }

        String cls(String svc, int idx) {
            return "com.t." + shortName(svc) + "." + shortName(svc) + idx;
        }

        ServiceLandscape landscape() {
            return new ServiceLandscape(services);
        }

        private static String shortName(String svc) {
            int dot = svc.lastIndexOf('.');
            return dot < 0 ? svc : svc.substring(dot + 1);
        }
    }

    private static List<AuditReport.Finding> findings(AuditReport r, String type) {
        return r.getFindings().stream().filter(f -> f.getType().equals(type)).collect(Collectors.toList());
    }

    @Test
    void detectsThreeServiceCycle() {
        Fixture f = new Fixture().svc("svcA", 5).svc("svcB", 5).svc("svcC", 5)
                .edge("svcA", 0, "svcB", 0, 3)
                .edge("svcB", 0, "svcC", 0, 2)
                .edge("svcC", 0, "svcA", 0, 1);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        List<AuditReport.Finding> cycles = findings(r, "serviceCycle");
        assertEquals(1, cycles.size());
        assertEquals("critical", cycles.get(0).getSeverity());
        assertEquals(3, cycles.get(0).getServices().size());
        assertTrue(cycles.get(0).getId().startsWith("serviceCycle:"));
    }

    @Test
    void detectsGodService() {
        Fixture f = new Fixture().svc("svcBig", 17).svc("svcSmall1", 3).svc("svcSmall2", 3)
                .edge("svcSmall1", 0, "svcBig", 0, 2);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        List<AuditReport.Finding> gods = findings(r, "godService");
        assertEquals(1, gods.size());
        assertEquals("svcBig", gods.get(0).getServices().get(0));
    }

    @Test
    void detectsMergePairButNotWhenExternalFlowDominates() {
        // 纯互指：双向占比恰为 0.5（数学上限），含等号命中
        Fixture pure = new Fixture().svc("svcP", 4).svc("svcQ", 4)
                .edge("svcP", 0, "svcQ", 0, 10).edge("svcQ", 0, "svcP", 0, 10);
        AuditReport pureReport = ArchitectureAuditor.audit(pure.graph, pure.landscape());
        assertEquals(1, findings(pureReport, "mergePair").size());

        // 有明显第三者流量：占比跌破阈值，不命中
        Fixture busy = new Fixture().svc("svcP", 4).svc("svcQ", 4).svc("svcR", 4)
                .edge("svcP", 0, "svcQ", 0, 10).edge("svcQ", 0, "svcP", 0, 10)
                .edge("svcP", 1, "svcR", 0, 30).edge("svcQ", 1, "svcR", 0, 30);
        AuditReport busyReport = ArchitectureAuditor.audit(busy.graph, busy.landscape());
        assertEquals(0, findings(busyReport, "mergePair").size());
    }

    @Test
    void detectsHollowServiceAndPureProducerIsNotHollow() {
        // 标准哑服务：被依赖（入 1）但出边 9 倍于入
        Fixture f = new Fixture().svc("svcH", 2).svc("svcA", 5)
                .edge("svcH", 0, "svcA", 0, 9)
                .edge("svcA", 0, "svcH", 1, 1); // svcH 有人依赖
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        List<AuditReport.Finding> hollow = findings(r, "hollowService");
        assertEquals(1, hollow.size());
        assertEquals("svcH", hollow.get(0).getServices().get(0));
        assertEquals("info", hollow.get(0).getSeverity());

        // 纯输出（fanIn=0）的小服务不是哑服务——它是调用方
        Fixture producer = new Fixture().svc("svcP", 2).svc("svcA", 5)
                .edge("svcP", 0, "svcA", 0, 9);
        AuditReport r2 = ArchitectureAuditor.audit(producer.graph, producer.landscape());
        assertEquals(0, findings(r2, "hollowService").size());
    }

    @Test
    void detectsSharedAbuseWithAllServicesWarning() {
        Fixture f = new Fixture().svc("svcA", 3).svc("svcB", 3).svc("common", 2, true)
                .edge("svcA", 0, "common", 0, 2)
                .edge("svcB", 0, "common", 0, 2);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        List<AuditReport.Finding> abuse = findings(r, "sharedAbuse");
        assertEquals(1, abuse.size());
        assertEquals("warning", abuse.get(0).getSeverity()); // 2/2 全部业务服务
        assertEquals(f.cls("common", 0), abuse.get(0).getClasses().get(0));
    }

    @Test
    void detectsMisplacedClassAndInheritanceEscalation() {
        Fixture f = new Fixture().svc("svcA", 4).svc("svcB", 4)
                // 普通错位：A0 的出边主要指向 B（5/6）
                .edge("svcA", 0, "svcB", 0, 5).edge("svcA", 0, "svcA", 1, 1)
                // 继承错位：A2 extends B1，唯一出边
                .inherit("svcA", 2, "svcB", 1)
                // 正常类：出边主要在本服务
                .edge("svcA", 3, "svcA", 1, 5).edge("svcA", 3, "svcB", 0, 1);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        List<AuditReport.Finding> misplaced = findings(r, "misplaced");
        assertEquals(2, misplaced.size()); // 按差值排序：继承错位 p_max=1.0 在前
        assertEquals("critical", misplaced.get(0).getSeverity());
        assertEquals(Boolean.TRUE, misplaced.get(0).getMetrics().get("inheritance"));
        assertEquals(f.cls("svcA", 2), misplaced.get(0).getClasses().get(0));
        assertEquals("warning", misplaced.get(1).getSeverity());
    }

    @Test
    void sharedPhantomCommunityDoesNotInflateModularity() {
        // B1 回归：shared 类间的边不得计入任何社区内聚（-1 若进 modularity 会成幻影社区抬高 Q）
        Fixture base = new Fixture().svc("svcA", 3).svc("svcB", 3)
                .edge("svcA", 0, "svcB", 0, 1);
        Fixture withSharedEdges = new Fixture().svc("svcA", 3).svc("svcB", 3).svc("common", 3, true)
                .edge("svcA", 0, "svcB", 0, 1)
                .edge("common", 0, "common", 1, 50)   // shared 间重边：不得影响模度
                .edge("svcA", 1, "common", 1, 5);     // 业务→shared 边：同样不得影响模度
        AuditReport r1 = ArchitectureAuditor.audit(base.graph, base.landscape());
        AuditReport r2 = ArchitectureAuditor.audit(withSharedEdges.graph, withSharedEdges.landscape());
        // 两图的业务标签完全相同 → 模度必须一致（shared 边被剔除）
        assertEquals(r1.getSummary().getModularity(), r2.getSummary().getModularity(), 1e-9);
    }

    @Test
    void boundaryCouplingExcludesSharedEdges() {
        Fixture f = new Fixture().svc("svcA", 3).svc("svcB", 3).svc("common", 2, true)
                .edge("svcA", 0, "common", 0, 10)   // shared 边：主口径剔除
                .edge("svcA", 1, "svcB", 0, 1)      // 跨业务边
                .edge("svcA", 2, "svcA", 0, 9);     // 服务内边
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        // 主口径：total = 1(跨) + 9(内) = 10，cross = 1 → 0.1
        assertEquals(0.1, r.getSummary().getBoundaryCoupling(), 1e-9);
        // 辅助口径：common 作为独立单元，total = 10+1+9=20，cross = 10+1=11 → 0.55
        assertEquals(0.55, r.getSummary().getBoundaryCouplingInclShared(), 1e-9);
        assertEquals(2, r.getSummary().getBusinessServiceCount());
        assertEquals(1, r.getSummary().getSharedServiceCount());
    }

    @Test
    void cleanBoundaryHasNoFindings() {
        // 每个类的主要出边都在本服务内（错位不触发）；一条单向跨服务边是正常协作
        // （不能放对称互指边——双向占比恰触 mergePair 的含等号阈值）
        Fixture f = new Fixture().svc("svcA", 6).svc("svcB", 6)
                .edge("svcA", 0, "svcA", 1, 5).edge("svcA", 0, "svcA", 2, 3).edge("svcA", 0, "svcB", 0, 1)
                .edge("svcB", 0, "svcB", 1, 5).edge("svcB", 0, "svcB", 2, 3);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        assertEquals(0, r.getFindings().size());
        assertEquals(1 / 17.0, r.getSummary().getBoundaryCoupling(), 1e-9);
    }

    @Test
    void degenerateInputsDoNotCrash() {
        // 空图 / 全 shared / 单服务
        AuditReport empty = ArchitectureAuditor.audit(new DependencyGraph(), new ServiceLandscape(List.of()));
        assertEquals(0, empty.getSummary().getClassCount());
        // R-06：无业务服务时指标必须是有限数值（NaN 会被 Jackson 写成字符串破坏 JSON 契约）
        assertTrue(Double.isFinite(empty.getSummary().getImbalanceCv()));
        assertTrue(Double.isFinite(empty.getSummary().getBoundaryCoupling()));
        assertTrue(Double.isFinite(empty.getSummary().getModularity()));

        Fixture allShared = new Fixture().svc("common", 3, true).svc("util", 2, true)
                .edge("common", 0, "util", 0, 2);
        AuditReport r1 = ArchitectureAuditor.audit(allShared.graph, allShared.landscape());
        assertEquals(0, r1.getSummary().getBusinessServiceCount());
        assertTrue(Double.isFinite(r1.getSummary().getImbalanceCv()));

        Fixture single = new Fixture().svc("only", 3).edge("only", 0, "only", 1, 4);
        AuditReport r2 = ArchitectureAuditor.audit(single.graph, single.landscape());
        assertEquals(0.0, r2.getSummary().getBoundaryCoupling(), 1e-9);
        assertEquals(1, r2.getSummary().getBusinessServiceCount());
    }

    @Test
    void configOverridesThresholds() {
        AuditConfig cfg = new AuditConfig();
        cfg.setMisplacedMargin(1.0); // 差值数学上限即 1.0，取该值永不命中
        Fixture f = new Fixture().svc("svcA", 3).svc("svcB", 3)
                .edge("svcA", 0, "svcB", 0, 9);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape(), cfg);
        assertEquals(0, findings(r, "misplaced").size());
        assertEquals(1.0, ((Number) r.getConfig().get("misplacedMargin")).doubleValue(), 1e-9);
    }

    @Test
    void serviceStatsUseDirectedRawGraphSemantics() {
        Fixture f = new Fixture().svc("svcA", 3).svc("svcB", 3)
                .edge("svcA", 0, "svcB", 0, 2)
                .edge("svcA", 1, "svcA", 2, 5);
        AuditReport r = ArchitectureAuditor.audit(f.graph, f.landscape());
        Map<String, AuditReport.ServiceStat> stats = r.getServices().stream()
                .collect(Collectors.toMap(AuditReport.ServiceStat::getName, s -> s));
        assertEquals(2, stats.get("svcA").getFanOut()); // A→B 出边 2（不是对称图 /2 的 1）
        assertEquals(2, stats.get("svcB").getFanIn());
        assertEquals(5, stats.get("svcA").getIntraWeight());
    }
}
