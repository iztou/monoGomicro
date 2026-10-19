package com.monogomicro.codegen;

/**
 * 生成选项。
 *
 * dryRun=true：只产出统计与迁移清单，不触碰目标目录——完整生成在临时目录执行后清理，
 * 统计值与真生成一致（代价是正式生成时重算一遍，MCP 场景先看摘要再落盘，划算）。
 * overwrite=false 且目标目录已存在且非空时报 E_OUTPUT_EXISTS 而非清空。
 */
public class GenerateOptions {

    private boolean dryRun;
    private boolean overwrite;
    /** A-07：输入文件（analysis/partition 等）绝对路径——outDir 包含任一输入即拒绝，防止清空目录时连带删掉输入 */
    private java.util.List<java.nio.file.Path> protectedInputs = java.util.List.of();

    public GenerateOptions() {
    }

    public GenerateOptions(boolean dryRun, boolean overwrite) {
        this.dryRun = dryRun;
        this.overwrite = overwrite;
    }

    /** 兼容旧行为：无条件清空重写（CLI / web 既有语义）。 */
    public static GenerateOptions legacy() {
        return new GenerateOptions(false, true);
    }

    /** MCP 安全默认：先看统计与迁移项，不落盘。 */
    public static GenerateOptions dryRun() {
        return new GenerateOptions(true, false);
    }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isOverwrite() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }
    public java.util.List<java.nio.file.Path> getProtectedInputs() { return protectedInputs; }
    public GenerateOptions protectedInputs(java.nio.file.Path... inputs) {
        java.util.List<java.nio.file.Path> list = new java.util.ArrayList<>();
        if (inputs != null) {
            for (java.nio.file.Path p : inputs) {
                if (p != null) {
                    list.add(p);
                }
            }
        }
        this.protectedInputs = java.util.List.copyOf(list);
        return this;
    }
}
