package com.monogomicro.core.partition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Louvain 社区发现（模度 Q 最大化）。完整两阶段：
 * 1) 局部移动——节点反复移到增益最大的邻居社区；
 * 2) 图聚合——同社区折叠成超节点，在新图上重复，直到无改进。
 * 输入为对称化加权邻接表（双向都存在，权重相同），输出 节点 -> 连续化社区编号。
 */
public class LouvainPartitioner {

    /** 一层图：邻接数组 + 社区状态 */
    private static final class Level {
        final List<List<int[]>> neighbors; // [i] -> {邻居j, 权重下标}
        final List<List<Long>> weights;
        final long[] degree;    // 度数（自环×2）
        final long[] commTot;   // 社区度数和
        final long[] commIn;    // 社区内部权重×2
        final int[] community;
        final long m2;          // 总度数 2m

        Level(List<List<int[]>> neighbors, List<List<Long>> weights, long m2) {
            this.neighbors = neighbors;
            this.weights = weights;
            this.degree = new long[neighbors.size()];
            for (int i = 0; i < neighbors.size(); i++) {
                long d = 0;
                for (int k = 0; k < neighbors.get(i).size(); k++) {
                    d += neighbors.get(i).get(k)[0] == i ? 2 * weights.get(i).get(k) : weights.get(i).get(k);
                }
                degree[i] = d;
            }
            this.community = new int[neighbors.size()];
            for (int i = 0; i < neighbors.size(); i++) {
                community[i] = i;
            }
            this.commTot = degree.clone();
            this.commIn = new long[neighbors.size()];
            this.m2 = m2;
        }

        void initCommIn() {
            for (int i = 0; i < community.length; i++) {
                List<int[]> ns = neighbors.get(i);
                List<Long> ws = weights.get(i);
                for (int[] nb : ns) {
                    if (nb[0] == i) {
                        commIn[community[i]] += 2 * ws.get(nb[1]); // 自环 = 内部权重
                    } else if (community[nb[0]] == community[i]) {
                        commIn[community[i]] += ws.get(nb[1]);
                    }
                }
            }
        }
    }

    private record Aggregated(Level next, int[] toSuper) {}

    private final Random random;

    public LouvainPartitioner(long seed) {
        this.random = new Random(seed);
    }

    public Map<String, Integer> partition(Map<String, Map<String, Long>> sym) {
        List<String> order = new ArrayList<>(sym.keySet());
        int n = order.size();
        if (n == 0) {
            return Map.of();
        }
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            indexOf.put(order.get(i), i);
        }

        Level level = buildLevel(sym, order, indexOf);
        int[] nodeToLayer = new int[n]; // 原始节点 -> 当前层节点
        for (int i = 0; i < n; i++) {
            nodeToLayer[i] = i;
        }

        while (true) {
            if (!localMove(level)) {
                break;
            }
            Aggregated agg = aggregate(level);
            if (agg == null) {
                break;
            }
            for (int i = 0; i < n; i++) {
                nodeToLayer[i] = agg.toSuper()[nodeToLayer[i]];
            }
            level = agg.next();
        }

