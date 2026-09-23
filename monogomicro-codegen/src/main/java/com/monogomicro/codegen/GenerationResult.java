package com.monogomicro.codegen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生成结果统计 + 人工迁移清单数据。 */
public class GenerationResult {

    private Path outputDir;
    private final Map<String, Integer> classesPerService = new LinkedHashMap<>();
    private final List<String> rewrittenInjections = new ArrayList<>();
    private final List<String> generatedBridges = new ArrayList<>();
    private final List<String> manualItems = new ArrayList<>();
    private int copiedResources;

    public Path getOutputDir() { return outputDir; }
    public void setOutputDir(Path outputDir) { this.outputDir = outputDir; }
    public Map<String, Integer> getClassesPerService() { return classesPerService; }
    public List<String> getRewrittenInjections() { return rewrittenInjections; }
    public List<String> getGeneratedBridges() { return generatedBridges; }
    public List<String> getManualItems() { return manualItems; }
    public int getCopiedResources() { return copiedResources; }
    public void setCopiedResources(int copiedResources) { this.copiedResources = copiedResources; }

    public String summary() {
        return "生成完成：" + classesPerService.size() + " 个服务 + 共享内核 / "
                + classesPerService.values().stream().mapToInt(Integer::intValue).sum() + " 个类 / "
                + rewrittenInjections.size() + " 处跨服务注入自动改写 / "
                + generatedBridges.size() + " 组 RPC 桥 / "
                + copiedResources + " 个资源文件 / "
                + manualItems.size() + " 项需人工处理（见 MIGRATION.md）";
    }
}
