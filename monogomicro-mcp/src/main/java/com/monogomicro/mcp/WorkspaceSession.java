package com.monogomicro.mcp;

import com.monogomicro.codegen.GenerateOptions;
import com.monogomicro.codegen.GenerationResult;
import com.monogomicro.codegen.MicroserviceGenerator;
import com.monogomicro.core.M2mException;
import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.parser.MavenClasspathResolver;
import com.monogomicro.core.parser.ProjectScanner;
import com.monogomicro.core.partition.PartitionEngine;
import com.monogomicro.core.report.JsonIO;
import com.monogomicro.core.trace.TraceAnalyzer;
import com.monogomicro.core.trace.UseCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 会话状态机（MCP 工具层的唯一可变状态）：
 *
 * EMPTY ──analyze──▶ ANALYZED ──computePartition──▶ PARTITIONED
 *   └────loadWorkspace────┘            ▲   moveClass / renameService / undo（编辑不降级状态）
 *
 * - 单 workspace；读工具并行（读锁），写工具串行（写锁）
 * - undo 栈覆盖 move 与 rename（上限 {@link #UNDO_LIMIT}），computePartition / loadWorkspace / 重复 analyze 清栈
 * - 重复 analyze / loadWorkspace = 重开会话（清空全部衍生状态，含轨迹与生成缓存）
 * - 生成缓存失效规则：任何分区编辑（move/rename/undo）后 get_migration_items 报 E_NO_GENERATION
 */
final class WorkspaceSession {

    enum State { EMPTY, ANALYZED, PARTITIONED }

    sealed interface Edit permits MoveEdit, RenameEdit {}
    record MoveEdit(String fqcn, int fromService) implements Edit {}
    record RenameEdit(int serviceId, String oldName) implements Edit {}

    record MoveOutcome(String fqcn, int fromService, int toService) {}
    record GenerateOutcome(GenerationResult result, boolean dryRun, Path outDir) {}

    private static final int UNDO_LIMIT = 50;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Deque<Edit> undoStack = new ArrayDeque<>();

    private State state = State.EMPTY;
    private AnalysisReport report;
    private DependencyGraph graph;
    private PartitionResult partition;
    /** fqcn -> 社区 id（-1 = 共享内核），从 partition 派生并在 move 时同步维护 */
    private Map<String, Integer> serviceOf = new LinkedHashMap<>();
    private Path projectDir;
    private Path workspaceRoot;
    /** load 会话的输入文件（generate 的 outDir 不得包含它们——A-07）；analyze 会话为空 */
    private List<Path> protectedInputs = List.of();
    /** 最近一次 analyze 的 classpath 解析状态（load 会话为 null） */
    private MavenClasspathResolver.ClasspathResult classpathResult;
    /** 已导入的业务用例（ingest_trace），compute_partition 时融合；重开会话清空 */
    private List<UseCase> useCases = List.of();
    /** 生成缓存：分区被编辑后置 null（get_migration_items 报 E_NO_GENERATION） */
    private GenerationResult generationResult;

    // ---------- 流水线（写锁） ----------

    /** analyze 会话：workspace 根默认 <projectDir>/m2m-out（与 CLI 输出约定一致）。 */
    void analyze(Path projectDir, List<String> packagePrefixes, boolean useMavenClasspath) {
        analyze(projectDir, packagePrefixes, useMavenClasspath, null);
    }

    /** 带进度回调的 analyze（每 50 个源文件回调累计计数；回调可为 null）。 */
    void analyze(Path projectDir, List<String> packagePrefixes, boolean useMavenClasspath,
                 java.util.function.IntConsumer onProgress) {
        lock.writeLock().lock();
        try {
            if (!Files.isDirectory(projectDir)) {
                throw new M2mException("E_PROJECT_NOT_FOUND",
                        "项目目录不存在：" + projectDir,
                        "检查 projectDir 是否为单体项目根目录的绝对路径");
            }
            if (useMavenClasspath && Files.isRegularFile(projectDir.resolve("pom.xml"))
                    && !MavenClasspathResolver.mvnAvailable()) {
                // fail-fast：白跑完整扫描再报错太浪费（大项目 analyze 含 mvn 调用可达数分钟）
                throw new M2mException("E_MAVEN_MISSING",
                        "useMavenClasspath=true 但 mvn 不在 PATH 或不可执行",
                        "安装/配置 mvn 后重试；或置 useMavenClasspath=false（精度降级：仅源码+JDK 反射解析）");
            }
            DependencyGraph scanned;
            ProjectScanner scanner = new ProjectScanner(packagePrefixes, useMavenClasspath);
            if (onProgress != null) {
                scanner.setProgressListener(onProgress);
            }
            try {
                scanned = scanner.scan(projectDir);
            } catch (M2mException e) {
                throw e; // core 已结构化（E_PROJECT_NOT_FOUND / E_PKG_NO_MATCH / E_SCAN_LIMIT），直接透传
            } catch (IOException e) {
                throw new M2mException("E_INTERNAL", "静态分析失败：" + e.getMessage(), null);
            }
            this.classpathResult = scanner.classpathResult();
            AnalysisReport fresh = new AnalysisReport();
            fresh.setProject(projectDir.toAbsolutePath().toString());
            fresh.setPackagePrefixes(List.copyOf(packagePrefixes));
            fresh.setClasses(List.copyOf(scanned.nodes()));
            fresh.setEdges(List.copyOf(scanned.edges()));
            fresh.setParseFailures(scanner.parseFailures());

            this.report = fresh;
            this.graph = scanned;
            this.partition = null;
            // A-10：必须 normalize——"./proj" 保留在 workspaceRoot 里会让后续所有
            // normalize 过的路径 startsWith 必假，save/generate 全部误报越界
            this.projectDir = projectDir.toAbsolutePath().normalize();
            this.workspaceRoot = this.projectDir.resolve("m2m-out");
            this.protectedInputs = List.of(); // analyze 会话无输入文件（load 会话的残留要清掉）
            rebuildServiceIndex();
            this.state = State.ANALYZED;
            reopenDerived();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** load 会话：优先显式 projectDir；workspace 根默认 analysis.json 所在目录。 */
    void loadWorkspace(Path analysisPath, Path partitionPath, Path projectDir) {
        lock.writeLock().lock();
        try {
            if (!Files.isRegularFile(analysisPath)) {
                throw new M2mException("E_PROJECT_NOT_FOUND",
                        "analysis.json 不存在：" + analysisPath, "检查路径，或先用 CLI analyze 产出");
            }
            AnalysisReport loaded;
            try {
                loaded = JsonIO.read(analysisPath, AnalysisReport.class);
            } catch (IOException e) {
                throw new M2mException("E_PARSE_FAILED",
                        "analysis.json 解析失败：" + e.getMessage(), "确认是 m2m analyze 产出的文件");
            }
            PartitionResult loadedPartition = null;
            if (partitionPath != null) {
                if (!Files.isRegularFile(partitionPath)) {
                    throw new M2mException("E_PROJECT_NOT_FOUND",
                            "partition.json 不存在：" + partitionPath, "检查路径，或省略以自动生成分区");
                }
                try {
                    loadedPartition = JsonIO.read(partitionPath, PartitionResult.class);
                } catch (IOException e) {
                    throw new M2mException("E_PARSE_FAILED",
                            "partition.json 解析失败：" + e.getMessage(), "确认是 m2m partition 产出的文件");
                }
            }
            DependencyGraph loadedGraph = loaded.toGraph();
            PartitionResult effective = loadedPartition != null
                    ? loadedPartition
                    : new PartitionEngine(loadedGraph, List.of(), new PartitionOptions()).run();

            this.report = loaded;
            this.graph = loadedGraph;
            this.partition = effective;
            this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath().normalize();
            this.workspaceRoot = analysisPath.toAbsolutePath().normalize().getParent();
            java.util.List<Path> inputs = new java.util.ArrayList<>();
            inputs.add(analysisPath.toAbsolutePath().normalize());
            if (partitionPath != null) {
                inputs.add(partitionPath.toAbsolutePath().normalize());
            }
            this.protectedInputs = List.copyOf(inputs);
            this.classpathResult = null; // load 会话无 classpath 上下文
            rebuildServiceIndex();
            this.state = State.PARTITIONED;
            reopenDerived();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 导入 agent 轨迹并解析为业务用例；下一次 compute_partition 融合。 */
    List<UseCase> ingestTrace(Path tracePath) {
        lock.writeLock().lock();
        try {
            requireAnalyzed();
            if (tracePath == null) {
                throw new M2mException("E_BAD_PARAMS", "缺少必填参数：tracePath",
                        "传入 agent 产出的 trace.log 路径");
            }
            if (!Files.isRegularFile(tracePath)) {
                throw new M2mException("E_TRACE_NOT_FOUND",
                        "轨迹文件不存在：" + tracePath,
                        "路径应为 agent 产出的 trace.log（-javaagent out 参数指定）");
            }
            List<UseCase> parsed;
            try {
                parsed = new TraceAnalyzer().analyze(tracePath);
            } catch (IOException e) {
                throw new M2mException("E_TRACE_FORMAT",
                        "轨迹解析失败：" + e.getMessage(),
                        "确认是 monogomicro-agent 产出的轨迹文件（每行 enter/exit 记录）");
            }
            this.useCases = parsed;
            return parsed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    void computePartition(double alpha, double beta, int minSize, int maxServices, long seed) {
        lock.writeLock().lock();
        try {
            requireAnalyzed();
            PartitionOptions options = new PartitionOptions();
            options.setAlpha(alpha);
            options.setBeta(beta);
            options.setMinCommunitySize(minSize);
            options.setMaxServices(maxServices);
            options.setSeed(seed);
            options.validate();
            this.partition = new PartitionEngine(graph, useCases, options).run();
            rebuildServiceIndex();
            this.state = State.PARTITIONED;
            this.undoStack.clear();
            this.generationResult = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------- 编辑（写锁） ----------

    MoveOutcome moveClass(String fqcn, int targetService) {
        lock.writeLock().lock();
        try {
            requirePartitioned();
            Integer current = serviceOf.get(fqcn);
            if (current == null) {
                throw unknownClass(fqcn);
            }
            boolean targetExists = targetService == -1
                    || partition.getCommunities().stream().anyMatch(c -> c.getId() == targetService);
            if (!targetExists) {
                throw unknownService(targetService);
            }
            int from = current;
            if (from == targetService) {
                // no-op 移动：不压 undo 栈（否则内核类 -1→-1 后 undo 会重复 add 进内核列表）
                return new MoveOutcome(fqcn, from, targetService);
            }
            undoPush(new MoveEdit(fqcn, from));
            partition.getCommunities().forEach(c -> c.getClasses().remove(fqcn));
            if (targetService == -1) {
                if (!partition.getKernelClasses().contains(fqcn)) {
                    partition.getKernelClasses().add(fqcn);
                }
            } else {
                partition.getKernelClasses().remove(fqcn);
                for (PartitionResult.Community c : partition.getCommunities()) {
                    if (c.getId() == targetService) {
                        c.getClasses().add(fqcn);
                        break;
                    }
                }
            }
            serviceOf.put(fqcn, targetService);
            generationResult = null;
            return new MoveOutcome(fqcn, from, targetService);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 改服务语义名（影响 get_partition_summary 展示与 generate 的 svc-&lt;tag&gt; 目录名）。 */
    String renameService(int serviceId, String name) {
        lock.writeLock().lock();
        try {
            requirePartitioned();
            PartitionResult.Community target = partition.getCommunities().stream()
                    .filter(c -> c.getId() == serviceId).findFirst().orElse(null);
            if (target == null) {
                throw unknownService(serviceId);
            }
            if (name == null || name.isBlank()) {
                throw new M2mException("E_BAD_PARAMS", "服务名不能为空", null);
            }
            // R-07：重名会让生成阶段两个社区 sanitize 出同一 tag/目录（user 与 user-service 都成 user）
            String trimmed = name.trim();
            boolean duplicate = partition.getCommunities().stream()
                    .anyMatch(c -> c.getId() != serviceId && trimmed.equalsIgnoreCase(c.getName()));
            if (duplicate) {
                throw new M2mException("E_DUPLICATE_SERVICE_NAME", "已有同名服务：" + trimmed,
                        "服务名在生成阶段决定 svc-<tag> 目录，重名会导致目录冲突；请换一个名字");
            }
            undoPush(new RenameEdit(serviceId, target.getName()));
            String oldName = target.getName();
            target.setName(trimmed);
            generationResult = null;
            return oldName;
        } finally {
            lock.writeLock().unlock();
        }
    }

    Edit undo() {
        lock.writeLock().lock();
        try {
            requirePartitioned();
            if (undoStack.isEmpty()) {
                throw new M2mException("E_NOTHING_TO_UNDO", "没有可撤销的编辑", null);
            }
            Edit edit = undoStack.pop();
            if (edit instanceof MoveEdit m) {
                partition.getCommunities().forEach(c -> c.getClasses().remove(m.fqcn()));
                if (m.fromService() == -1) {
                    if (!partition.getKernelClasses().contains(m.fqcn())) {
                        partition.getKernelClasses().add(m.fqcn());
                    }
                } else {
                    partition.getKernelClasses().remove(m.fqcn());
                    for (PartitionResult.Community c : partition.getCommunities()) {
                        if (c.getId() == m.fromService()) {
                            c.getClasses().add(m.fqcn());
                            break;
                        }
                    }
                }
                serviceOf.put(m.fqcn(), m.fromService());
            } else if (edit instanceof RenameEdit r) {
                partition.getCommunities().stream()
                        .filter(c -> c.getId() == r.serviceId())
                        .findFirst().ifPresent(c -> c.setName(r.oldName()));
            }
            generationResult = null;
            return edit;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------- 生成与落盘（写锁） ----------

    GenerateOutcome generateServices(Path projectDirOverride, Path outDir,
                                     boolean dryRun, boolean overwrite) {
        lock.writeLock().lock();
        try {
            requirePartitioned();
            Path effectiveProject = projectDirOverride != null
                    ? projectDirOverride.toAbsolutePath() : projectDir;
            if (effectiveProject == null) {
                throw new M2mException("E_BAD_PARAMS",
                        "缺少单体项目根目录：load_workspace 会话未提供 projectDir，generate 需要源码与 pom",
                        "generate_services 时显式传入 projectDir，或 load_workspace 时提供");
            }
            if (!Files.isDirectory(effectiveProject)) {
                throw new M2mException("E_PROJECT_NOT_FOUND",
                        "projectDir 不存在：" + effectiveProject, "检查单体项目根目录路径");
            }
            Path effectiveOut = outDir != null ? outDir : workspaceRoot.resolve("services");
            Path normalized = effectiveOut.toAbsolutePath().normalize();
            if (!normalized.startsWith(workspaceRoot)) {
                throw new M2mException("E_OUT_OF_WORKSPACE",
                        "outDir 越出 workspace 根：" + effectiveOut,
                        "输出目录必须位于 workspace 根（" + workspaceRoot + "）内");
            }
            GenerationResult result;
            try {
                // 每次调用新实例 + 深拷贝分区：MicroserviceGenerator 持有一次性生成状态，且会直接变异
                // 传入的 partition（提升类写回 kernel/communities）。若共享会话对象，dry-run 先跑一遍
                // 就把提升决策写回分区，紧接的正式生成拿到"已提升"的分区——桥全部消失、内核膨胀。
                PartitionResult generationInput = JsonIO.mapper().treeToValue(
                        JsonIO.mapper().valueToTree(partition), PartitionResult.class);
                result = new MicroserviceGenerator(report, generationInput, effectiveProject)
                        .generate(normalized, new GenerateOptions(dryRun, overwrite)
                                .protectedInputs(protectedInputs.toArray(new Path[0])));
            } catch (M2mException e) {
                throw e; // E_OUTPUT_EXISTS 等结构化错误透传
            } catch (Exception e) {
                throw new M2mException("E_INTERNAL", "生成失败：" + e.getMessage(), null, e);
            }
            this.generationResult = result;
            return new GenerateOutcome(result, dryRun, normalized);
        } finally {
            lock.writeLock().unlock();
        }
    }

    Path savePartition(Path path) {
        lock.writeLock().lock();
        try {
            requirePartitioned();
            Path effective = path != null ? path : workspaceRoot.resolve("partition.json");
            Path normalized = effective.toAbsolutePath().normalize();
            if (!normalized.startsWith(workspaceRoot)) {
                throw new M2mException("E_OUT_OF_WORKSPACE",
                        "保存路径越出 workspace 根：" + effective,
                        "保存路径必须位于 workspace 根（" + workspaceRoot + "）内");
            }
            try {
                JsonIO.write(partition, normalized);
            } catch (IOException e) {
                throw new M2mException("E_INTERNAL", "partition.json 落盘失败：" + e.getMessage(), null);
            }
            return normalized;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------- 读视图（读锁） ----------

    <T> T read(java.util.function.Supplier<T> reader) {
        lock.readLock().lock();
        try {
            return reader.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    State state() {
        lock.readLock().lock();
        try {
            return state;
        } finally {
            lock.readLock().unlock();
        }
    }

    AnalysisReport report() { return report; }
    DependencyGraph graph() { return graph; }
    PartitionResult partition() { return partition; }
    Map<String, Integer> serviceOf() { return serviceOf; }
    Path projectDir() { return projectDir; }
    Path workspaceRoot() { return workspaceRoot; }
    int undoDepth() { return undoStack.size(); }
    MavenClasspathResolver.ClasspathResult classpathResult() { return classpathResult; }
    List<UseCase> useCases() { return useCases; }
    GenerationResult generationResult() { return generationResult; }

    // ---------- 内部 ----------

    private void requireAnalyzed() {
        if (state == State.EMPTY) {
            throw new M2mException("E_NO_ANALYSIS", "会话中尚无分析结果",
                    "先调用 analyze_project 或 load_workspace");
        }
    }

    private void requirePartitioned() {
        if (state == State.EMPTY || state == State.ANALYZED) {
            throw new M2mException("E_NO_PARTITION", "会话中尚无分区结果",
                    "先调用 compute_partition 或 load_workspace（带 partition.json）");
        }
    }

    /** 重开会话：清空轨迹、undo、生成缓存（analyze / load 共用）。 */
    private void reopenDerived() {
        this.useCases = List.of();
        this.undoStack.clear();
        this.generationResult = null;
    }

    private void rebuildServiceIndex() {
        Map<String, Integer> index = new LinkedHashMap<>();
        if (partition != null) {
            for (String kernel : partition.getKernelClasses()) {
                index.put(kernel, -1);
            }
            for (PartitionResult.Community c : partition.getCommunities()) {
                for (String fqcn : c.getClasses()) {
                    index.put(fqcn, c.getId());
                }
            }
        }
        this.serviceOf = index;
    }

    private void undoPush(Edit edit) {
        undoStack.push(edit);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
    }

    /** 带相近候选的 E_UNKNOWN_CLASS：简单名双向包含匹配（拼错常为多打/少打字符），取前 5 个。 */
    M2mException unknownClass(String fqcn) {
        String simple = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        String candidates = report == null || simple.length() < 2 ? "" : report.getClasses().stream()
                .map(ClassInfo::getFqcn)
                .filter(f -> {
                    String fSimple = f.contains(".")
                            ? f.substring(f.lastIndexOf('.') + 1) : f;
                    return fSimple.contains(simple) || simple.contains(fSimple);
                })
                .limit(5)
                .reduce((a, b) -> a + "、" + b)
                .map(c -> "相近候选：" + c + "；")
                .orElse("");
        return new M2mException("E_UNKNOWN_CLASS", "未知类：" + fqcn,
                candidates + "fqcn 须为全限定名，可用 find_classes 按简单名搜索");
    }

    /** 带现有服务清单的 E_UNKNOWN_SERVICE。 */
    M2mException unknownService(int serviceId) {
        String services = partition == null ? "" : partition.getCommunities().stream()
                .map(c -> c.getId() + ":" + c.getName())
                .reduce((a, b) -> a + "、" + b)
                .map(s -> "现有服务：" + s + "；")
                .orElse("");
        return new M2mException("E_UNKNOWN_SERVICE", "未知服务 id：" + serviceId,
                services + "-1 = 共享内核；id 见 get_partition_summary");
    }
}