        int[] raw = new int[n];
        for (int i = 0; i < n; i++) {
            raw[i] = level.community[nodeToLayer[i]];
        }
        Map<Integer, Integer> renumber = new HashMap<>();
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int id = renumber.computeIfAbsent(raw[i], k -> renumber.size());
            result.put(order.get(i), id);
        }
        return result;
    }

    private Level buildLevel(Map<String, Map<String, Long>> sym, List<String> order, Map<String, Integer> indexOf) {
        List<List<int[]>> neighbors = new ArrayList<>();
        List<List<Long>> weights = new ArrayList<>();
        long m2 = 0;
        for (int i = 0; i < order.size(); i++) {
            List<int[]> ns = new ArrayList<>();
            List<Long> ws = new ArrayList<>();
            for (Map.Entry<String, Long> e : sym.getOrDefault(order.get(i), Map.of()).entrySet()) {
                Integer j = indexOf.get(e.getKey());
                if (j == null || j == i) {
                    continue;
                }
                ns.add(new int[]{j, ws.size()});
                ws.add(e.getValue());
                m2 += e.getValue();
            }
            neighbors.add(ns);
            weights.add(ws);
        }
        return new Level(neighbors, weights, m2);
    }

    /** @return 本层是否发生过移动 */
    private boolean localMove(Level level) {
        int n = level.community.length;
        if (n <= 1) {
            return false;
        }
        level.initCommIn();
        boolean anyMove = false;
        boolean moved = true;
        while (moved) {
            moved = false;
            List<Integer> scan = shuffledOrder(n);
            for (int i : scan) {
                if (moveBest(level, i)) {
                    moved = true;
                    anyMove = true;
                }
            }
        }
        return anyMove;
    }

    private List<Integer> shuffledOrder(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        java.util.Collections.shuffle(list, random);
        return list;
    }

    private boolean moveBest(Level level, int i) {
        int old = level.community[i];
        level.commTot[old] -= level.degree[i];
        long weightToOld = weightToCommunity(level, i, old);

        Map<Integer, Long> commWeights = new HashMap<>();
        List<int[]> ns = level.neighbors.get(i);
        List<Long> ws = level.weights.get(i);
        for (int[] nb : ns) {
            int c = nb[0] == i ? old : level.community[nb[0]]; // 自环跟随节点旧社区
            commWeights.merge(c, ws.get(nb[1]), Long::sum);
        }

        long ki = level.degree[i];
        int best = old;
        double bestGain = 0;
        for (Map.Entry<Integer, Long> e : commWeights.entrySet()) {
            int c = e.getKey();
            double gain = e.getValue() - (double) level.commTot[c] * ki / level.m2;
            if (gain > bestGain) {
                bestGain = gain;
                best = c;
            }
        }
        long weightToBest = best == old ? weightToOld : weightToCommunity(level, i, best);
        level.community[i] = best;
        level.commTot[best] += level.degree[i];
        level.commIn[old] -= 2 * weightToOld;
        level.commIn[best] += 2 * weightToBest;
        return best != old;
    }

    private long weightToCommunity(Level level, int i, int c) {
        // 自环永远跟随节点自身，计入目标社区
        long w = 0;
        List<int[]> ns = level.neighbors.get(i);
        List<Long> ws = level.weights.get(i);
        for (int[] nb : ns) {
            if (nb[0] == i || level.community[nb[0]] == c) {
                w += ws.get(nb[1]);
            }
        }
        return w;
    }

    /** 折叠同社区为超节点。社区数==节点数（无可折叠）时返回 null。 */
    private Aggregated aggregate(Level level) {
        int n = level.community.length;
        int[] toSuper = new int[n];
        Map<Integer, Integer> superId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            toSuper[i] = superId.computeIfAbsent(level.community[i], k -> superId.size());
        }
        int superN = superId.size();
        if (superN == n) {
            return null;
        }

        Map<Long, Long> superEdges = new HashMap<>(); // key = a*superN+b（a<=b），value = 权重和
        for (int i = 0; i < n; i++) {
            int a = toSuper[i];
            List<int[]> ns = level.neighbors.get(i);
            List<Long> ws = level.weights.get(i);
            for (int k = 0; k < ns.size(); k++) {
                int j = ns.get(k)[0];
                if (j < i) {
                    continue; // 无向边只处理一次（自环 j==i 时处理）
                }
                int b = toSuper[j];
                long key = a <= b ? (long) a * superN + b : (long) b * superN + a;
                superEdges.merge(key, ws.get(k), Long::sum);
            }
        }
        List<List<int[]>> neighbors = new ArrayList<>();
        List<List<Long>> weights = new ArrayList<>();
        for (int s = 0; s < superN; s++) {
            neighbors.add(new ArrayList<>());
            weights.add(new ArrayList<>());
        }
        long m2 = 0;
        for (Map.Entry<Long, Long> e : superEdges.entrySet()) {
            int a = (int) (e.getKey() / superN);
            int b = (int) (e.getKey() % superN);
            long w = e.getValue();
            if (a == b) {
                neighbors.get(a).add(new int[]{a, weights.get(a).size()});
                weights.get(a).add(w);
                m2 += 2 * w;
            } else {
                neighbors.get(a).add(new int[]{b, weights.get(a).size()});
                weights.get(a).add(w);
                neighbors.get(b).add(new int[]{a, weights.get(b).size()});
                weights.get(b).add(w);
                m2 += 2 * w;
            }
        }
        return new Aggregated(new Level(neighbors, weights, m2), toSuper);
    }

    /** 模度 Q（有序对求和 / 2m），供报告与分区质量评估 */
    public static double modularity(Map<String, Map<String, Long>> sym, Map<String, Integer> labels) {
        Map<String, Long> degree = new HashMap<>();
        long m2 = 0;
        for (Map.Entry<String, Map<String, Long>> e : sym.entrySet()) {
            long d = 0;
            for (Long w : e.getValue().values()) {
                d += w;
            }
            degree.put(e.getKey(), d);
            m2 += d;
        }
        if (m2 == 0) {
            return 0;
        }
        double q = 0;
        for (Map.Entry<String, Map<String, Long>> e : sym.entrySet()) {
            String i = e.getKey();
            Integer ci = labels.get(i);
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                if (ci != null && ci.equals(labels.get(f.getKey()))) {
                    double expected = (double) degree.get(i) * degree.get(f.getKey()) / m2;
                    q += f.getValue() - expected;
                }
            }
        }
        return q / m2;
    }
}
