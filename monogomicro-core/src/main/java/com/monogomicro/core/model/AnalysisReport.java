package com.monogomicro.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** analyze 阶段产物：analysis.json 的结构。 */
@JsonPropertyOrder({"formatVersion", "project", "scannedAt", "packagePrefixes",
        "mavenModules", "classCount", "edgeCount", "totalWeight", "classes", "edges"})
public class AnalysisReport {

    /** v1 = 初版（无 formatVersion 字段的旧文件读取后即为 0）；v2 = 新增 mavenModules。 */
    public static final int CURRENT_FORMAT_VERSION = 2;

    /** 读取旧文件时保持 0（= v1 标记）；JsonIO.write 落盘前统一盖当前版本号。 */
    private int formatVersion;
    private String project;
    private String scannedAt;
    private List<String> packagePrefixes = new ArrayList<>();
    private List<ClassInfo> classes = new ArrayList<>();
    private List<DependencyEdge> edges = new ArrayList<>();
    private List<String> mavenModules;
    private List<String> parseFailures;

    public int getFormatVersion() { return formatVersion; }
    public void setFormatVersion(int formatVersion) { this.formatVersion = formatVersion; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public String getScannedAt() { return scannedAt; }
    public void setScannedAt(String scannedAt) { this.scannedAt = scannedAt; }
    public List<String> getPackagePrefixes() { return packagePrefixes; }
    public void setPackagePrefixes(List<String> packagePrefixes) { this.packagePrefixes = packagePrefixes; }
    public List<ClassInfo> getClasses() { return classes; }
    public void setClasses(List<ClassInfo> classes) { this.classes = classes; }
    public List<DependencyEdge> getEdges() { return edges; }
    public void setEdges(List<DependencyEdge> edges) { this.edges = edges; }

    /** A-15：解析失败的源文件路径（语法错误不再静默丢弃；旧文件缺失时默认空表）。 */
    public List<String> getParseFailures() { return parseFailures == null ? List.of() : parseFailures; }
    public void setParseFailures(List<String> parseFailures) { this.parseFailures = parseFailures; }

    /** Maven 模块清单（v1 旧文件缺失时由 classes 惰性聚合，自愈兼容）。 */
    public List<String> getMavenModules() {
        if (mavenModules == null) {
            mavenModules = classes.stream()
                    .map(ClassInfo::getMavenModule)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
        }
        return mavenModules;
    }
    public void setMavenModules(List<String> mavenModules) { this.mavenModules = mavenModules; }

    public int getClassCount() { return classes.size(); }
    public int getEdgeCount() { return edges.size(); }
    public long getTotalWeight() { return edges.stream().mapToLong(DependencyEdge::getWeight).sum(); }

    /** 重建依赖图（供 partition/generate/web 阶段复用） */
    public com.monogomicro.core.graph.DependencyGraph toGraph() {
        com.monogomicro.core.graph.DependencyGraph g = new com.monogomicro.core.graph.DependencyGraph();
        classes.forEach(g::addNode);
        edges.forEach(g::putEdge);
        return g;
    }
}
