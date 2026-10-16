package com.monogomicro.core.graph;

import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** edgeCount() 是 O(1) 计数器（扫描上限检查在主循环内逐单元调用，依赖此复杂度）。
 *  本测试锁定它必须始终等于 edges().size()，防止后续改动引入失同步。 */
class DependencyGraphCountTest {

    @Test
    void counterMatchesEdgeListAcrossMixedMutations() {
        DependencyGraph g = new DependencyGraph();
        for (String n : new String[]{"a.A", "a.B", "a.C"}) {
            g.addNode(new ClassInfo(n, "m", n + ".java"));
        }

        // 同一对多次累计 -> 仍是一条边
        g.addOccurrence("a.A", "a.B", DependencyEdge.EdgeKind.FIELD, "f1");
        g.addOccurrence("a.A", "a.B", DependencyEdge.EdgeKind.METHOD_CALL, "m1");
        assertEquals(1, g.edgeCount());

        // 自环被忽略
        g.addOccurrence("a.A", "a.A", DependencyEdge.EdgeKind.FIELD, "self");
        assertEquals(1, g.edgeCount());

        // 新对 -> +1
        g.addOccurrence("a.B", "a.C", DependencyEdge.EdgeKind.CONSTRUCT, "c1");
        assertEquals(2, g.edgeCount());

        // putEdge 替换既有边不重复计数，插入新边 +1
        DependencyEdge replace = new DependencyEdge("a.A", "a.B");
        replace.setWeight(99);
        g.putEdge(replace);
        assertEquals(2, g.edgeCount());
        assertEquals(99, g.edge("a.A", "a.B").getWeight(), "putEdge 应保留替换语义");
        g.putEdge(new DependencyEdge("a.C", "a.A"));
        assertEquals(3, g.edgeCount());

        // 最终一致：计数器 == 实际边列表
        assertEquals(g.edges().size(), g.edgeCount());
    }
}
