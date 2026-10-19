package com.monogomicro.web;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.PartitionResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 会话状态：当前载入的分析与分区，支持页面调整后实时重算指标。 */
@Component
public class ConsoleState {

    private AnalysisReport report;
    /** F-07：load 时的输入文件路径（analysis + partition），generate 的 outDir 守卫要用。 */
    private java.util.List<String> inputPaths = new java.util.ArrayList<>();
    private DependencyGraph graph;
    private PartitionResult partition;
    private java.nio.file.Path saveRoot; // load 时记录：/api/save 只允许写到该目录树内（R-04）
    private java.nio.file.Path analysisPath; // load 的输入文件：/api/generate 的 outDir 不得包含它（A-07）
    private final Map<String, Integer> labels = new LinkedHashMap<>(); // fqcn -> 社区 id（-1 = 内核）
    private final Map<String, String> sourcePaths = new HashMap<>();

    public void load(AnalysisReport report, PartitionResult partition) {
        load(report, partition, null, null);
    }

    public void load(AnalysisReport report, PartitionResult partition,
                     String analysisPath, String partitionPath) {
        this.inputPaths.clear();
        if (analysisPath != null) {
            this.inputPaths.add(analysisPath);
        }
        if (partitionPath != null) {
            this.inputPaths.add(partitionPath);
        }
        this.report = report;
        this.graph = report.toGraph();
        this.partition = partition;
        labels.clear();
        sourcePaths.clear();
        for (ClassInfo c : report.getClasses()) {
            sourcePaths.put(c.getFqcn(), c.getSourcePath());
        }
        for (String kernel : partition.getKernelClasses()) {
            labels.put(kernel, -1);
        }
        for (PartitionResult.Community c : partition.getCommunities()) {
            for (String fqcn : c.getClasses()) {
                labels.put(fqcn, c.getId());
            }
        }
    }

    public boolean isLoaded() {
        return report != null;
    }

    public AnalysisReport report() { return report; }
    public java.util.List<String> inputPaths() { return inputPaths; }
    public DependencyGraph graph() { return graph; }
    public PartitionResult partition() { return partition; }
    public Map<String, Integer> labels() { return labels; }
    public String sourcePath(String fqcn) { return sourcePaths.get(fqcn); }
    public java.nio.file.Path saveRoot() { return saveRoot; }
    public void saveRoot(java.nio.file.Path saveRoot) { this.saveRoot = saveRoot; }
    public java.nio.file.Path analysisPath() { return analysisPath; }
    public void analysisPath(java.nio.file.Path analysisPath) { this.analysisPath = analysisPath; }

    /** 调整后回写分区对象（重算指标在控制器里做）。先校验、后改状态（R-13：
     *  先改后验会把类从所有社区删掉再抛错，labels 指向不存在的 id，状态不一致） */
    public void move(String fqcn, int targetCommunity) {
        if (!labels.containsKey(fqcn)) {
            throw new IllegalArgumentException("未知类: " + fqcn);
        }
        if (targetCommunity != -1 && partition.getCommunities().stream()
                .noneMatch(c -> c.getId() == targetCommunity)) {
            throw new IllegalArgumentException("未知社区: " + targetCommunity);
        }
        labels.put(fqcn, targetCommunity);
        partition.getCommunities().forEach(c -> c.getClasses().remove(fqcn));
        if (targetCommunity == -1) {
            if (!partition.getKernelClasses().contains(fqcn)) {
                partition.getKernelClasses().add(fqcn);
            }
            return;
        }
        partition.getKernelClasses().remove(fqcn);
        for (PartitionResult.Community c : partition.getCommunities()) {
            if (c.getId() == targetCommunity) {
                c.getClasses().add(fqcn);
                return;
            }
        }
    }
}
