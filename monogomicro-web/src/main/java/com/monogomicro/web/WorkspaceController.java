package com.monogomicro.web;

import com.monogomicro.codegen.GenerationResult;
import com.monogomicro.codegen.MicroserviceGenerator;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;
import com.monogomicro.core.partition.PartitionMetrics;
import com.monogomicro.core.report.JsonIO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class WorkspaceController {

    private final ConsoleState state;

    public WorkspaceController(ConsoleState state) {
        this.state = state;
    }

    public record LoadRequest(String analysisPath, String partitionPath) {}

    /** audit 请求体：可选 services.json 修正自动推导的现状边界。 */
    public record AuditRequest(String servicesPath) {}

    /** 架构体检：现状边界指标 + 六类反模式（只读，不依赖分区状态）。 */
    @PostMapping("/api/audit")
    public Object audit(@RequestBody(required = false) AuditRequest req) throws Exception {
        if (!state.isLoaded()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "尚未载入数据，请先 POST /api/load");
        }
        com.monogomicro.core.audit.ServicesConfig cfg = null;
        if (req != null && req.servicesPath() != null && !req.servicesPath().isBlank()) {
            cfg = JsonIO.read(Path.of(req.servicesPath()), com.monogomicro.core.audit.ServicesConfig.class);
            cfg.validate();
        }
        com.monogomicro.core.audit.ServiceLandscape landscape =
                com.monogomicro.core.audit.LandscapeFactory.from(state.report(), cfg);
        return com.monogomicro.core.audit.ArchitectureAuditor.audit(state.graph(), landscape);
    }

    @PostMapping("/api/load")
    public Object load(@RequestBody LoadRequest req) throws Exception {
        AnalysisReport report = JsonIO.read(Path.of(req.analysisPath()), AnalysisReport.class);
        PartitionResult partition = req.partitionPath() == null || req.partitionPath().isBlank()
                ? null
                : JsonIO.read(Path.of(req.partitionPath()), PartitionResult.class);
        if (partition == null) {
            // 只给分析结果时，用纯静态分区先出一版建议
            partition = new com.monogomicro.core.partition.PartitionEngine(
                    report.toGraph(), List.of(), new com.monogomicro.core.model.PartitionOptions()).run();
        }
        Path analysisFile = Path.of(req.analysisPath()).toAbsolutePath().normalize();
        Path partitionFile = req.partitionPath() == null || req.partitionPath().isBlank()
                ? null : Path.of(req.partitionPath()).toAbsolutePath().normalize();
        // F-07：analysis 与 partition 两个输入都记录——generate 的 outDir 守卫须同时保护
        state.load(report, partition, analysisFile.toString(),
                partitionFile == null ? null : partitionFile.toString());
        // 保存根：analysis 文件所在目录（/api/save 只允许写到该目录树内，防止任意路径写）
        Path analysisDir = analysisFile.getParent();
        state.saveRoot(analysisDir != null ? analysisDir : Path.of("").toAbsolutePath().normalize());
        state.analysisPath(analysisFile);
        return state();
    }

    @GetMapping("/api/state")
    public Object state() {
        if (!state.isLoaded()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "尚未载入数据，请先 POST /api/load");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        state.report().getClasses().forEach(c -> {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", c.getFqcn());
            n.put("name", c.getSimpleName());
            n.put("module", c.getMavenModule());
            n.put("stereotype", c.getStereotype() == null ? "OTHER" : c.getStereotype().name());
            n.put("tier", c.getTier());
            n.put("community", state.labels().getOrDefault(c.getFqcn(), -1));
            nodes.add(n);
        });
        List<Map<String, Object>> links = new ArrayList<>();
        for (DependencyEdge e : state.report().getEdges()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("source", e.getFrom());
            l.put("target", e.getTo());
            l.put("weight", e.getWeight());
            l.put("kinds", e.getKinds());
            l.put("samples", e.getSamples());
            links.add(l);
        }
        List<Map<String, Object>> communities = new ArrayList<>();
        state.partition().getCommunities().forEach(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("name", c.getName());
            cm.put("size", c.getClasses().size());
            communities.add(cm);
        });
        payload.put("nodes", nodes);
        payload.put("links", links);
        payload.put("communities", communities);
        payload.put("kernel", state.partition().getKernelClasses());
        payload.put("metrics", metrics());
        return payload;
    }

    public record MoveRequest(String fqcn, int targetCommunity) {}

    @PostMapping("/api/move")
    public ResponseEntity<?> move(@RequestBody MoveRequest req) {
        try {
            state.move(req.fqcn(), req.targetCommunity());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(metrics());
    }

    /** 保存当前分区到 partition.json。目标路径必须落在 load 时 analysis 文件所在目录树内（R-04） */
    @PostMapping("/api/save")
    public ResponseEntity<?> save(@RequestParam("path") String path) throws Exception {
        if (!state.isLoaded()) {
            return ResponseEntity.status(409).body(Map.of("error", "尚未载入数据"));
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        Path saveRoot = state.saveRoot();
        if (saveRoot == null || !target.startsWith(saveRoot)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "保存路径必须位于已载入 analysis 文件所在目录内: " + saveRoot));
        }
        JsonIO.write(state.partition(), target);
        return ResponseEntity.ok(Map.of("saved", target.toString()));
    }

    public record GenerateRequest(String projectDir, String outDir, Boolean overwrite) {}

    @PostMapping("/api/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req) throws Exception {
        if (!state.isLoaded()) {
            return ResponseEntity.status(409).body(Map.of("error", "尚未载入数据"));
        }
        // 与 CLI/MCP 同一安全语义：默认不覆盖非空目录（R-01），显式传 overwrite=true 才清空重建
        boolean overwrite = req.overwrite() != null && req.overwrite();
        java.util.List<java.nio.file.Path> inputs = new java.util.ArrayList<>();
        inputs.add(state.analysisPath());
        state.inputPaths().stream().skip(1).forEach(sp -> inputs.add(java.nio.file.Path.of(sp)));
        GenerationResult result = new MicroserviceGenerator(
                state.report(), state.partition(), Path.of(req.projectDir()))
                .generate(Path.of(req.outDir()), new com.monogomicro.codegen.GenerateOptions(false, overwrite)
                        .protectedInputs(inputs.toArray(new java.nio.file.Path[0])));
        return ResponseEntity.ok(Map.of("summary", result.summary(),
                "manualItems", result.getManualItems()));
    }

    /** 指标实时重算：跨服务通信占比 + 模度 + 社区规模（计算下沉在 core.PartitionMetrics） */
    private Map<String, Object> metrics() {
        PartitionMetrics.Snapshot s = PartitionMetrics.of(state.graph(), state.partition());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("crossRatio", s.crossRatio());
        metrics.put("modularity", s.modularity());
        List<Map<String, Object>> perCommunity = new ArrayList<>();
        s.communities().forEach(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("name", c.name());
            cm.put("size", c.size());
            perCommunity.add(cm);
        });
        metrics.put("perCommunity", perCommunity);
        metrics.put("kernelSize", s.kernelSize());
        return metrics;
    }
}
