package com.monogomicro.core.partition;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LouvainPartitionerTest {

    /** 两个稠密簇 + 一条弱连接，标准两簇结构 */
    private Map<String, Map<String, Long>> twoClusters() {
        Map<String, Map<String, Long>> sym = new LinkedHashMap<>();
        String[] a = {"a1", "a2", "a3", "a4", "a5"};
        String[] b = {"b1", "b2", "b3", "b4", "b5"};
        link(sym, a, 10);
        link(sym, b, 10);
        connect(sym, "a1", "b1", 1);
        return sym;
    }

    private void link(Map<String, Map<String, Long>> sym, String[] nodes, long weight) {
        for (int i = 0; i < nodes.length; i++) {
            for (int j = i + 1; j < nodes.length; j++) {
                connect(sym, nodes[i], nodes[j], weight);
            }
        }
    }

    private void connect(Map<String, Map<String, Long>> sym, String x, String y, long w) {
        sym.computeIfAbsent(x, k -> new LinkedHashMap<>()).merge(y, w, Long::sum);
        sym.computeIfAbsent(y, k -> new LinkedHashMap<>()).merge(x, w, Long::sum);
    }

    @Test
    void recoversTwoClusters() {
        Map<String, Map<String, Long>> sym = twoClusters();
        Map<String, Integer> labels = new LouvainPartitioner(42).partition(sym);
        assertEquals(10, labels.size());
        assertEquals(labels.get("a1"), labels.get("a2"));
        assertEquals(labels.get("a1"), labels.get("a5"));
        assertEquals(labels.get("b1"), labels.get("b3"));
        assertEquals(labels.get("b1"), labels.get("b5"));
        assertNotEquals(labels.get("a1"), labels.get("b1"), "两个簇应被分到不同社区");
        double q = LouvainPartitioner.modularity(sym, labels);
        assertTrue(q > 0.3, "两簇结构的模度应显著为正，实际 " + q);
    }

    @Test
    void singleCommunityStaysTogether() {
        Map<String, Map<String, Long>> sym = new LinkedHashMap<>();
        String[] all = {"x1", "x2", "x3"};
        link(sym, all, 5);
        Map<String, Integer> labels = new LouvainPartitioner(42).partition(sym);
        assertEquals(labels.get("x1"), labels.get("x2"));
        assertEquals(labels.get("x2"), labels.get("x3"));
    }

    @Test
    void emptyGraphReturnsEmpty() {
        assertTrue(new LouvainPartitioner(42).partition(Map.of()).isEmpty());
    }
}
