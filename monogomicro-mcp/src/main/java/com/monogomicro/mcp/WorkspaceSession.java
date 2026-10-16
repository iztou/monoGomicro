package com.monogomicro.mcp;

import com.monogomicro.core.M2mException;
import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.PartitionOptions;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.parser.MavenClasspathResolver;
import com.monogomicro.core.parser.ProjectScanner;
import com.monogomicro.core.partition.PartitionEngine;
import com.monogomicro.core.report.JsonIO;

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
 *   └────loadWorkspace────┘            ▲   moveClass / undo（编辑不降级状态）
 *
 * - 单 workspace；读工具并行（读锁），写工具串行（写锁）
 * - undo 栈仅覆盖 moveClass（上限 {@link #UNDO_LIMIT}），computePartition / loadWorkspace / 重复 analyze 清栈
 * - 重复 analyze / loadWorkspace = 重开会话（清空全部衍生状态）
 */
final class WorkspaceSession {

    enum State { EMPTY, ANALYZED, PARTITIONED }

    record Edit(String fqcn, int fromService) {}

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
    /** 最近一次 analyze 的 classpath 解析状态（load 会话为 null） */
    private MavenClasspathResolver.ClasspathResult classpathResult;

    // ---------- 流水线（写锁） ----------

    /** analyze 会话：workspace 根默认 <projectDir>/m2m-out（与 CLI 输出约定一致）。 */
    void analyze(Path projectDir, List<String> packagePrefixes, boolean useMavenClasspath) {
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

            this.report = fresh;
            this.graph = scanned;
            this.partition = null;
            this.projectDir = projectDir.toAbsolutePath();
            this.workspaceRoot = this.projectDir.resolve("m2m-out");
            rebuildServiceIndex();
            this.state = State.ANALYZED;
            this.undoStack.clear();
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
            this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath();
            this.workspaceRoot = analysisPath.toAbsolutePath().getParent();
            this.classpathResult = null; // load 会话无 classpath 上下文
            rebuildServiceIndex();
            this.state = State.PARTITIONED;
            this.undoStack.clear();
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
            this.partition = new PartitionEngine(graph, List.of(), options).run();
            rebuildServiceIndex();
            this.state = State.PARTITIONED;
            this.undoStack.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------- 编辑（写锁） ----------

    record MoveOutcome(String fqcn, int fromService, int toService) {}

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
                throw new M2mException("E_UNKNOWN_SERVICE",
                        "未知服务 id：" + targetService,
                        "服务 id 见 get_partition_summary 返回的 services[].id；-1 表示移入共享内核");
            }
            int from = current;
            if (from == targetService) {
                // no-op 移动：不压 undo 栈（否则内核类 -1→-1 后 undo 会重复 add 进内核列表）
                return new MoveOutcome(fqcn, from, targetService);
            }
            undoPush(new Edit(fqcn, from));
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
            return new MoveOutcome(fqcn, from, targetService);
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
            partition.getCommunities().forEach(c -> c.getClasses().remove(edit.fqcn()));
            if (edit.fromService() == -1) {
                if (!partition.getKernelClasses().contains(edit.fqcn())) {
                    partition.getKernelClasses().add(edit.fqcn());
                }
            } else {
                partition.getKernelClasses().remove(edit.fqcn());
                for (PartitionResult.Community c : partition.getCommunities()) {
                    if (c.getId() == edit.fromService()) {
                        c.getClasses().add(edit.fqcn());
                        break;
                    }
                }
            }
            serviceOf.put(edit.fqcn(), edit.fromService());
            return edit;
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

    static M2mException unknownClass(String fqcn) {
        return new M2mException("E_UNKNOWN_CLASS", "未知类：" + fqcn,
                "fqcn 须为全限定名，可用 find_classes 按简单名搜索");
    }
}
