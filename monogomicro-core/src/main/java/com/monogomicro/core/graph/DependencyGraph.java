package com.monogomicro.core.graph;

import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 类依赖图：节点 + 有向加权边。提供分区算法需要的邻接视图。 */
public class DependencyGraph {

    private final Map<String, ClassInfo> nodes = new TreeMap<>();
    /** from -> (to -> edge) */
    private final Map<String, Map<String, DependencyEdge>> adjacency = new LinkedHashMap<>();

    public void addNode(ClassInfo info) {
        nodes.put(info.getFqcn(), info);
        adjacency.computeIfAbsent(info.getFqcn(), k -> new LinkedHashMap<>());
    }

    public boolean hasNode(String fqcn) {
        return nodes.containsKey(fqcn);
    }

    public ClassInfo node(String fqcn) {
        return nodes.get(fqcn);
    }

    public Collection<ClassInfo> nodes() {
        return nodes.values();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public DependencyEdge edge(String from, String to) {
        Map<String, DependencyEdge> m = adjacency.get(from);
        return m == null ? null : m.get(to);
    }

    public void addOccurrence(String from, String to, DependencyEdge.EdgeKind kind, String sample) {
        if (from.equals(to)) {
            return; // 自环对分区无意义
        }
        adjacency.computeIfAbsent(from, k -> new LinkedHashMap<>())
                .computeIfAbsent(to, k -> new DependencyEdge(from, to))
                .addOccurrence(kind, sample);
    }

    public List<DependencyEdge> edges() {
        List<DependencyEdge> all = new ArrayList<>();
        adjacency.values().forEach(m -> all.addAll(m.values()));
        return all;
    }

    /** 从持久化数据整体装入一条聚合边 */
    public void putEdge(DependencyEdge edge) {
        adjacency.computeIfAbsent(edge.getFrom(), k -> new LinkedHashMap<>()).put(edge.getTo(), edge);
    }

    public int edgeCount() {
        return edges().size();
    }

    /** from 的出边（from 依赖了谁） */
    public Collection<DependencyEdge> outEdges(String from) {
        Map<String, DependencyEdge> m = adjacency.get(from);
        return m == null ? List.of() : m.values();
    }

    /** to 的入边（谁依赖了 to）——扇入 */
    public List<DependencyEdge> inEdges(String to) {
        List<DependencyEdge> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, DependencyEdge>> e : adjacency.entrySet()) {
            DependencyEdge edge = e.getValue().get(to);
            if (edge != null) {
                result.add(edge);
            }
        }
        return result;
    }

    public long totalWeight() {
        return edges().stream().mapToLong(DependencyEdge::getWeight).sum();
    }

    public void forEach(Consumer<DependencyEdge> consumer) {
        adjacency.values().forEach(m -> m.values().forEach(consumer));
    }

    /** 对称化权重：a-b 之间的无向权重 = max(有向权重和, 最小值保证连通性) */
    public Map<String, Map<String, Long>> symmetrized() {
        Map<String, Map<String, Long>> sym = new LinkedHashMap<>();
        for (String n : nodes.keySet()) {
            sym.put(n, new LinkedHashMap<>());
        }
        forEach(e -> {
            long w = e.getWeight();
            sym.get(e.getFrom()).merge(e.getTo(), w, Long::sum);
            sym.get(e.getTo()).merge(e.getFrom(), w, Long::sum);
        });
        return sym;
    }
}
