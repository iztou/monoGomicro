package com.monogomicro.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/** 分区结果：partition.json 的结构。 */
@JsonPropertyOrder({"formatVersion", "alpha", "beta", "modularity", "kernelClasses", "communities", "metrics"})
public class PartitionResult {

    /** v1 = 初版（无 formatVersion 字段的旧文件读取后即为 0）；结构未变，维持 1。 */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** 读取旧文件时保持 0（= v1 标记）；JsonIO.write 落盘前统一盖当前版本号。 */
    private int formatVersion;
    private double alpha;
    private double beta;
    private double modularity;
    private List<String> kernelClasses = new ArrayList<>();
    private List<Community> communities = new ArrayList<>();
    private Metrics metrics = new Metrics();

    public int getFormatVersion() { return formatVersion; }
    public void setFormatVersion(int formatVersion) { this.formatVersion = formatVersion; }

    public double getAlpha() { return alpha; }
    public void setAlpha(double alpha) { this.alpha = alpha; }
    public double getBeta() { return beta; }
    public void setBeta(double beta) { this.beta = beta; }
    public double getModularity() { return modularity; }
    public void setModularity(double modularity) { this.modularity = modularity; }
    public List<String> getKernelClasses() { return kernelClasses; }
    public void setKernelClasses(List<String> kernelClasses) { this.kernelClasses = kernelClasses; }
    public List<Community> getCommunities() { return communities; }
    public void setCommunities(List<Community> communities) { this.communities = communities; }
    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    @JsonPropertyOrder({"id", "name", "classes"})
    public static class Community {
        private int id;
        private String name;
        private List<String> classes = new ArrayList<>();

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getClasses() { return classes; }
        public void setClasses(List<String> classes) { this.classes = classes; }
    }

    public static class Metrics {
        private double crossRatio;
        private double avgUseCaseSpan;
        private double imbalance;
        private List<CommunityStat> perCommunity = new ArrayList<>();
        private List<UseCaseSpan> useCaseSpans = new ArrayList<>();

        public double getCrossRatio() { return crossRatio; }
        public void setCrossRatio(double crossRatio) { this.crossRatio = crossRatio; }
        public double getAvgUseCaseSpan() { return avgUseCaseSpan; }
        public void setAvgUseCaseSpan(double avgUseCaseSpan) { this.avgUseCaseSpan = avgUseCaseSpan; }
        public double getImbalance() { return imbalance; }
        public void setImbalance(double imbalance) { this.imbalance = imbalance; }
        public List<CommunityStat> getPerCommunity() { return perCommunity; }
        public void setPerCommunity(List<CommunityStat> perCommunity) { this.perCommunity = perCommunity; }
        public List<UseCaseSpan> getUseCaseSpans() { return useCaseSpans; }
        public void setUseCaseSpans(List<UseCaseSpan> useCaseSpans) { this.useCaseSpans = useCaseSpans; }
    }

    @JsonPropertyOrder({"id", "name", "size", "intraWeight", "fanIn", "fanOut"})
    public static class CommunityStat {
        private int id;
        private String name;
        private int size;
        private long intraWeight;
        private long fanIn;
        private long fanOut;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public long getIntraWeight() { return intraWeight; }
        public void setIntraWeight(long intraWeight) { this.intraWeight = intraWeight; }
        public long getFanIn() { return fanIn; }
        public void setFanIn(long fanIn) { this.fanIn = fanIn; }
        public long getFanOut() { return fanOut; }
        public void setFanOut(long fanOut) { this.fanOut = fanOut; }
    }

    @JsonPropertyOrder({"name", "span", "communities"})
    public static class UseCaseSpan {
        private String name;
        private int span;
        private List<Integer> communities = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getSpan() { return span; }
        public void setSpan(int span) { this.span = span; }
        public List<Integer> getCommunities() { return communities; }
        public void setCommunities(List<Integer> communities) { this.communities = communities; }
    }
}
