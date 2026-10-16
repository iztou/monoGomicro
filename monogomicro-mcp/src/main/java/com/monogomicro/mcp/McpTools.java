package com.monogomicro.mcp;

import com.monogomicro.codegen.GenerationResult;
import com.monogomicro.core.M2mException;
import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.parser.MavenClasspathResolver;
import com.monogomicro.core.partition.PartitionMetrics;
import com.monogomicro.core.report.JsonIO;
import com.monogomicro.core.trace.UseCase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 工具实现层：纯 Java（出入参均为 JDK 集合/基本类型），不依赖 SDK 类型——
 * 传输/注册层（McpServerMain）只做参数装配与 JSON 序列化。
 *
 * token 预算约定（设计文档 §8）：单工具返回目标 ≤4KB；列表分页默认 50、上限 200；
 * 边列表按权重降序 top-N 并标注 truncated；fqcn 永远全限定名。
 */
final class McpTools {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int FIND_LIMIT = 20;
    private static final int EDGE_TOP_N = 5;

    private final WorkspaceSession session;

    McpTools(WorkspaceSession session) {
        this.session = session;
    }

    // ---------- 流水线层 ----------

    Map<String, Object> analyzeProject(Path projectDir, List<String> packagePrefixes,
                                       boolean useMavenClasspath, Path persistAnalysisPath,
                                       java.util.function.IntConsumer onProgress) {
        long start = System.currentTimeMillis();
        session.analyze(projectDir, packagePrefixes, useMavenClasspath, onProgress);
        AnalysisReport report = session.read(session::report);
        if (persistAnalysisPath != null) {
            // 写路径白名单（设计 §12）：落盘必须在 workspace 根内，拒绝逃逸
            Path root = session.read(session::workspaceRoot);
            Path target = persistAnalysisPath.toAbsolutePath().normalize();
            if (root == null || !target.startsWith(root)) {
                throw new M2mException("E_OUT_OF_WORKSPACE",
                        "persistAnalysisPath 越出 workspace 根：" + persistAnalysisPath,
                        "落盘路径必须位于 workspace 根（默认 <projectDir>/m2m-out）内");
            }
            try {
                JsonIO.write(report, target);
            } catch (IOException e) {
                throw new M2mException("E_INTERNAL", "analysis.json 落盘失败：" + e.getMessage(), null);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classes", report.getClassCount());
        out.put("edges", report.getEdgeCount());
        out.put("totalWeight", report.getTotalWeight());
        out.put("mavenModules", report.getMavenModules());
        // classpath 降级可见性：jarsResolved=0 且 degraded=true 时，依赖图精度已受影响
        ClasspathResultView cp = classpathView();
        out.put("jarsResolved", cp.jars());
        out.put("classpathDegraded", cp.degraded());
        if (cp.degraded() && cp.detail() != null) {
            out.put("classpathDetail", cp.detail());
        }
        out.put("analysisPersisted", persistAnalysisPath == null ? null
                : persistAnalysisPath.toAbsolutePath().normalize().toString());
        out.put("durationMs", System.currentTimeMillis() - start);
        out.put("next", "compute_partition 计算分区建议");
        return out;
    }

    private record ClasspathResultView(int jars, boolean degraded, String detail) {}

    private ClasspathResultView classpathView() {
        return session.read(() -> {
            var r = session.classpathResult();
            if (r == null) {
                return new ClasspathResultView(0, false, null); // 未启用 maven classpath 或 load 会话
            }
            boolean degraded = r.status() != MavenClasspathResolver.Status.OK;
            return new ClasspathResultView(r.jars().size(), degraded, r.detail());
        });
    }

    Map<String, Object> loadWorkspace(Path analysisPath, Path partitionPath, Path projectDir) {
        session.loadWorkspace(analysisPath, partitionPath, projectDir);
        return partitionSummary();
    }

    Map<String, Object> computePartition(double alpha, double beta, int minSize,
                                         int maxServices, long seed) {
        session.computePartition(alpha, beta, minSize, maxServices, seed);
        Map<String, Object> summary = partitionSummary();
        summary.put("options", Map.of("alpha", alpha, "beta", beta,
                "minSize", minSize, "maxServices", maxServices, "seed", seed));
        return summary;
    }

    // ---------- 观察层 ----------

    /** 对话锚点：目标 ≤800 token。 */
    Map<String, Object> partitionSummary() {
        return session.read(() -> {
            requirePartitionedView();
            PartitionMetrics.Snapshot snapshot =
                    PartitionMetrics.of(session.graph(), session.partition());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", session.state().name());
            List<Map<String, Object>> services = new ArrayList<>();
            snapshot.communities().forEach(c -> services.add(orderedMap(
                    "id", c.id(), "name", c.name(), "size", c.size())));
            out.put("services", services);
            out.put("metrics", orderedMap(
                    "crossRatio", round4(snapshot.crossRatio()),
                    "modularity", round4(snapshot.modularity())));
            out.put("kernelSize", snapshot.kernelSize());
            out.put("totalClasses", session.report().getClassCount());
            return out;
        });
    }

    Map<String, Object> listClasses(String scope, String stereotype, int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        int effectivePage = page;
        int effectiveSize = pageSize;
        return session.read(() -> {
            requirePartitionedView();
            int serviceId = parseScope(scope);
            Stream<ClassInfo> stream = session.report().getClasses().stream()
                    .filter(c -> serviceId == -2 || session.serviceOf().getOrDefault(c.getFqcn(), -2) == serviceId);
            if (stereotype != null && !stereotype.isBlank()) {
                stream = stream.filter(c -> c.getStereotype() != null
                        && c.getStereotype().name().equalsIgnoreCase(stereotype));
            }
            List<ClassInfo> sorted = stream.sorted(Comparator.comparing(ClassInfo::getFqcn)).toList();
            int from = Math.min((effectivePage - 1) * effectiveSize, sorted.size());
            int to = Math.min(from + effectiveSize, sorted.size());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ClassInfo c : sorted.subList(from, to)) {
                rows.add(orderedMap(
                        "fqcn", c.getFqcn(),
                        "simpleName", c.getSimpleName(),
                        "stereotype", c.getStereotype() == null ? "OTHER" : c.getStereotype().name(),
                        "mavenModule", c.getMavenModule()));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", sorted.size());
            out.put("page", effectivePage);
            out.put("pageSize", effectiveSize);
            out.put("truncated", to < sorted.size());
            out.put("classes", rows);
            return out;
        });
    }

    Map<String, Object> classInfo(String fqcn) {
        return session.read(() -> {
            requirePartitionedView();
            ClassInfo info = session.report().getClasses().stream()
                    .filter(c -> c.getFqcn().equals(fqcn)).findFirst().orElse(null);
            if (info == null) {
                throw session.unknownClass(fqcn);
            }
            Integer service = session.serviceOf().get(fqcn);
            DependencyGraph graph = session.graph();

            // 出边 O(1) 查邻接表；入边一次全表扫描——避免旧实现的 4 次全表重建
            List<DependencyEdge> outs = graph.outEdges(fqcn).stream()
                    .sorted(Comparator.comparingInt(DependencyEdge::getWeight).reversed()).toList();
            List<DependencyEdge> ins = graph.inEdges(fqcn).stream()
                    .sorted(Comparator.comparingInt(DependencyEdge::getWeight).reversed()).toList();
            List<Map<String, Object>> outEdges = outs.stream().limit(EDGE_TOP_N)
                    .map(e -> edgeView(e, true)).toList();
            List<Map<String, Object>> inEdges = ins.stream().limit(EDGE_TOP_N)
                    .map(e -> edgeView(e, false)).toList();
            long outTotal = outs.size();
            long inTotal = ins.size();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fqcn", info.getFqcn());
            out.put("stereotype", info.getStereotype() == null ? "OTHER" : info.getStereotype().name());
            out.put("mavenModule", info.getMavenModule());
            out.put("superClass", info.getSuperClass());
            out.put("interfaces", info.getInterfaces());
            out.put("sourcePath", info.getSourcePath());
            out.put("currentService", service == null ? null : serviceView(service));
            out.put("outEdgesTop" + EDGE_TOP_N, outEdges);
            out.put("outEdgesTotal", outTotal);
            out.put("inEdgesTop" + EDGE_TOP_N, inEdges);
            out.put("inEdgesTotal", inTotal);
            out.put("neighborConcentration", neighborConcentration(fqcn));
            out.put("hint", "neighborConcentration 说明最强依赖的去向，是归属解释的依据；"
                    + "移动请用 move_class，targetService 填 services[].id 或 -1（共享内核）");
            return out;
        });
    }

    Map<String, Object> findClasses(String query, String stereotype) {
        String lower = query == null ? "" : query.toLowerCase();
        return session.read(() -> {
            requirePartitionedView();
            List<Map<String, Object>> matches = session.report().getClasses().stream()
                    .filter(c -> !lower.isEmpty()
                            && (c.getFqcn().toLowerCase().contains(lower)
                                || c.getSimpleName().toLowerCase().contains(lower)))
                    .filter(c -> stereotype == null || stereotype.isBlank()
                            || (c.getStereotype() == null ? "OTHER" : c.getStereotype().name())
                                    .equalsIgnoreCase(stereotype))
                    .sorted(Comparator.comparing(ClassInfo::getFqcn))
                    .limit(FIND_LIMIT)
                    .map(c -> orderedMap(
                            "fqcn", c.getFqcn(),
                            "simpleName", c.getSimpleName(),
                            "stereotype", c.getStereotype() == null ? "OTHER" : c.getStereotype().name(),
                            "service", serviceView(session.serviceOf().getOrDefault(c.getFqcn(), -2))))
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matches", matches);
            out.put("truncated", matches.size() == FIND_LIMIT);
            return out;
        });
    }

    // ---------- 编辑层 ----------

    Map<String, Object> moveClass(String fqcn, int targetService) {
        // 先查状态：指标计算访问 partition，ANALYZED 状态下为 null（曾因此 NPE 落成 E_INTERNAL）
        session.read(() -> {
            requirePartitionedView();
            return null;
        });
        PartitionMetrics.Snapshot before = session.read(() ->
                PartitionMetrics.of(session.graph(), session.partition()));
        WorkspaceSession.MoveOutcome outcome = session.moveClass(fqcn, targetService);
        PartitionMetrics.Snapshot after = session.read(() ->
                PartitionMetrics.of(session.graph(), session.partition()));

        // 移动后与目标服务之外的边 = 这次移动新付出的跨服务通信
        List<Map<String, Object>> affected = session.read(() -> session.graph().edges().stream()
                .filter(e -> (fqcn.equals(e.getFrom()) || fqcn.equals(e.getTo()))
                        && crossesService(e.getFrom(), e.getTo()))
                .sorted(Comparator.comparingInt(DependencyEdge::getWeight).reversed())
                .limit(EDGE_TOP_N)
                .map(e -> orderedMap("from", e.getFrom(), "to", e.getTo(), "weight", e.getWeight()))
                .toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", orderedMap(
                "fqcn", outcome.fqcn(),
                "from", serviceView(outcome.fromService()),
                "to", serviceView(outcome.toService())));
        out.put("metricsDelta", orderedMap(
                "crossRatio", orderedMap("before", round4(before.crossRatio()), "after", round4(after.crossRatio())),
                "modularity", orderedMap("before", round4(before.modularity()), "after", round4(after.modularity()))));
        out.put("affectedCrossEdgesTop" + EDGE_TOP_N, affected);
        out.put("undoDepth", session.read(() -> session.undoDepth()));
        return out;
    }

    Map<String, Object> undo() {
        WorkspaceSession.Edit edit = session.undo();
        Map<String, Object> restored = new LinkedHashMap<>();
        if (edit instanceof WorkspaceSession.MoveEdit m) {
            restored.put("fqcn", m.fqcn());
            restored.put("service", serviceView(m.fromService()));
        } else if (edit instanceof WorkspaceSession.RenameEdit r) {
            restored.put("serviceId", r.serviceId());
            restored.put("name", r.oldName());
        }
        return orderedMap("restored", restored);
    }

    // ---------- M2：轨迹 / 重命名 / 生成 / 落盘 / 指标 / 迁移清单 ----------

    Map<String, Object> ingestTrace(Path tracePath) {
        List<UseCase> useCases = session.ingestTrace(tracePath);
        List<Map<String, Object>> top = new ArrayList<>();
        useCases.stream().limit(5).forEach(uc -> top.add(orderedMap(
                "name", uc.getName(),
                "classes", uc.getClasses().size(),
                "edges", uc.getEdgeWeights().size())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("useCases", useCases.size());
        out.put("topUseCases", top);
        out.put("next", "compute_partition 将融合动态轨迹（双图权重 α/β）");
        return out;
    }

    Map<String, Object> renameService(int serviceId, String name) {
        session.renameService(serviceId, name);
        return session.read(() -> orderedMap(
                "serviceId", serviceId,
                "name", name.trim(),
                "note", "影响 get_partition_summary 展示与 generate 的 svc-<tag> 目录名",
                "undoDepth", session.undoDepth()));
    }

    Map<String, Object> generateServices(Path projectDir, Path outDir, boolean dryRun, boolean overwrite) {
        long start = System.currentTimeMillis();
        WorkspaceSession.GenerateOutcome outcome =
                session.generateServices(projectDir, outDir, dryRun, overwrite);
        GenerationResult r = outcome.result();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", outcome.dryRun());
        out.put("services", r.getClassesPerService().size());
        out.put("classesTotal", r.getClassesPerService().values().stream().mapToInt(Integer::intValue).sum());
        out.put("rpcBridges", r.getGeneratedBridges().size());
        out.put("injectionsRewritten", r.getRewrittenInjections().size());
        out.put("resourcesMigrated", r.getCopiedResources());
        out.put("manualItemsTotal", r.getManualItems().size());
        out.put("manualItems", r.getManualItems().stream().limit(10).toList());
        out.put("outDir", outcome.outDir().toString());
        out.put("durationMs", System.currentTimeMillis() - start);
        if (outcome.dryRun()) {
            out.put("note", "dry-run：目标目录未被写入；确认后以 dryRun=false 正式生成");
        } else {
            out.put("next", "get_migration_items 查看需人工处理的项；工程可 mvn compile 验证");
        }
        return out;
    }

    Map<String, Object> savePartition(Path path) {
        Path saved = session.savePartition(path);
        return orderedMap("saved", saved.toString(),
                "note", "当前分区（含未 undo 的编辑）已落盘，可被 CLI / web 复用");
    }

    /** 比摘要更细的数值视图：实时全局指标 + 每服务规模/跨服务通信量 + 计算期指标（标注可能过期）。 */
    Map<String, Object> metrics() {
        return session.read(() -> {
            requirePartitionedView();
            PartitionMetrics.Snapshot snapshot =
                    PartitionMetrics.of(session.graph(), session.partition());

            // 实时每服务跨服务通信量（出向/入向权重）
            Map<Integer, long[]> cross = new LinkedHashMap<>();
            session.graph().edges().forEach(e -> {
                Integer ca = session.serviceOf().get(e.getFrom());
                Integer cb = session.serviceOf().get(e.getTo());
                if (ca != null && cb != null && ca >= 0 && cb >= 0 && !ca.equals(cb)) {
                    cross.computeIfAbsent(ca, k -> new long[2])[0] += e.getWeight();
                    cross.computeIfAbsent(cb, k -> new long[2])[1] += e.getWeight();
                }
            });
            List<Map<String, Object>> perService = new ArrayList<>();
            snapshot.communities().forEach(c -> {
                long[] crossWeight = cross.getOrDefault(c.id(), new long[2]);
                perService.add(orderedMap(
                        "id", c.id(), "name", c.name(), "size", c.size(),
                        "crossOut", crossWeight[0], "crossIn", crossWeight[1]));
            });

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("crossRatio", round4(snapshot.crossRatio()));
            out.put("modularity", round4(snapshot.modularity()));
            out.put("kernelSize", snapshot.kernelSize());
            out.put("perService", perService);
            out.put("undoDepth", session.undoDepth());
            // 计算期指标（alpha/beta/用例跨度）在分区编辑后不实时重算，标注来源
            var m = session.partition().getMetrics();
            Map<String, Object> computeTime = new LinkedHashMap<>();
            computeTime.put("alpha", session.partition().getAlpha());
            computeTime.put("beta", session.partition().getBeta());
            computeTime.put("avgUseCaseSpan", m.getAvgUseCaseSpan());
            computeTime.put("imbalance", m.getImbalance());
            computeTime.put("note", "compute_partition 时的值；分区编辑后仅 crossRatio/modularity 实时");
            out.put("computeTime", computeTime);
            return out;
        });
    }

    Map<String, Object> migrationItems() {
        return session.read(() -> {
            GenerationResult r = session.generationResult();
            if (r == null) {
                throw new M2mException("E_NO_GENERATION",
                        "会话中没有有效的生成结果（尚未生成，或分区在生成后被编辑）",
                        "先（重新）调用 generate_services，再查看迁移清单");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", r.getManualItems().size());
            out.put("items", r.getManualItems().stream().limit(50).toList());
            out.put("truncated", r.getManualItems().size() > 50);
            out.put("doc", "generate 落盘时同步写入输出目录的 MIGRATION.md");
            return out;
        });
    }

    // ---------- 内部 ----------

    private void requirePartitionedView() {
        if (session.state() != WorkspaceSession.State.PARTITIONED) {
            throw new M2mException("E_NO_PARTITION", "会话中尚无分区结果",
                    "先调用 compute_partition 或 load_workspace（带 partition.json）");
        }
    }

    /** scope: "all" | "kernel" | "service:&lt;id&gt;"；返回 -2 = all，-1 = kernel，其他 = 服务 id。 */
    private int parseScope(String scope) {
        if (scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope)) {
            return -2;
        }
        if ("kernel".equalsIgnoreCase(scope)) {
            return -1;
        }
        if (scope.toLowerCase().startsWith("service:")) {
            try {
                return Integer.parseInt(scope.substring(8).trim());
            } catch (NumberFormatException e) {
                throw new M2mException("E_UNKNOWN_SERVICE", "scope 格式错误：" + scope,
                        "取值 all / kernel / service:<id>，id 见 get_partition_summary");
            }
        }
        throw new M2mException("E_UNKNOWN_SERVICE", "无法识别的 scope：" + scope,
                "取值 all / kernel / service:<id>");
    }

    private boolean crossesService(String a, String b) {
        Integer ca = session.serviceOf().get(a);
        Integer cb = session.serviceOf().get(b);
        return ca != null && cb != null && ca >= 0 && cb >= 0 && !ca.equals(cb);
    }

    private String serviceView(int id) {
        if (id == -1) {
            return "kernel";
        }
        if (id == -2) {
            return "unassigned";
        }
        PartitionResult.Community c = session.partition().getCommunities().stream()
                .filter(x -> x.getId() == id).findFirst().orElse(null);
        return c == null ? "service:" + id : "service:" + id + "(" + c.getName() + ")";
    }

    /** 最强依赖去向分布：「12/18 条边连接 service:3(user)」式的归属解释。 */
    private String neighborConcentration(String fqcn) {
        record Count(int service, int n) {}
        Map<Integer, Integer> byService = new LinkedHashMap<>();
        // 出边查邻接表 + 入边一次扫描，不重建全表
        java.util.stream.Stream.concat(
                session.graph().outEdges(fqcn).stream(),
                session.graph().inEdges(fqcn).stream())
                .forEach(e -> {
                    String other = fqcn.equals(e.getFrom()) ? e.getTo() : e.getFrom();
                    Integer s = session.serviceOf().getOrDefault(other, -2);
                    byService.merge(s, 1, Integer::sum);
                });
        int total = byService.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return "无边";
        }
        Count top = byService.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(en -> new Count(en.getKey(), en.getValue())).orElse(null);
        return top.n + "/" + total + " 条边连接 " + serviceView(top.service());
    }

    private Map<String, Object> edgeView(DependencyEdge e, boolean outgoing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(outgoing ? "to" : "from", outgoing ? e.getTo() : e.getFrom());
        m.put("service", serviceView(session.serviceOf().getOrDefault(
                outgoing ? e.getTo() : e.getFrom(), -2)));
        m.put("weight", e.getWeight());
        m.put("kinds", e.getKinds());
        if (!e.getSamples().isEmpty()) {
            m.put("sample", e.getSamples().get(0));
        }
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
