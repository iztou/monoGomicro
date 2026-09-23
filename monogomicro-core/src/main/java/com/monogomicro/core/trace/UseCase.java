package com.monogomicro.core.trace;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 一个业务用例（从入口到完成）的运行时行为：参与的类与调用权重。 */
@JsonPropertyOrder({"name", "classes", "edgeWeights"})
public class UseCase {
    private final String name;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Map<String, Long> edgeWeights = new LinkedHashMap<>();

    public UseCase(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public Set<String> getClasses() { return classes; }
    public Map<String, Long> getEdgeWeights() { return edgeWeights; }

    public void addCall(String caller, String callee) {
        if (caller != null) {
            edgeWeights.merge(caller + "->" + callee, 1L, Long::sum);
        }
        classes.add(callee);
        if (caller != null && !caller.isEmpty()) {
            classes.add(caller);
        }
    }
}
