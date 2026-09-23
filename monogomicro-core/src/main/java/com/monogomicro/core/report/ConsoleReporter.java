package com.monogomicro.core.report;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.PartitionResult;

import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 控制台摘要输出。 */
public class ConsoleReporter {

    private final PrintStream out;

    public ConsoleReporter(PrintStream out) {
        this.out = out;
    }

    public void reportAnalysis(AnalysisReport report) {
        out.println();
        out.println("========== 静态分析结果 ==========");
        out.println("项目          : " + report.getProject());
        out.println("扫描时间      : " + report.getScannedAt());
        out.println("类数量        : " + report.getClassCount());
        out.println("依赖边数量    : " + report.getEdgeCount());
        out.println("总权重        : " + report.getTotalWeight());

        Map<String, Long> byModule = report.getClasses().stream()
                .collect(Collectors.groupingBy(c -> c.getMavenModule() == null ? "?" : c.getMavenModule(),
                        TreeMap::new, Collectors.counting()));
        out.println("各 Maven 模块 : " + byModule);

        Map<String, Long> byStereotype = report.getClasses().stream()
                .collect(Collectors.groupingBy(c -> c.getStereotype() == null ? "OTHER" : c.getStereotype().name(),
                        TreeMap::new, Collectors.counting()));
        out.println("构造型分布    : " + byStereotype);
    }

    public void reportPartition(PartitionResult result) {
        out.println();
        out.println("========== 微服务分区建议 ==========");
        out.printf("模度 Q            : %.4f%n", result.getModularity());
        out.printf("跨服务通信占比    : %.2f%%%n", result.getMetrics().getCrossRatio() * 100);
        out.printf("平均用例跨度      : %.2f 个服务%n", result.getMetrics().getAvgUseCaseSpan());
        out.printf("规模失衡度        : %.2f%n", result.getMetrics().getImbalance());
        out.println("共享内核类        : " + result.getKernelClasses().size() + " 个");
        if (result.getKernelClasses().size() <= 20) {
            result.getKernelClasses().forEach(c -> out.println("    - " + c));
        }
        out.println();
        out.println("建议的服务划分：");
        for (PartitionResult.Community c : result.getCommunities()) {
            out.printf("  [%d] %s （%d 个类）%n", c.getId(), c.getName(), c.getClasses().size());
            c.getClasses().stream().limit(8)
                    .forEach(cls -> out.println("        " + cls));
            if (c.getClasses().size() > 8) {
                out.println("        ... 等 " + c.getClasses().size() + " 个类");
            }
        }
        out.println();
        out.println("服务间通信（跨服务边 = 未来 RPC 调用点）：");
        for (PartitionResult.CommunityStat stat : result.getMetrics().getPerCommunity()) {
            out.printf("  %s: 扇出 %d / 扇入 %d / 内聚权重 %d%n",
                    stat.getName(), stat.getFanOut(), stat.getFanIn(), stat.getIntraWeight());
        }
    }
}
