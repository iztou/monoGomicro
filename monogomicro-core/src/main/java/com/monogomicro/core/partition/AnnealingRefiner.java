package com.monogomicro.core.partition;

import com.monogomicro.core.trace.UseCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模拟退火精化。Louvain 只优化模度；这里显式建模微服务拆分真正关心的三项目标：
 * 1) 跨分区通信成本（跨服务边权占比）
 * 2) 用例跨度（一个业务用例跨的服务数——原版 Mono2Micro 的核心洞察：调用链跨服务越多，延迟与一致性代价越高）
 * 3) 规模均衡（服务间类数量方差）
 */
public class AnnealingRefiner {

    public static class Options {
        public double wCommunication = 0.5;
        public double wUseCaseSpan = 0.3;
        public double wBalance = 0.2;
        public int iterationsPerNode = 20;
        public int minCommunitySize = 3;
        public long seed = 42;
    }

    private final Map<String, Map<String, Long>> sym;
    private final List<UseCase> useCases;
    private final Map<String, Integer> labels;
    private final int k;
    private final Options options;
    private final Random random;

    public AnnealingRefiner(Map<String, Map<String, Long>> sym, Map<String, Integer> labels,
                            List<UseCase> useCases, Options options) {
        this.sym = sym;
        this.useCases = useCases;
        this.labels = labels;
        this.k = labels.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        this.options = options;
        this.random = new Random(options.seed);
    }

    public Map<String, Integer> refine() {
        List<String> nodes = new ArrayList<>(labels.keySet());
        if (nodes.size() < 2 || k < 2) {
            return new java.util.LinkedHashMap<>(labels);
        }
        int totalIterations = Math.max(200, options.iterationsPerNode * nodes.size());
        double temperature = 0.1;
        double cooling = Math.pow(0.001 / 0.1, 1.0 / totalIterations);

        int[] communitySizes = sizes();
        double current = objective(communitySizes);
        Map<String, Integer> best = new java.util.LinkedHashMap<>(labels);
        double bestScore = current;

        for (int iter = 0; iter < totalIterations; iter++) {
            String node = nodes.get(random.nextInt(nodes.size()));
            int from = labels.get(node);
            if (communitySizes[from] <= options.minCommunitySize) {
                continue; // 不掏空小社区
            }
            int to = randomNeighborCommunity(node, from);
            if (to < 0 || to == from) {
                continue;
            }
            labels.put(node, to);
            communitySizes[from]--;
            communitySizes[to]++;
            double candidate = objective(communitySizes);
            double delta = candidate - current;
            if (delta <= 0 || random.nextDouble() < Math.exp(-delta / temperature)) {
                current = candidate;
                if (current < bestScore) {
                    bestScore = current;
                    best = new java.util.LinkedHashMap<>(labels);
                }
            } else {
                labels.put(node, from);
                communitySizes[from]++;
                communitySizes[to]--;
            }
            temperature *= cooling;
        }
        return best;
    }

    private int[] sizes() {
        int[] sizes = new int[k];
        for (int c : labels.values()) {
            sizes[c]++;
        }
        return sizes;
    }

    /** 优先选邻居所在社区（有意义的移动），偶尔随机（跳出局部最优） */
    private int randomNeighborCommunity(String node, int from) {
        Map<String, Long> adj = sym.getOrDefault(node, Map.of());
        if (!adj.isEmpty() && random.nextDouble() < 0.8) {
            List<String> neighbors = new ArrayList<>(adj.keySet());
            String neighbor = neighbors.get(random.nextInt(neighbors.size()));
            Integer c = labels.get(neighbor);
            if (c != null) {
                return c;
            }
        }
        return random.nextInt(k);
    }

    /** 目标函数：越小越好，三项都已归一化到 [0,1] 量级 */
    public double objective(int[] communitySizes) {
        long total = 0;
        long cross = 0;
        for (Map.Entry<String, Map<String, Long>> e : sym.entrySet()) {
            Integer ca = labels.get(e.getKey());
            for (Map.Entry<String, Long> f : e.getValue().entrySet()) {
                long w = f.getValue();
                total += w;
                if (ca == null || !ca.equals(labels.get(f.getKey()))) {
                    cross += w;
                }
            }
        }
        double crossRatio = total == 0 ? 0 : (double) cross / total;

        double spanNorm = useCaseSpanNorm();

        // R-11：退火中单点移动可能把社区移空——空社区不是真实社区，不应计入规模均值/方差
        //（否则系统性抬高 imbalance，还会诱导"移入空社区"的坏邻居选择）。只统计非空社区。
        int active = 0;
        for (int s : communitySizes) {
            if (s > 0) {
                active++;
            }
        }
        long sizeSum = java.util.Arrays.stream(communitySizes).sum();
        double mean = active == 0 ? 0 : (double) sizeSum / active;
        double variance = 0;
        for (int s : communitySizes) {
            if (s > 0) {
                variance += (s - mean) * (s - mean);
            }
        }
        variance /= Math.max(1, active);
        double imbalance = mean == 0 ? 0 : Math.sqrt(variance) / mean;

        return options.wCommunication * crossRatio + options.wUseCaseSpan * spanNorm
                + options.wBalance * imbalance;
    }

    /** 平均用例跨度归一化：1 个服务=0，k 个服务=1 */
    private double useCaseSpanNorm() {
        if (useCases.isEmpty() || k <= 1) {
            return 0;
        }
        double sum = 0;
        int counted = 0;
        for (UseCase uc : useCases) {
            java.util.Set<Integer> touched = new java.util.HashSet<>();
            for (String cls : uc.getClasses()) {
                Integer c = labels.get(cls);
                if (c != null) {
                    touched.add(c);
                }
            }
            if (!touched.isEmpty()) {
                sum += touched.size();
                counted++;
            }
        }
        if (counted == 0) {
            return 0;
        }
        double avgSpan = sum / counted;
        return Math.max(0, Math.min(1, (avgSpan - 1) / (k - 1)));
    }
}
