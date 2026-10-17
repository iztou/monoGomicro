package com.monogomicro.core.partition;


import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-11 回归：标签空洞（合并小社区后 id 不连续）与空社区（退火移空）不得进入
 * 目标函数的规模统计。审阅实测：labels {a:0, b:2} 的 objective=0.6414，
 * 连续 {a:0, b:1} 为 0.5——空洞凭空抬高了目标值并诱导坏邻居选择。
 */
class AnnealingEmptyCommunityTest {

    /** 两类各 2 节点、社区内权重 5、跨社区 1 的对称图；labels 可注入空洞。 */
    private static Map<String, Map<String, Long>> sym(int crossWeight) {
        return Map.of(
                "a1", Map.of("a2", 5L, "b1", (long) crossWeight),
                "a2", Map.of("a1", 5L),
                "b1", Map.of("b2", 5L, "a1", (long) crossWeight),
                "b2", Map.of("b1", 5L));
    }

    private static AnnealingRefiner refiner(Map<String, Map<String, Long>> sym,
                                            Map<String, Integer> labels) {
        AnnealingRefiner.Options o = new AnnealingRefiner.Options();
        o.seed = 42;
        return new AnnealingRefiner(sym, labels, List.of(), o);
    }

    @Test
    void gapLabelsScoreSameAsContiguous() {
        // 同样的实际分组 {a1,a2} {b1,b2}：id 是否连续（有无空洞）不得改变 objective
        Map<String, Integer> contiguous = Map.of("a1", 0, "a2", 0, "b1", 1, "b2", 1);
        Map<String, Integer> gapped = Map.of("a1", 0, "a2", 0, "b1", 2, "b2", 2);
        double s1 = refiner(sym(1), contiguous).objective(new int[]{2, 2});
        double s2 = refiner(sym(1), gapped).objective(new int[]{2, 0, 2});
        assertEquals(s1, s2, 1e-9, "空洞/空社区不应改变目标值（修复前 0.6414 vs 0.5）");
    }

    @Test
    void emptiedCommunityIsNotCountedAsImbalance() {
        // {2,2} 与 {4,0}（一个社区被移空=单社区）：后者空槽不得被当成"完美均衡"的稀释项
        Map<String, Integer> labels = Map.of("a1", 0, "a2", 0, "b1", 0, "b2", 0);
        AnnealingRefiner r = refiner(sym(1), labels);
        double balanced = r.objective(new int[]{2, 2});
        double oneEmptied = r.objective(new int[]{4, 0});
        assertTrue(oneEmptied >= balanced - 1e-9,
                "空社区稀释 imbalance 会让坏分组显得更好");
    }
}
