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
        state.load(report, partition);
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

    /** 保存当前分区到 partition.json */
    @PostMapping("/api/save")
    public ResponseEntity<?> save(@RequestParam String path) throws Exception {
        JsonIO.write(state.partition(), Path.of(path));
        return ResponseEntity.ok(Map.of("saved", path));
    }

    public record GenerateRequest(String projectDir, String outDir) {}

    @PostMapping("/api/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req) throws Exception {
        if (!state.isLoaded()) {
            return ResponseEntity.status(409).body(Map.of("error", "尚未载入数据"));
        }
        GenerationResult result = new MicroserviceGenerator(
                state.report(), state.partition(), Path.of(req.projectDir()))
                .generate(Path.of(req.outDir()));
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
