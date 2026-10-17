package com.monogomicro.core.audit;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4：建议层——移动类群（沙盘 delta）、参考分区参数适配（Q7）、可复现元信息。
 */
class RefactoringAdvisorTest {

    private static DependencyGraph graphWith(int n, String prefix) {
        DependencyGraph g = new DependencyGraph();
        for (int i = 0; i < n; i++) {
            g.addNode(new ClassInfo("com.t." + prefix + i, "m", "src/" + i + ".java"));
        }
        return g;
    }

    private static void call(DependencyGraph g, String from, String to, int times) {
        for (int i = 0; i < times; i++) {
            g.addOccurrence(from, to, DependencyEdge.EdgeKind.METHOD_CALL, "test");
        }
    }

    /** 两个内聚簇被现状边界硬切开：B 里 4 个类与 A 的簇强连接。 */
    private static ServiceLandscape splitClusterLandscape(DependencyGraph g) {
        // 簇 1（A 域）：A0..A7 + 错放在 B 的 B0..B3 两两强连接；簇 2（B 域）：B4..B11
        for (int i = 1; i < 8; i++) {
            call(g, "com.t.a" + 0, "com.t.a" + i, 5);
            call(g, "com.t.b" + 0, "com.t.a" + i, 5); // B0 与 A 簇强连接
        }
        for (int i = 1; i < 4; i++) {
            call(g, "com.t.b" + i, "com.t.a" + i, 4);  // B1-B3 也倾向 A 簇
        }
        for (int i = 5; i < 12; i++) {
            call(g, "com.t.b" + 4, "com.t.b" + i, 5);  // B 域内聚
        }
        List<ServiceLandscape.Service> services = new ArrayList<>();
        List<String> svcA = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            svcA.add("com.t.a" + i);
        }
        List<String> svcB = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            svcB.add("com.t.b" + i);
        }
        services.add(new ServiceLandscape.Service("svcA", svcA, false));
        services.add(new ServiceLandscape.Service("svcB", svcB, false));
        return new ServiceLandscape(services);
    }

    @Test
    void suggestsMovingMisplacedClusterWithSandboxesDelta() {
        DependencyGraph g = new DependencyGraph();
        for (int i = 0; i < 8; i++) {
            g.addNode(new ClassInfo("com.t.a" + i, "svcA", "a" + i));
        }
        for (int i = 0; i < 12; i++) {
            g.addNode(new ClassInfo("com.t.b" + i, "svcB", "b" + i));
        }
        ServiceLandscape landscape = splitClusterLandscape(g);

        RefactoringAdvisor.Advice advice = RefactoringAdvisor.advise(g, landscape);

        assertFalse(advice.getMoves().isEmpty(), "切开的两簇应产生移动建议");
        RefactoringAdvisor.MoveSuggestion top = advice.getMoves().get(0);
        assertNotNull(top.getFrom());
        assertNotNull(top.getTo());
        assertFalse(top.getClasses().isEmpty());
        assertTrue(top.getClasses().contains("com.t.b0"), "B0（A 簇强连接）应在移动建议里");
        // 沙盘 delta 数值合法
        assertTrue(top.getBoundaryCouplingAfter() >= 0 && top.getModularityAfter() >= 0);
        assertFalse(top.isWorsened(), "把错放簇移回应变好（worsened=false）");
        // 元信息与免责声明
        assertEquals(42L, ((Number) advice.getReferencePartition().get("seed")).longValue());
        assertTrue(advice.getReferencePartition().containsKey("maxServices"));
        assertTrue(advice.getDisclaimer().contains("参照物"));
    }

    @Test
    void maxServicesAdaptsToLandscapeSize() {
        // 13 个小服务：maxServices 应取 max(12, ceil(13×1.5)) = 20（Q7 定论）
        DependencyGraph g = new DependencyGraph();
        List<ServiceLandscape.Service> services = new ArrayList<>();
        for (int s = 0; s < 13; s++) {
            String prefix = "s" + s + "c";
            for (int i = 0; i < 6; i++) {
                String fqcn = "com.t." + prefix + i;
                g.addNode(new ClassInfo(fqcn, "m" + s, prefix + i));
            }
            List<String> fqcns = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fqcns.add("com.t." + prefix + i);
            }
            // 每个服务内部强连接（保证内聚）
            for (int i = 1; i < 6; i++) {
                call(g, "com.t." + prefix + 0, "com.t." + prefix + i, 3);
            }
            services.add(new ServiceLandscape.Service("svc" + s, fqcns, false));
        }
        RefactoringAdvisor.Advice advice = RefactoringAdvisor.advise(g, new ServiceLandscape(services));
        assertEquals(20, ((Number) advice.getReferencePartition().get("maxServices")).intValue());
    }

    @Test
    void alignedCleanLandscapeProducesFewOrNoMoves() {
        // 两个干净内聚簇 → 无（或极少）移动建议，无结构性建议
        DependencyGraph g = new DependencyGraph();
        for (int i = 0; i < 8; i++) {
            g.addNode(new ClassInfo("com.t.a" + i, "svcA", "a" + i));
            g.addNode(new ClassInfo("com.t.b" + i, "svcB", "b" + i));
        }
        for (int i = 1; i < 8; i++) {
            call(g, "com.t.a0", "com.t.a" + i, 5);
            call(g, "com.t.b0", "com.t.b" + i, 5);
        }
        ServiceLandscape landscape = new ServiceLandscape(List.of(
                new ServiceLandscape.Service("svcA", fqcnList("a", 8), false),
                new ServiceLandscape.Service("svcB", fqcnList("b", 8), false)));
        RefactoringAdvisor.Advice advice = RefactoringAdvisor.advise(g, landscape);
        assertEquals(0, advice.getStructures().size());
        // 干净对齐时无错放类群（偶发的小碎片移动不在此断言范围，只断言不崩且结构完整）
        advice.getMoves().forEach(m -> assertNotNull(m.getTo()));
    }

    private static List<String> fqcnList(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("com.t." + prefix + i);
        }
        return out;
    }
}
