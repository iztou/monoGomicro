package com.monogomicro.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** 有向加权依赖边：from 依赖 to。权重按来源类型累计（见 EdgeKind.weight）。 */
@JsonPropertyOrder({"from", "to", "weight", "count", "kinds", "samples"})
public class DependencyEdge {

    public enum EdgeKind {
        INHERITANCE(3),   // extends / implements
        FIELD(2),         // 字段持有
        CONSTRUCT(1),     // new
        METHOD_CALL(1),   // 方法调用
        PARAM_RETURN(1),  // 方法签名参数/返回类型
        ANNOTATION(1);    // 注解使用

        private final int weight;

        EdgeKind(int weight) { this.weight = weight; }

        public int weight() { return weight; }
    }

    private String from;
    private String to;
    private final EnumSet<EdgeKind> kinds = EnumSet.noneOf(EdgeKind.class);
    private int weight = 0;
    private int count = 0;
    private final List<String> samples = new ArrayList<>();

    public DependencyEdge() {
    }

    public DependencyEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public void addOccurrence(EdgeKind kind, String sample) {
        kinds.add(kind);
        weight += kind.weight();
        count++;
        if (samples.size() < 5 && sample != null) {
            samples.add(sample);
        }
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public List<String> getSamples() { return samples; }
    public List<String> getKinds() { return kinds.stream().map(Enum::name).toList(); }
    public void setKinds(List<String> kinds) {
        this.kinds.clear();
        for (String k : kinds) {
            this.kinds.add(EdgeKind.valueOf(k));
        }
    }
    public EnumSet<EdgeKind> kindSet() { return kinds; }
}
