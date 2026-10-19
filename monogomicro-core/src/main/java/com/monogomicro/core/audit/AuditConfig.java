package com.monogomicro.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 体检阈值（全部固定默认值 + 可覆盖；分位数自适应明确不做——设计 §5.3 定论 Q1）。
 * 报告会自带生效值（自描述），保证同一份报告不依赖读者脑内的默认值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditConfig {

    // #2 上帝服务：size > max(均值×godServiceSizeRatio, godServiceMinSize) 且为最大服务
    private double godServiceSizeRatio = 2.0;
    private int godServiceMinSize = 15;

    // #3 该合并对：min(w(A→B), w(B→A)) > mergePairRatio × min(F(A), F(B))，F=跨服务出入总流量
    private double mergePairRatio = 0.5;

    // #4 哑服务：跨服务出边 > hollowOutInRatio × 入边，且 size < hollowMaxSize
    private int hollowOutInRatio = 3;
    private int hollowMaxSize = 8;

    // #5 共享库滥用：shared 类按扇入业务服务数排序 top sharedAbuseTopN，扇入 ≥ 半数业务服务
    private int sharedAbuseTopN = 10;

    // #6 归属错位类：p_max − p_in > misplacedMargin；按差值排序 top misplacedTopN
    private double misplacedMargin = 0.2;
    private int misplacedTopN = 15;

    public double getGodServiceSizeRatio() {
        return godServiceSizeRatio;
    }

    public void setGodServiceSizeRatio(double v) {
        this.godServiceSizeRatio = v;
    }

    public int getGodServiceMinSize() {
        return godServiceMinSize;
    }

    public void setGodServiceMinSize(int v) {
        this.godServiceMinSize = v;
    }

    public double getMergePairRatio() {
        return mergePairRatio;
    }

    public void setMergePairRatio(double v) {
        this.mergePairRatio = v;
    }

    public int getHollowOutInRatio() {
        return hollowOutInRatio;
    }

    public void setHollowOutInRatio(int v) {
        this.hollowOutInRatio = v;
    }

    public int getHollowMaxSize() {
        return hollowMaxSize;
    }

    public void setHollowMaxSize(int v) {
        this.hollowMaxSize = v;
    }

    public int getSharedAbuseTopN() {
        return sharedAbuseTopN;
    }

    public void setSharedAbuseTopN(int v) {
        this.sharedAbuseTopN = v;
    }

    public double getMisplacedMargin() {
        return misplacedMargin;
    }

    public void setMisplacedMargin(double v) {
        this.misplacedMargin = v;
    }

    public int getMisplacedTopN() {
        return misplacedTopN;
    }

    public void setMisplacedTopN(int v) {
        this.misplacedTopN = v;
    }

    /** 阈值校验（R-12）：负数/NaN 阈值不产生异常而是产出无意义报告，统一拦在入口。 */
    public void validate() {
        java.util.Map<String, Double> numeric = new java.util.LinkedHashMap<>();
        numeric.put("godServiceSizeRatio", godServiceSizeRatio);
        numeric.put("godServiceMinSize", (double) godServiceMinSize);
        numeric.put("mergePairRatio", mergePairRatio);
        numeric.put("hollowOutInRatio", (double) hollowOutInRatio);
        numeric.put("hollowMaxSize", (double) hollowMaxSize);
        numeric.put("sharedAbuseTopN", (double) sharedAbuseTopN);
        numeric.put("misplacedMargin", misplacedMargin);
        numeric.put("misplacedTopN", (double) misplacedTopN);
        numeric.forEach((k, v) -> {
            if (!Double.isFinite(v) || v < 0) {
                throw new com.monogomicro.core.M2mException("E_BAD_PARAMS",
                        "audit 阈值必须为非负有限数：" + k + "=" + v, null);
            }
        });
    }

    /** 序列化用的扁平视图（报告 config 字段）。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("godServiceSizeRatio", godServiceSizeRatio);
        m.put("godServiceMinSize", godServiceMinSize);
        m.put("mergePairRatio", mergePairRatio);
        m.put("hollowOutInRatio", hollowOutInRatio);
        m.put("hollowMaxSize", hollowMaxSize);
        m.put("sharedAbuseTopN", sharedAbuseTopN);
        m.put("misplacedMargin", misplacedMargin);
        m.put("misplacedTopN", misplacedTopN);
        return m;
    }
}
