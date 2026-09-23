package com.monogomicro.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/** analyze 阶段产物：analysis.json 的结构。 */
@JsonPropertyOrder({"project", "scannedAt", "packagePrefixes", "classCount", "edgeCount", "totalWeight", "classes", "edges"})
public class AnalysisReport {
    private String project;
    private String scannedAt;
    private List<String> packagePrefixes = new ArrayList<>();
    private List<ClassInfo> classes = new ArrayList<>();
    private List<DependencyEdge> edges = new ArrayList<>();

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
