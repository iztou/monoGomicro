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
                Main.TraceCommand.class, Main.GenerateCommand.class})
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
                description = "服务数量上限（超过则合并最小社区）")
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

            PartitionResult result = new PartitionEngine(report.toGraph(), useCases, options).run();
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

        @Override
        public Integer call() throws Exception {
            AnalysisReport report = JsonIO.read(analysis, AnalysisReport.class);
            PartitionResult partitionResult = JsonIO.read(partition, PartitionResult.class);
            com.monogomicro.codegen.MicroserviceGenerator generator =
                    new com.monogomicro.codegen.MicroserviceGenerator(report, partitionResult, project);
            com.monogomicro.codegen.GenerationResult result = generator.generate(out);
            System.out.println(result.summary());
            System.out.println("生成目录：" + out.toAbsolutePath());
            return 0;
        }
    }
}
