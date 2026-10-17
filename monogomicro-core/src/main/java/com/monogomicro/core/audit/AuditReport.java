package com.monogomicro.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.monogomicro.core.report.JsonIO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构体检报告（v2 设计 §5.4）。POJO，经 JsonIO 落盘/传输；契约自带 formatVersion。
 *
 * <p>指标命名纪律：主指标是 <b>boundaryCoupling（边界耦合率）</b>——微服务 monorepo 的跨
 * module 边是编译期依赖（对 shared 库是打进各自 jar 的同进程调用），<b>不得</b>表述为
 * "跨服务通信占比"；含 shared 的口径仅作辅助参照。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditReport {

    public static final int CURRENT_FORMAT_VERSION = 1;

    private int formatVersion = CURRENT_FORMAT_VERSION;
    private String generatedAt;
    private MetricsSummary summary = new MetricsSummary();
    private List<ServiceStat> services = new ArrayList<>();
    private List<Finding> findings = new ArrayList<>();
    private Map<String, Object> config = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricsSummary {
        /** 边界耦合率（主口径，剔除 shared：这些边是编译期依赖/同进程调用）。 */
        private double boundaryCoupling;
        /** 辅助口径：shared 服务作为独立单元计入。 */
        private double boundaryCouplingInclShared;
        private double modularity;
        /** 规模失衡：σ/mean（PartitionMetrics 同口径）。 */
        private double imbalanceCv;
        /** 规模失衡：max/min。 */
        private double imbalanceMaxMin;
        private int classCount;
        private int businessServiceCount;
        private int sharedServiceCount;

        public double getBoundaryCoupling() {
            return boundaryCoupling;
        }

        public void setBoundaryCoupling(double v) {
            this.boundaryCoupling = v;
        }

        public double getBoundaryCouplingInclShared() {
            return boundaryCouplingInclShared;
        }

        public void setBoundaryCouplingInclShared(double v) {
            this.boundaryCouplingInclShared = v;
        }

        public double getModularity() {
            return modularity;
        }

        public void setModularity(double v) {
            this.modularity = v;
        }

        public double getImbalanceCv() {
            return imbalanceCv;
        }

        public void setImbalanceCv(double v) {
            this.imbalanceCv = v;
        }

        public double getImbalanceMaxMin() {
            return imbalanceMaxMin;
        }

        public void setImbalanceMaxMin(double v) {
            this.imbalanceMaxMin = v;
        }

        public int getClassCount() {
            return classCount;
        }

        public void setClassCount(int v) {
            this.classCount = v;
        }

        public int getBusinessServiceCount() {
            return businessServiceCount;
        }

        public void setBusinessServiceCount(int v) {
            this.businessServiceCount = v;
        }

        public int getSharedServiceCount() {
            return sharedServiceCount;
        }

        public void setSharedServiceCount(int v) {
            this.sharedServiceCount = v;
        }
    }

    /** 单服务统计（原始有向图口径：A 依赖 B 记 A 出边 / B 入边；只计跨业务服务边）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceStat {
        private String name;
        private boolean shared;
        private int size;
        private long intraWeight;
        private long fanIn;
        private long fanOut;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isShared() {
            return shared;
        }

        public void setShared(boolean shared) {
            this.shared = shared;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getIntraWeight() {
            return intraWeight;
        }

        public void setIntraWeight(long v) {
            this.intraWeight = v;
        }

        public long getFanIn() {
            return fanIn;
        }

        public void setFanIn(long v) {
            this.fanIn = v;
        }

        public long getFanOut() {
            return fanOut;
        }

        public void setFanOut(long v) {
            this.fanOut = v;
        }
    }

    /**
     * 一条体检发现。id 稳定（type + 服务名 + 关键类集合短 hash），供沙盘/后续执行器引用。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Finding {
        /** 反模式类型：serviceCycle / godService / mergePair / hollowService / sharedAbuse / misplaced。 */
        private String type;
        /** critical / warning / info。 */
        private String severity;
        private String id;
        private List<String> services = new ArrayList<>();
        private List<String> classes = new ArrayList<>();
        private String detail;
        /** 量化明细（阈值判定用到的原始数值），键随 type 而定。 */
        private Map<String, Object> metrics = new LinkedHashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<String> getServices() {
            return services;
        }

        public void setServices(List<String> services) {
            this.services = services;
        }

        public List<String> getClasses() {
            return classes;
        }

        public void setClasses(List<String> classes) {
            this.classes = classes;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }

        public void setMetrics(Map<String, Object> metrics) {
            this.metrics = metrics;
        }
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int v) {
        this.formatVersion = v;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String v) {
        this.generatedAt = v;
    }

    public MetricsSummary getSummary() {
        return summary;
    }

    public void setSummary(MetricsSummary v) {
        this.summary = v;
    }

    public List<ServiceStat> getServices() {
        return services;
    }

    public void setServices(List<ServiceStat> v) {
        this.services = v;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void setFindings(List<Finding> v) {
        this.findings = v;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> v) {
        this.config = v;
    }

    /** 落盘（JsonIO 负责建目录与格式化）。 */
    public void write(Path file) throws java.io.IOException {
        setFormatVersion(CURRENT_FORMAT_VERSION);
        JsonIO.write(this, file);
    }
}
