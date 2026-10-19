package com.monogomicro.cli;

import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.parser.ProjectScanner;
import com.monogomicro.core.partition.PartitionEngine;
import com.monogomicro.core.report.ConsoleReporter;
import com.monogomicro.core.report.JsonIO;
import com.monogomicro.core.trace.TraceAnalyzer;
import com.monogomicro.core.trace.UseCase;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "m2m", mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "monoGomicro：Java 单体应用微服务拆分建议工具（Mono2Micro 复刻）",
        subcommands = {Main.AnalyzeCommand.class, Main.PartitionCommand.class,
                Main.TraceCommand.class, Main.GenerateCommand.class, Main.AuditCommand.class})
public final class Main implements Callable<Integer> {

    /** 版本号读 jar manifest 的 Implementation-Version（Maven ${project.version} 注入）。 */
    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"m2m " + com.monogomicro.core.Versions.get()};
        }
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    if (ex instanceof com.monogomicro.core.M2mException e) {
                        // 结构化业务错误：打印 code + hint，不打堆栈
                        System.err.println("错误 [" + e.getCode() + "] " + e.getMessage());
                        if (e.getHint() != null) {
                            System.err.println("提示: " + e.getHint());
                        }
                    } else {
                        System.err.println("错误: " + ex.getMessage());
                    }
                    cmd.usage(System.err);
                    return 2;
                })
                .execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "analyze", description = "静态分析目标项目，产出 analysis.json",
            mixinStandardHelpOptions = true)
    public static class AnalyzeCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "目标项目根目录")
        Path project;

        @CommandLine.Option(names = {"-p", "--package"}, required = true,
                description = "业务包前缀（可多次指定），如 com.ruoyi")
        List<String> packagePrefixes;

        @CommandLine.Option(names = {"-o", "--out"}, defaultValue = "m2m-out",
                description = "输出目录（analysis.json 写到这里）")
        Path out;

        @CommandLine.Option(names = "--no-maven-classpath", defaultValue = "false",
                description = "跳过 mvn dependency:build-classpath（解析精度略降，速度快很多）")
        boolean noMavenClasspath;

        @Override
        public Integer call() throws Exception {
            ProjectScanner scanner = new ProjectScanner(packagePrefixes, !noMavenClasspath);
            DependencyGraph graph = scanner.scan(project);

            AnalysisReport report = new AnalysisReport();
            report.setProject(project.toAbsolutePath().toString());
            report.setScannedAt(LocalDateTime.now().toString());
            report.setPackagePrefixes(packagePrefixes);
            report.setClasses(List.copyOf(graph.nodes()));
            report.setParseFailures(scanner.parseFailures());
            if (!report.getParseFailures().isEmpty()) {
                System.out.printf("警告：%d 个源文件解析失败（语法错误），已跳过：首例 %s%n",
                        report.getParseFailures().size(), report.getParseFailures().get(0));
            }
            report.setEdges(graph.edges());
            JsonIO.write(report, out.resolve("analysis.json"));
            new ConsoleReporter(System.out).reportAnalysis(report);
            System.out.println();
            System.out.println("已写入 " + out.resolve("analysis.json").toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "partition", description = "基于分析结果计算微服务分区，产出 partition.json",
            mixinStandardHelpOptions = true)
    public static class PartitionCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"-a", "--analysis"}, required = true,
                description = "analysis.json 路径")
        Path analysis;

        @CommandLine.Option(names = {"-t", "--trace"},
                description = "agent 轨迹文件（可选；提供则融合运行时行为）")
        Path trace;

        @CommandLine.Option(names = {"-o", "--out"}, defaultValue = "m2m-out",
                description = "输出目录（partition.json 写到这里）")
        Path out;

        @CommandLine.Option(names = "--alpha", defaultValue = "0.5", description = "静态图权重占比")
        double alpha;

        @CommandLine.Option(names = "--beta", defaultValue = "0.5", description = "动态图权重占比")
        double beta;

        @CommandLine.Option(names = {"-s", "--min-size"}, defaultValue = "5",
                description = "服务最小类数量")
        int minSize;

        @CommandLine.Option(names = {"-m", "--max-services"}, defaultValue = "12",
                description = "服务数量软上限（超过则尽量合并；等大孤立分量时可能超出并输出提示）")
        int maxServices;

        @CommandLine.Option(names = "--seed", defaultValue = "42")
        long seed;

        @Override
        public Integer call() throws Exception {
            AnalysisReport report = JsonIO.read(analysis, AnalysisReport.class);
            List<UseCase> useCases = List.of();
            if (trace != null) {
                useCases = new TraceAnalyzer().analyze(trace);
                System.out.printf("轨迹解析：%d 个业务用例%n", useCases.size());
            }
            PartitionOptions options = new PartitionOptions();
            options.setAlpha(alpha);
            options.setBeta(beta);
            options.setMinCommunitySize(minSize);
            options.setMaxServices(maxServices);
            options.setSeed(seed);
            options.validate();

            PartitionResult result = new PartitionEngine(report.toGraph(), useCases, options).run();
            // A-22：min/max 是软约束（孤立无邻居的小社区无法并入）——违反时明确提示，不静默
            long undersized = result.getCommunities().stream()
                    .filter(c -> c.getClasses().size() < minSize).count();
            if (undersized > 0) {
                System.out.printf("提示：%d 个服务低于 --min-size=%d（软约束：孤立/无连接社区无法并入，详见分区结果）%n",
                        undersized, minSize);
            }
            if (result.getCommunities().size() > maxServices) {
                System.out.printf("提示：服务数 %d 超过 --max-services=%d（软约束：等大孤立分量无法再合并）%n",
                        result.getCommunities().size(), maxServices);
            }
            JsonIO.write(result, out.resolve("partition.json"));
            new ConsoleReporter(System.out).reportPartition(result);
            System.out.println("已写入 " + out.resolve("partition.json").toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "trace", description = "查看轨迹文件的用例解析结果（检查插桩效果）",
            mixinStandardHelpOptions = true)
    public static class TraceCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "agent 轨迹文件")
        Path trace;

        @Override
        public Integer call() throws Exception {
            List<UseCase> useCases = new TraceAnalyzer().analyze(trace);
            System.out.printf("共 %d 个业务用例%n", useCases.size());
            for (UseCase uc : useCases) {
                System.out.printf("  %s：%d 个类 / %d 条调用边%n",
                        uc.getName(), uc.getClasses().size(), uc.getEdgeWeights().size());
            }
            return 0;
        }
    }

    @Command(name = "audit", description = "微服务架构体检：现状边界指标 + 反模式检测（--suggest 附优化建议）",
            mixinStandardHelpOptions = true)
    public static class AuditCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"-a", "--analysis"}, required = true,
                description = "analysis.json 路径（微服务 monorepo 跑 m2m analyze 产出）")
        Path analysis;

        @CommandLine.Option(names = {"-s", "--services"},
                description = "可选 services.json：修正按 Maven module 自动推导的服务边界（合并/重命名/shared/排除）")
        Path services;

        @CommandLine.Option(names = {"-c", "--config"},
                description = "可选 audit-config.json：覆盖反模式阈值（字段同 AuditConfig，如 misplacedMargin）")
        Path config;

        @CommandLine.Option(names = "--suggest", defaultValue = "false",
                description = "附加优化建议（现状 vs 参考分区对比 + 沙盘指标 delta）")
        boolean suggest;

        @CommandLine.Option(names = {"-o", "--out"}, defaultValue = "m2m-out",
                description = "输出目录（audit.json / suggestions.json 写到这里）")
        Path out;

        @Override
        public Integer call() throws Exception {
            AnalysisReport report = JsonIO.read(analysis, AnalysisReport.class);
            com.monogomicro.core.audit.ServicesConfig cfg = null;
            if (services != null) {
                cfg = JsonIO.read(services, com.monogomicro.core.audit.ServicesConfig.class);
                cfg.validate();
            }
            com.monogomicro.core.audit.ServiceLandscape landscape =
                    com.monogomicro.core.audit.LandscapeFactory.from(report, cfg);
            DependencyGraph graph = report.toGraph();

            com.monogomicro.core.audit.AuditConfig auditConfig = new com.monogomicro.core.audit.AuditConfig();
            if (config != null) {
                auditConfig = JsonIO.read(config, com.monogomicro.core.audit.AuditConfig.class);
            }
            auditConfig.validate();
            com.monogomicro.core.audit.AuditReport auditReport =
                    com.monogomicro.core.audit.ArchitectureAuditor.audit(graph, landscape, auditConfig);
            auditReport.write(out.resolve("audit.json"));
            com.monogomicro.core.audit.AuditReporter.report(System.out, auditReport);
            System.out.println("已写入 " + out.resolve("audit.json").toAbsolutePath());

            if (suggest) {
                com.monogomicro.core.audit.RefactoringAdvisor.Advice advice =
                        com.monogomicro.core.audit.RefactoringAdvisor.advise(graph, landscape);
                JsonIO.write(advice, out.resolve("suggestions.json"));
                System.out.printf("优化建议：%d 条移动类群 / %d 条结构审视（参考分区 %s 个服务，seed=%s）%n",
                        advice.getMoves().size(), advice.getStructures().size(),
                        advice.getReferencePartition().get("serviceCount"),
                        advice.getReferencePartition().get("seed"));
                advice.getMoves().stream().limit(5).forEach(m ->
                        System.out.printf("  %s → %s（%d 类，耦合 %.4f→%.4f 模度 %.4f→%.4f%s）%n",
                                m.getFrom(), m.getTo(), m.getClasses().size(),
                                m.getBoundaryCouplingBefore(), m.getBoundaryCouplingAfter(),
                                m.getModularityBefore(), m.getModularityAfter(),
                                m.isWorsened() ? "，沙盘变差（warning）" : ""));
                advice.getStructures().forEach(st ->
                        System.out.printf("  [%s] %s：%s%n", st.getKind(), st.getService(), st.getDetail()));
                System.out.println("已写入 " + out.resolve("suggestions.json").toAbsolutePath());
                System.out.println("提示：" + advice.getDisclaimer());
            }
            return 0;
        }
    }

    @Command(name = "generate", description = "按分区生成微服务工程骨架与跨服务调用改写",
            mixinStandardHelpOptions = true)
    public static class GenerateCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"-a", "--analysis"}, required = true)
        Path analysis;

        @CommandLine.Option(names = {"-p", "--partition"}, required = true)
        Path partition;

        @CommandLine.Option(names = {"-o", "--out"}, defaultValue = "m2m-out/services",
                description = "生成的微服务工程输出目录")
        Path out;

        @CommandLine.Option(names = {"--project"}, required = true,
                description = "原单体项目根目录（用于拷贝源文件）")
        Path project;

        @CommandLine.Option(names = "--overwrite", defaultValue = "false",
                description = "目标目录已存在且非空时清空重建（默认拒绝并报 E_OUTPUT_EXISTS）")
        boolean overwrite;

        @Override
        public Integer call() throws Exception {
            AnalysisReport report = JsonIO.read(analysis, AnalysisReport.class);
            PartitionResult partitionResult = JsonIO.read(partition, PartitionResult.class);
            com.monogomicro.codegen.MicroserviceGenerator generator =
                    new com.monogomicro.codegen.MicroserviceGenerator(report, partitionResult, project);
            com.monogomicro.codegen.GenerationResult result =
                    generator.generate(out, new com.monogomicro.codegen.GenerateOptions(false, overwrite)
                            .protectedInputs(analysis, partition));
            System.out.println(result.summary());
            System.out.println("生成目录：" + out.toAbsolutePath());
            return 0;
        }
    }
}
