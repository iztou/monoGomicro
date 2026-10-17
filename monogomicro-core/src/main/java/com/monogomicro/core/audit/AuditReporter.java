package com.monogomicro.core.audit;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 体检报告的控制台摘要（对齐 CLI 现有输出风格：指标先行、按严重度分组）。
 * 指标措辞纪律同 {@link AuditReport}：boundaryCoupling 是边界耦合率，不是通信占比。
 */
public final class AuditReporter {

    private static final Map<String, Integer> SEVERITY_ORDER =
            Map.of("critical", 0, "warning", 1, "info", 2);

    private AuditReporter() {
    }

    public static void report(PrintStream out, AuditReport r) {
        AuditReport.MetricsSummary s = r.getSummary();
        out.printf("现状边界：%d 个业务服务 / %d 个 shared 库 / %d 类%n",
                s.getBusinessServiceCount(), s.getSharedServiceCount(), s.getClassCount());
        out.printf("边界耦合率：%.4f（剔除 shared，编译期依赖口径）  含 shared 口径：%.4f%n",
                s.getBoundaryCoupling(), s.getBoundaryCouplingInclShared());
        out.printf("模度：%.4f  规模失衡：σ/mean=%.3f  max/min=%.1f%n",
                s.getModularity(), s.getImbalanceCv(), s.getImbalanceMaxMin());

        List<AuditReport.Finding> findings = r.getFindings();
        if (findings.isEmpty()) {
            out.println("反模式检测：未发现问题（按当前阈值）");
            return;
        }
        out.printf("反模式检测：%d 条%n", findings.size());
        findings.stream()
                .sorted(Comparator.comparingInt(f -> SEVERITY_ORDER.getOrDefault(f.getSeverity(), 9)))
                .forEach(f -> out.printf("  [%s] %-13s %s%n", f.getSeverity(), f.getType(), f.getDetail()));

        out.println("服务统计（fanIn/fanOut 为原始有向图口径）：");
        r.getServices().stream()
                .sorted(Comparator.comparingInt(AuditReport.ServiceStat::getSize).reversed())
                .forEach(st -> out.printf("  %s%s: size=%d 扇入=%d / 扇出=%d / 内聚=%d%n",
                        st.getName(), st.isShared() ? "(shared)" : "",
                        st.getSize(), st.getFanIn(), st.getFanOut(), st.getIntraWeight()));
    }
}
