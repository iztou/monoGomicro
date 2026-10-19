package com.monogomicro.codegen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.monogomicro.core.M2mException;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.ClassStereotype;
import com.monogomicro.core.model.DependencyEdge;
import com.monogomicro.core.model.PartitionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 微服务工程生成编排：
 * 共享内核 -> common 模块；每个分区 -> svc-<name> 模块（源码拷贝 + 跨服务服务注入改写 + RPC 桥）；
 * 生成聚合 POM（复用原项目依赖与 BOM）、各服务 Application/application.yml、MIGRATION.md。
 * 不假装全自动：无法安全改写的跨服务依赖（Mapper/继承/静态工具/构造注入等）全部列入 MIGRATION.md。
 */
public class MicroserviceGenerator {

    private final AnalysisReport report;
    private final PartitionResult partition;
    private final Path projectDir;

    private final Map<String, ClassInfo> classIndex = new HashMap<>();
    private final Map<String, String> simpleNameToFqcn = new HashMap<>();
    private final Map<String, String> ownerOf = new HashMap<>(); // fqcn -> 模块目录名
    private final GenerationResult result = new GenerationResult();
    private PomReader pomReader;
    private com.monogomicro.core.graph.DependencyGraph graph;
    /** 非空表示源码已拷贝，后续内核扩充需同步移动文件 */
    private Path moveContext;
    /** A-05：社区 id → 去重后的 tag（唯一权威来源，目录/yml/端口/桥 key 全从这里取）。 */
    private final Map<Integer, String> communityTags = new LinkedHashMap<>();

    public MicroserviceGenerator(AnalysisReport report, PartitionResult partition, Path projectDir) {
        this.report = report;
        this.partition = partition;
        this.projectDir = projectDir.toAbsolutePath();
        for (ClassInfo c : report.getClasses()) {
            classIndex.put(c.getFqcn(), c);
            simpleNameToFqcn.putIfAbsent(c.getSimpleName(), c.getFqcn());
            ownerOf.put(c.getFqcn(), "pending");
        }
    }

    /**
     * 内核依赖闭包：common 模块不能依赖任何服务（否则循环依赖），
     * 把内核类的依赖逐层拉进内核，直到封闭。上限 40% 防止内核吞掉应用。
     */
    private void expandKernelClosure(Set<String> kernel) {
        // 初始闭包设 40% 上限；共享提升路径传 Integer.MAX_VALUE（正确性优先，比例过高的信号写入 MIGRATION.md）
        expandKernelClosure(kernel, (int) (report.getClasses().size() * 0.4));
    }

    private void expandKernelClosure(Set<String> kernel, int cap) {
        if (graph == null) {
            graph = report.toGraph();
        }
        boolean changed = true;
        while (changed && kernel.size() < cap) {
            changed = false;
            for (String fqcn : List.copyOf(kernel)) {
                for (DependencyEdge e : graph.outEdges(fqcn)) {
                    String owner = ownerOf.get(e.getTo());
                    if (owner != null && !"common".equals(owner)) {
                        ownerOf.put(e.getTo(), "common");
                        kernel.add(e.getTo());
                        partition.getCommunities().forEach(c -> c.getClasses().remove(e.getTo()));
                        // 拷贝阶段之后（moveContext 非空）：文件已落到服务目录，必须同步移动
                        if (moveContext != null) {
                            try {
                                moveClassFile(moveContext, owner, e.getTo());
                            } catch (IOException ex) {
                                result.getManualItems().add("移动类文件失败：" + e.getTo() + "（" + ex + "）");
                            }
                        }
                        changed = true;
                    }
                }
            }
        }
        partition.setKernelClasses(new ArrayList<>(kernel));
    }

    /**
     * 跨服务共享类提升：服务 A 引用服务 B 的类（枚举/工具/DTO 等非 RPC 场景）时，
     * 该类移入 common。迭代到不动点，同时保证 common 依赖封闭。已生成到服务目录的
     * 源文件物理移动到 common。
     */
    private void promoteCrossServiceShares(Path outDir, Set<String> kernel) throws IOException {
        if (graph == null) {
            graph = report.toGraph();
        }
        moveContext = outDir;
        int moved = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String fqcn : List.copyOf(ownerOf.keySet())) {
                String owner = ownerOf.get(fqcn);
                if ("common".equals(owner) || "pending".equals(owner)) {
                    continue;
                }
                for (DependencyEdge e : graph.outEdges(fqcn)) {
                    String targetOwner = ownerOf.get(e.getTo());
                    if (targetOwner == null || "common".equals(targetOwner) || targetOwner.equals(owner)) {
                        continue;
                    }
                    // SERVICE 构造型不提升：跨服务调用统一走 RPC 桥，接口与实现留在提供方服务
                    ClassInfo target = classIndex.get(e.getTo());
                    if (target != null && target.getStereotype() == ClassStereotype.SERVICE) {
                        continue;
                    }
                    // 目标在别的服务 -> 提升到 common
                    ownerOf.put(e.getTo(), "common");
                    kernel.add(e.getTo());
                    partition.getCommunities().forEach(c -> c.getClasses().remove(e.getTo()));
                    moveClassFile(outDir, targetOwner, e.getTo());
                    moved++;
                    changed = true;
                }
            }
            if (changed) {
                expandKernelClosure(kernel, Integer.MAX_VALUE); // 新成员的依赖也要封闭
            }
        }
        partition.setKernelClasses(new ArrayList<>(kernel));
        if (moved > 0) {
            double ratio = kernel.size() * 100.0 / Math.max(1, report.getClasses().size());
            result.getManualItems().add("跨服务共享类提升 " + moved + " 个到 common（共享内核最终占比 "
                    + String.format("%.0f%%", ratio) + "；偏高说明原单体耦合较重，建议先解耦再拆分）");
        }
    }

    /** 客户端类（&lt;basePackage&gt;.client.*）收敛到 common：同名去重，服务目录副本删除 */
    private void consolidateClientsToCommon(Path outDir, String basePackage) throws IOException {
        String clientDirSegment = "/src/main/java/" + basePackage.replace('.', '/') + "/client/";
        java.util.Map<String, Path> byName = new java.util.LinkedHashMap<>();
        List<Path> copies = new ArrayList<>();
        try (var walk = Files.walk(outDir, 12)) {
            walk.filter(p -> p.toString().contains(clientDirSegment)
                            && p.toString().endsWith(".java"))
                .forEach(p -> {
                    String svcDir = p.subpath(outDir.getNameCount(), outDir.getNameCount() + 1).toString();
                    if ("common".equals(svcDir)) {
                        byName.put(p.getFileName().toString(), p);
                    } else {
                        copies.add(p);
                    }
                });
        }
        for (Path copy : copies) {
            Path existing = byName.get(copy.getFileName().toString());
            if (existing == null) {
                Path target = outDir.resolve("common" + clientDirSegment)
                        .resolve(copy.getFileName().toString());
                Files.createDirectories(target.getParent());
                Files.move(copy, target);
                byName.put(copy.getFileName().toString(), target);
            } else {
                Files.deleteIfExists(copy); // 与已有版本重复（同一 provider 的镜像客户端）
            }
        }
    }

    /** 把已拷贝到服务目录的类源文件（含同名 mapper XML）移动到 common 目录 */
    private void moveClassFile(Path outDir, String fromService, String fqcn) throws IOException {
        String relPath = fqcn.replace('.', '/') + ".java";
        Path src = outDir.resolve(fromService).resolve("src/main/java").resolve(relPath);
        if (Files.isRegularFile(src)) {
            Path target = outDir.resolve("common").resolve("src/main/java").resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.move(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // mapper XML 跟随移动
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        Path resourcesRoot = outDir.resolve(fromService).resolve("src/main/resources");
        try (var walk = Files.walk(resourcesRoot)) {
            for (Path xml : walk.filter(p -> p.getFileName().toString().equals(simple + ".xml")).toList()) {
                Path xmlTarget = outDir.resolve("common").resolve("src/main/resources")
                        .resolve(resourcesRoot.relativize(xml).toString());
                Files.createDirectories(xmlTarget.getParent());
                Files.move(xml, xmlTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.nio.file.NoSuchFileException ignore) {
            // 服务没有 resources 目录
        }
    }

    public GenerationResult generate(Path out) throws Exception {
        return generate(out, GenerateOptions.legacy());
    }

    /**
     * 带选项生成（MCP / 程序化调用入口）：
     * dryRun=true —— 完整生成在临时目录执行后清理，只返回统计与迁移清单，目标目录不受任何影响；
     * dryRun=false 且 overwrite=false 时，目标目录已存在且非空报 E_OUTPUT_EXISTS 而非清空。
     *
     * 所有入口共用 {@link #guardOutDir}：目标目录将被清空重建，空路径 / 当前工作目录 /
     * 原项目根 / 包含它们的祖先目录一律在删除前拒绝（R-01：曾在输入校验失败前就删了目标目录）。
     *
     * 注意：本类持有一次性生成状态（ownerOf/result 等），同一实例只能成功执行一次 generate；
     * dry-run 摸底后再正式生成，请用新实例调 dryRun=false。
     */
    public GenerationResult generate(Path out, GenerateOptions options) throws Exception {
        Path outDir = out.toAbsolutePath().normalize();
        guardOutDir(outDir);
        if (options.isDryRun()) {
            Path tempDir = Files.createTempDirectory("m2m-dryrun-");
            try {
                GenerationResult dry = doGenerate(tempDir);
                dry.setOutputDir(outDir); // 汇报目标位置，而不是临时目录
                return dry;
            } finally {
                try {
                    deleteRecursively(tempDir);
                } catch (IOException e) {
                    // 临时目录清理失败不影响结果，系统临时目录由 OS 兜底
                }
            }
        }
        if (!options.isOverwrite() && isNonEmptyDir(outDir)) {
            throw new M2mException("E_OUTPUT_EXISTS",
                    "目标目录已存在且非空：" + outDir,
                    "确认内容可丢弃后置 overwrite=true（将清空重建），或更换输出目录");
        }
        return doGenerate(outDir);
    }

    /**
     * 输出目录守卫（删除前拒绝危险路径）：空路径（等价当前工作目录）、当前工作目录、
     * 原项目根、以及包含二者任一的祖先目录。正常用法（项目内子目录 / 独立目录）不受影响。
     */
    private void guardOutDir(Path outDir) {
        String s = outDir.toString();
        if (s.isBlank() || s.equals(".")) {
            throw new M2mException("E_BAD_OUT_DIR",
                    "输出目录不能为空（生成会清空重建，空路径等于当前工作目录）",
                    "指定明确的输出目录，如 <project>/m2m-out/services");
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (outDir.equals(cwd)) {
            throw new M2mException("E_BAD_OUT_DIR", "输出目录不能是当前工作目录：" + outDir,
                    "指定一个专用的输出子目录");
        }
        Path project = projectDir.toAbsolutePath().normalize();
        if (outDir.equals(project)) {
            throw new M2mException("E_BAD_OUT_DIR", "输出目录不能是原项目根：" + outDir,
                    "生成会清空重建该目录、删除全部源码；指定项目外或项目内子目录");
        }
        if (project.startsWith(outDir) || cwd.startsWith(outDir)) {
            throw new M2mException("E_BAD_OUT_DIR",
                    "输出目录包含原项目或当前工作目录，拒绝清空：" + outDir,
                    "输出目录必须是原项目/工作目录的子目录，或完全独立的目录");
        }
        if (Files.exists(outDir) && !Files.isDirectory(outDir)) {
            // A-07：-o 指向普通文件曾绕过 isNonEmptyDir 后被删除
            throw new M2mException("E_BAD_OUT_DIR", "输出路径已存在且不是目录：" + outDir,
                    "生成输出必须是目录；该位置已有文件，请更换输出目录");
        }
        // A-20：符号链接穿透——文本 normalize 比较看不出中间 symlink 指向项目，
        // toRealPath 解析链接后再比对；同时拒绝删除项目的源码树本身
        try {
            Path realOut = Files.exists(outDir) ? outDir.toRealPath() : outDir.toRealPath().getParent();
            Path realProject = project.toAbsolutePath().normalize().toRealPath();
            if (realProject.startsWith(realOut) || realOut.startsWith(realProject.resolve("src"))) {
                throw new M2mException("E_BAD_OUT_DIR",
                        "输出目录（经符号链接解析后）指向原项目或其源码树：" + realOut,
                        "更换输出目录；符号链接路径不会被用作清空目标");
            }
        } catch (java.io.IOException resolveFailed) {
            // 解析失败（含目标不存在）：上面的 normalize 守卫已覆盖主路径，这里不放大异常
        }
    }

    private static boolean isNonEmptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        }
    }

    private GenerationResult doGenerate(Path outDir) throws Exception {
        // 顺序纪律（R-01）：先完成所有会让生成失败的输入校验（POM 解析等），再删除目标目录——
        // 顺序颠倒会"校验失败前已把目标目录清空"
        pomReader = new PomReader();
        pomReader.readProject(projectDir);

        deleteRecursively(outDir);
        Files.createDirectories(outDir);
        result.setOutputDir(outDir);

        // 归属关系：内核 -> common，其余 -> svc-<name>
        Set<String> kernel = new LinkedHashSet<>(partition.getKernelClasses());
        // A-02：@MapperScan 等全局配置类（stereotype=CONFIG 且声明了 MapperScan）强制提升进
        // common——否则只落在一个服务里，其余服务启动即 No qualifying bean mapper
        boolean promotedConfig = false;
        for (PartitionResult.Community c : partition.getCommunities()) {
            for (String fqcn : new ArrayList<>(c.getClasses())) {
                if (isMapperScanConfig(fqcn)) {
                    c.getClasses().remove(fqcn);
                    if (kernel.add(fqcn)) {
                        promotedConfig = true;
                        result.getManualItems().add("全局配置类 " + fqcn + "（@MapperScan）已提升进 common；"
                                + "各服务的 datasource/redis 配置仍需按 MIGRATION.md 人工迁移");
                    }
                }
            }
        }
        if (promotedConfig) {
            // 配置类进了 kernel，但其所属社区若因此变空需在后续归并中自然处理
        }
        List<String[]> serviceDirs = new ArrayList<>(); // {目录名, 社区名}
        List<String> moduleTags = new ArrayList<>();
        for (String fqcn : kernel) {
            ownerOf.put(fqcn, "common");
        }
        String basePackage = commonPackagePrefix();
        // A-05：tag 只算一次（含冲突去重），目录/yml key/端口/桥 provider key 全部从这张表取——
        // 此前 copyClasses/yml/portOfTag 各自重算 serviceTag，去重只作用于 serviceDirs 导致两社区合并
        for (PartitionResult.Community c : partition.getCommunities()) {
            String tag = serviceTag(c.getName(), c.getId());
            while (communityTags.containsValue(tag)) {
                tag = tag + "-" + c.getId();
            }
            communityTags.put(c.getId(), tag);
        }
        for (PartitionResult.Community c : partition.getCommunities()) {
            String tag = communityTags.get(c.getId());
            String dir = "svc-" + tag;
            serviceDirs.add(new String[]{dir, c.getName()});
            moduleTags.add(dir);
            for (String fqcn : c.getClasses()) {
                ownerOf.put(fqcn, dir);
            }
        }

        // 1. 探测跨服务注入（消费类 -> provider 接口），把 provider 接口签名引用的
        //    第三方服务类型提前提升进共享内核（共享 DTO 提取，Mono2Micro 同样做法）
        Map<String, Set<String>> consumerToProviders = detectCrossServiceInjections(kernel);

        // 1.5 内核依赖闭包：保证 common 不依赖任何服务，否则生成工程无法编译
        int kernelBefore = kernel.size();
        expandKernelClosure(kernel);
        if (kernel.size() > kernelBefore) {
            result.getManualItems().add("共享内核经依赖闭包扩充 " + (kernel.size() - kernelBefore)
                    + " 个类（common 依赖的类必须同在 common，防止循环依赖）");
        }

        // 2. 拷贝类源码（内核 + 各服务）
        copyClasses(kernel, outDir.resolve("common"), result.getManualItems());
        for (PartitionResult.Community c : partition.getCommunities()) {
            String dir = "svc-" + communityTags.get(c.getId());
            Path serviceRoot = outDir.resolve(dir);
            copyClasses(c.getClasses(), serviceRoot, result.getManualItems());
            result.getClassesPerService().put(dir, c.getClasses().size());
        }

        // 3. 拷贝 MyBatis mapper XML（按 Mapper 类归属匹配文件名）
        result.setCopiedResources(copyMapperXml(outDir));

        // 4. 跨服务共享类提升（终态归属）：服务 A 引用服务 B 的类（枚举/工具/DTO 等）
        //     物理移入 common——服务间不允许类级依赖，否则工程无法编译。
        //     必须在 RPC 桥生成之前：桥的方法可见性判定依赖最终归属。
        promoteCrossServiceShares(outDir, kernel);

        // 5. 跨服务依赖处理：服务接口字段注入 -> RPC 桥；其余 -> 人工清单
        rewriteCrossServiceCalls(outDir, basePackage, consumerToProviders);

        // 5.5 生成的 RPC 客户端统一收敛到 common（去重）——common 里的类也可能引用客户端，
        //     且同名客户端会随多个消费服务重复生成，必须只保留一份
        consolidateClientsToCommon(outDir, basePackage);

        // 4. 每个服务：Application + application.yml + pom
        int port = 8081;
        for (String[] svc : serviceDirs) {
            String dir = svc[0];
            writeApplicationClass(outDir.resolve(dir), dir, basePackage);
            writeApplicationYml(outDir.resolve(dir), dir, port, providersUsedBy(dir));
            port++;
        }

        // 5. POM 们
        writePoms(outDir, moduleTags, basePackage);

        // 6. MIGRATION.md
        writeMigrationDoc(outDir, serviceDirs);
        return result;
    }

    // ---------- 归属与命名 ----------

    private String serviceTag(String communityName, int id) {
        String tag = communityName == null ? "" : communityName;
        tag = tag.replaceAll("(?i)-?service$", "").replaceAll("[^a-zA-Z0-9-]", "");
        if (tag.isEmpty()) {
            tag = "svc" + id;
        }
        return tag.toLowerCase();
    }

    /** 全部类的最长公共包前缀（Application 放这里，保证组件扫描覆盖） */
    private String commonPackagePrefix() {
        String prefix = null;
        for (ClassInfo c : report.getClasses()) {
            String pkg = c.getPackageName();
            prefix = prefix == null ? pkg : commonPrefix(prefix, pkg);
        }
        if (prefix == null || prefix.isEmpty()) {
            prefix = "com.generated";
        }
        return prefix;
    }

    private String commonPrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int i = 0;
        while (i < pa.length && i < pb.length && pa[i].equals(pb[i])) {
            i++;
        }
        return i == 0 ? "" : String.join(".", java.util.Arrays.copyOfRange(pa, 0, i));
    }

    // ---------- 拷贝 ----------

    private void copyClasses(Iterable<String> fqcns, Path moduleRoot, List<String> manual) throws IOException {
        java.util.Set<Path> copiedFiles = new java.util.HashSet<>();
        for (String fqcn : fqcns) {
            ClassInfo info = classIndex.get(fqcn);
            if (info == null || info.getSourcePath() == null) {
                continue;
            }
            Path src = projectDir.resolve(info.getSourcePath());
            if (!Files.isRegularFile(src)) {
                manual.add("找不到源文件，需人工迁移：" + fqcn + "（" + info.getSourcePath() + "）");
                continue;
            }
            if (isOriginalBootApplication(src) || referencesSkippedBootstrap(src)) {
                // A-01：原启动类与生成的 M2m*Application 双 main 会让 package 失败；
                // 引用启动类的伴生入口（如 RuoYiServletInitializer extends SpringBootServletInitializer
                // 且 .sources(RuoYiApplication.class)）一并跳过，否则编译不过
                skippedBootstrapSimpleNames.add(info.getSimpleName());
                manual.add("原启动/部署类 " + fqcn + " 未拷贝（各服务有自己的 Application）；"
                        + "其 @MapperScan/exclude 等注解与 datasource/mybatis 配置需按 MIGRATION.md 人工迁移");
                continue;
            }
            if (!copiedFiles.add(src.toAbsolutePath())) {
                continue; // 一个文件多个顶层类型：只拷一次，避免类重复
            }
            // A-04：fqcn 来自可编辑的 analysis.json——包名必须是合法 Java 标识符序列，
            // 从源头杜绝 "../" 逃逸；sourcePath 必须相对且不越出 projectDir
            if (!isValidPackageName(info.getPackageName())) {
                manual.add("非法包名，拒绝拷贝（防路径逃逸）：" + fqcn + "（packageName=" + info.getPackageName() + "）");
                continue;
            }
            Path relSource = Path.of(info.getSourcePath());
            if (relSource.isAbsolute() || relSource.normalize().startsWith("..")) {
                manual.add("sourcePath 必须是项目内相对路径，拒绝拷贝：" + fqcn + "（" + info.getSourcePath() + "）");
                continue;
            }
            // 保留原文件名：一个文件可含多个顶层类型（仅主类型可 public），改名会破坏 JLS
            Path target = moduleRoot.resolve("src/main/java")
                    .resolve(info.getPackageName().replace('.', '/'))
                    .resolve(src.getFileName().toString());
            if (!target.normalize().startsWith(moduleRoot.resolve("src/main/java").normalize())) {
                manual.add("目标路径越出模块，拒绝拷贝：" + fqcn);
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(src, target);
        }
    }

    /** 合法 Java 包名（标识符序列），从源头封死路径段（../、绝对路径片段等）。 */
    private static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    }

    /** mapper XML 按「文件名 == Mapper 类简单名」匹配到对应服务，保留 mapper/... 相对路径 */
    private int copyMapperXml(Path outDir) throws IOException {
        int copied = 0;
        List<Path> xmls = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir, 8)) {
            walk.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.toString().contains("/mapper/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(xmls::add);
        }
        for (Path xml : xmls) {
            String simple = xml.getFileName().toString().replaceFirst("\\.xml$", "");
            String fqcn = simpleNameToFqcn.get(simple);
            String owner = fqcn == null ? null : ownerOf.get(fqcn);
            if (owner == null) {
                continue;
            }
            // 目标路径：resources 下从 mapper/ 开始的相对路径。
            // 不用正则剥「模块名/src/main/resources/」前缀——单模块项目没有该前缀会剥错；
            // 直接定位路径中最后一个 mapper 段取其后相对路径（入口过滤已保证路径含 /mapper/）
            Path rel = null;
            for (int i = xml.getNameCount() - 2; i >= 0; i--) {
                if ("mapper".equals(xml.getName(i).toString())) {
                    rel = xml.subpath(i, xml.getNameCount());
                    break;
                }
            }
            if (rel == null) {
                continue;
            }
            Path target = outDir.resolve(owner).resolve("src/main/resources").resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(xml, target);
            copied++;
        }
        return copied;
    }

    // ---------- 跨服务改写 ----------

    /** 探测跨服务服务接口字段注入；并把 provider 签名引用的非本服务类型提升进内核 */
    private Map<String, Set<String>> detectCrossServiceInjections(Set<String> kernel) {
        Map<String, Set<String>> consumerToProviders = new LinkedHashMap<>();
        for (DependencyEdge edge : report.getEdges()) {
            String fromOwner = ownerOf.get(edge.getFrom());
            String toOwner = ownerOf.get(edge.getTo());
            if (fromOwner == null || toOwner == null || fromOwner.equals(toOwner) || "common".equals(toOwner)) {
                continue;
            }
            ClassInfo target = classIndex.get(edge.getTo());
            if (target != null && target.getStereotype() == ClassStereotype.SERVICE
                    && edge.kindSet().contains(DependencyEdge.EdgeKind.FIELD)) {
                consumerToProviders.computeIfAbsent(edge.getFrom(), k -> new LinkedHashSet<>())
                        .add(edge.getTo());
            }
        }
        // 共享 DTO 提取：签名里的第三方服务类型 -> common，消费/提供两端都可达
        for (Set<String> providers : consumerToProviders.values()) {
            for (String providerFqcn : providers) {
                String providerOwner = ownerOf.get(providerFqcn);
                for (String typeFqcn : signatureTypesOf(providerFqcn)) {
                    String typeOwner = ownerOf.get(typeFqcn);
                    if (typeOwner != null && !"common".equals(typeOwner) && !typeOwner.equals(providerOwner)) {
                        ownerOf.put(typeFqcn, "common");
                        kernel.add(typeFqcn);
                        partition.getCommunities().forEach(c -> c.getClasses().remove(typeFqcn));
                        result.getManualItems().add("自动提升到共享内核（跨服务接口签名引用）："
                                + typeFqcn + "（原属 " + typeOwner + "）");
                    }
                }
            }
        }
        partition.setKernelClasses(new ArrayList<>(kernel));
        return consumerToProviders;
    }

    /** 解析一个接口源码，返回签名引用的项目类型 FQCN 集合 */
    private Set<String> signatureTypesOf(String providerFqcn) {
        Set<String> fqcms = new LinkedHashSet<>();
        ClassInfo info = classIndex.get(providerFqcn);
        if (info == null || info.getSourcePath() == null) {
            return fqcms;
        }
        Path src = projectDir.resolve(info.getSourcePath());
        if (!Files.isRegularFile(src)) {
            return fqcms;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(src);
            if (cu.getTypes().isEmpty() || !cu.getType(0).isClassOrInterfaceDeclaration()) {
                return fqcms;
            }
            java.util.regex.Pattern id = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
            for (var m : cu.getType(0).asClassOrInterfaceDeclaration().getMethods()) {
                collectProjectIds(m.getType().asString(), id, fqcms);
                for (var p : m.getParameters()) {
                    collectProjectIds(p.getType().asString(), id, fqcms);
                }
            }
        } catch (Exception e) {
            result.getManualItems().add("无法解析接口签名：" + providerFqcn + "（" + e.getMessage() + "）");
        }
        return fqcms;
    }

    private void collectProjectIds(String type, java.util.regex.Pattern id, Set<String> out) {
        java.util.regex.Matcher matcher = id.matcher(type);
        while (matcher.find()) {
            String fqcn = simpleNameToFqcn.get(matcher.group());
            if (fqcn != null) {
                out.add(fqcn);
            }
        }
    }

    private void rewriteCrossServiceCalls(Path outDir, String basePackage,
                                          Map<String, Set<String>> consumerToProviders) throws IOException {
        RpcBridgeGenerator bridge = new RpcBridgeGenerator(simpleNameToFqcn, ownerOf::get, result.getManualItems());
        Map<String, DependencyEdge> manualEdges = new LinkedHashMap<>();

        for (DependencyEdge edge : report.getEdges()) {
            String fromOwner = ownerOf.get(edge.getFrom());
            String toOwner = ownerOf.get(edge.getTo());
            if (fromOwner == null || toOwner == null || fromOwner.equals(toOwner) || "common".equals(toOwner)) {
                continue;
            }
            ClassInfo target = classIndex.get(edge.getTo());
            boolean bridged = target != null && target.getStereotype() == ClassStereotype.SERVICE
                    && edge.kindSet().contains(DependencyEdge.EdgeKind.FIELD)
                    && consumerToProviders.getOrDefault(edge.getFrom(), Set.of()).contains(edge.getTo());
            if (!bridged) {
                String key = edge.getFrom() + " -> " + edge.getTo();
                manualEdges.merge(key, edge, (a, b) -> a.getCount() >= b.getCount() ? a : b);
            }
        }

        // 生成桥 + 改写消费端源码
        for (Map.Entry<String, Set<String>> e : consumerToProviders.entrySet()) {
            String consumer = e.getKey();
            String consumerOwner = ownerOf.get(consumer);
            Set<String> bridgedProviders = new LinkedHashSet<>();
            for (String providerFqcn : e.getValue()) {
                String providerOwner = ownerOf.get(providerFqcn);
                ClassInfo providerInfo = classIndex.get(providerFqcn);
                Path providerSrc = projectDir.resolve(providerInfo.getSourcePath());
                if (!Files.isRegularFile(providerSrc)) {
                    result.getManualItems().add("找不到 provider 源文件：" + providerFqcn);
                    continue;
                }
                RpcBridgeGenerator.BridgeRequest req = new RpcBridgeGenerator.BridgeRequest();
                req.consumerService = consumerOwner;
                req.providerService = providerOwner;
                req.providerInterfaceFqcn = providerFqcn;
                req.providerInterfaceSource = Files.readString(providerSrc, StandardCharsets.UTF_8);
                // A-05：桥的 provider key 与 yml/目录同源（communityTags），不再独立 sanitize
                req.providerNameKey = tagOfDir(providerOwner);
                Path consumerSrcRoot = outDir.resolve(consumerOwner).resolve("src/main/java");
                Path providerSrcRoot = outDir.resolve(providerOwner).resolve("src/main/java");
                if (bridge.generate(req, consumerSrcRoot, providerSrcRoot, basePackage)) {
                    result.getGeneratedBridges().add(consumerOwner + " -> " + providerOwner
                            + "（" + providerInfo.getSimpleName() + "）");
                    bridgedProviders.add(providerFqcn);
                }
            }
            // 只改写真正生成客户端的注入，避免引用不存在的客户端类
            if (!bridgedProviders.isEmpty()) {
                rewriteInjections(outDir.resolve(consumerOwner), consumer, bridgedProviders);
            }
        }

        for (Map.Entry<String, DependencyEdge> e : manualEdges.entrySet()) {
            DependencyEdge edge = e.getValue();
            String desc = e.getKey() + "  [" + String.join("/", edge.getKinds()) + "]";
            if (!edge.getSamples().isEmpty()) {
                desc += "  位置示例：" + edge.getSamples().get(0);
            }
            result.getManualItems().add("跨服务依赖未自动改写：" + desc);
        }
    }

    /** 目录名（svc-xxx）→ 去重 tag（去掉 svc- 前缀）。 */
    private String tagOfDir(String ownerDir) {
        return ownerDir.replaceFirst("^svc-", "");
    }

    private String partitionName(String ownerDir) {
        for (PartitionResult.Community c : partition.getCommunities()) {
            if (("svc-" + communityTags.get(c.getId())).equals(ownerDir)) {
                return c.getName();
            }
        }
        return ownerDir;
    }

    /** 把消费类中对跨服务接口的字段注入替换为生成客户端（客户端包 = 公共前缀.client，与桥生成器一致） */
    private void rewriteInjections(Path serviceRoot, String consumerFqcn, Set<String> providerFqcns) throws IOException {
        Path target = serviceRoot.resolve("src/main/java")
                .resolve(consumerFqcn.replace('.', '/') + ".java");
        if (!Files.isRegularFile(target)) {
            return;
        }
        CompilationUnit cu = StaticJavaParser.parse(target);
        boolean changed = false;
        for (String providerFqcn : providerFqcns) {
            String providerSimple = providerFqcn.substring(providerFqcn.lastIndexOf('.') + 1);
            String clientSimple = providerSimple + "Client";
            String clientImport = commonPackagePrefix() + ".client." + clientSimple;
            boolean touched = false;
            for (com.github.javaparser.ast.body.FieldDeclaration field
                    : cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
                String typeStr = field.getElementType().asString();
                if (providerSimple.equals(rawTypeName(typeStr))) {
                    field.getVariable(0).setType(clientSimple);
                    touched = true;
                }
            }
            // 构造器/方法/lambda 参数类型同步改写——构造器注入（Spring 推荐风格）只改字段必炸编译
            for (com.github.javaparser.ast.body.Parameter param
                    : cu.findAll(com.github.javaparser.ast.body.Parameter.class)) {
                String typeStr = param.getType().asString();
                if (providerSimple.equals(rawTypeName(typeStr))) {
                    param.setType(clientSimple);
                    touched = true;
                }
            }
            if (touched) {
                result.getRewrittenInjections().add(consumerFqcn + " 中 " + providerSimple
                        + " -> " + clientSimple);
                cu.addImport(clientImport);
                // 原接口的 import 指向别的服务里的类，残留必炸编译——移除
                cu.getImports().removeIf(imp -> imp.getNameAsString().equals(providerFqcn)
                        || imp.getNameAsString().endsWith("." + providerSimple));
                changed = true;
            }
        }
        if (changed) {
            Files.writeString(target, cu.toString(), StandardCharsets.UTF_8);
        }
    }

    private String rawTypeName(String type) {
        int lt = type.indexOf('<');
        return lt > 0 ? type.substring(0, lt) : type;
    }

    // ---------- 服务工程文件 ----------

    /** 生成的启动类全限定名（dir → fqcn），供 POM 显式 mainClass 使用（A-01 双保险）。 */
    private final Map<String, String> generatedMainClasses = new LinkedHashMap<>();
    /** 被跳过的原启动/部署类简单名——引用它们的其他类（如 ServletInitializer）也必须跳过。 */
    private final java.util.Set<String> skippedBootstrapSimpleNames = new java.util.HashSet<>();

    /**
     * 检测源文件是否是原单体启动类（@SpringBootApplication 或独立 main 方法所在的
     * Boot 应用入口）。生成的服务有自己的 M2m*Application，原启动类若被拷进同一模块，
     * spring-boot-maven-plugin 会因双 main 而 package 失败（A-01）。
     */
    /** @Configuration + @MapperScan 的全局配置类（跨服务基础设施，必须人人可见）。 */
    private boolean isMapperScanConfig(String fqcn) {
        ClassInfo info = classIndex.get(fqcn);
        if (info == null || info.getSourcePath() == null) {
            return false;
        }
        try {
            String text = Files.readString(projectDir.resolve(info.getSourcePath()));
            return text.contains("@MapperScan");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isOriginalBootApplication(Path src) {
        try {
            String text = Files.readString(src);
            return text.contains("@SpringBootApplication")
                    || text.contains("extends SpringBootServletInitializer");
        } catch (IOException e) {
            return false;
        }
    }

    /** 引用了任一被跳过启动类的源文件（编译依赖完整性：缺符号即编译失败）。 */
    private boolean referencesSkippedBootstrap(Path src) {
        if (skippedBootstrapSimpleNames.isEmpty()) {
            return false;
        }
        try {
            String text = Files.readString(src);
            return skippedBootstrapSimpleNames.stream().anyMatch(n -> text.contains(n));
        } catch (IOException e) {
            return false;
        }
    }

    private void writeApplicationClass(Path serviceRoot, String dir, String basePackage) throws IOException {
        String tag = dir.replaceFirst("^svc-", "");
        String camel = camelCase(tag);
        String appName = "M2m" + camel + "Application";
        String content = """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class %s {
                    public static void main(String[] args) {
                        SpringApplication.run(%s.class, args);
                    }
                }
                """.formatted(basePackage, appName, appName);
        Path file = serviceRoot.resolve("src/main/java")
                .resolve(basePackage.replace('.', '/') + "/" + appName + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        generatedMainClasses.put(dir, basePackage + "." + appName);
    }

    /** 该服务消费了哪些服务（用于生成 base-url 配置） */
    private List<String> providersUsedBy(String dir) {
        List<String> providers = new ArrayList<>();
        for (var bridge : result.getGeneratedBridges()) {
            int sep = bridge.indexOf(" -> ");
            if (sep > 0 && bridge.substring(0, sep).equals(dir)) {
                providers.add(bridge.substring(sep + 4).replaceAll("[（(].*$", "").trim());
            }
        }
        return providers;
    }

    private void writeApplicationYml(Path serviceRoot, String dir, int port, List<String> providers)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("server:\n  port: ").append(port).append("\n");
        sb.append("spring:\n  application:\n    name: ").append(dir).append("\n");
        // 客户端统一在 common，组件扫描会实例化所有客户端，因此每个服务都要有全部对端地址。
        // 自己也不能跳过：本服务的 client bean 同样引用 ${svc.<自己>.base-url} 占位符，
        // 缺了会直接启动失败（Could not resolve placeholder）；指向自己的地址不会被调用，无害
        StringBuilder svcSection = new StringBuilder();
        for (PartitionResult.Community c : partition.getCommunities()) {
            String tag = communityTags.get(c.getId());
            svcSection.append("  ").append(tag).append(":\n    base-url: http://localhost:")
                    .append(portOfTag(tag)).append("\n");
        }
        if (svcSection.length() > 0) {
            sb.append("svc:\n").append(svcSection);
        }
        Path file = serviceRoot.resolve("src/main/resources/application.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private String portOfTag(String tag) {
        // A-05：端口按 communityTags 的稳定顺序分配，与目录/yml 同源
        int port = 8081;
        for (String t : communityTags.values()) {
            if (t.equals(tag)) {
                return String.valueOf(port);
            }
            port++;
        }
        return "8081";
    }

    // ---------- POM 生成 ----------

    private void writePoms(Path outDir, List<String> serviceDirs, String basePackage) throws IOException {
        String group = pomReader.projectGroupId();
        String version = pomReader.projectVersion();
        String rootName = projectDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+$", "");
        String aggArtifact = rootName + "-microservices";

        StringBuilder modules = new StringBuilder();
        modules.append("        <module>common</module>\n");
        for (String dir : serviceDirs) {
            modules.append("        <module>").append(dir).append("</module>\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("    <groupId>").append(xml(group)).append("</groupId>\n");
        sb.append("    <artifactId>").append(xml(aggArtifact)).append("</artifactId>\n");
        sb.append("    <version>").append(xml(version)).append("</version>\n");
        sb.append("    <packaging>pom</packaging>\n");
        sb.append("    <modules>\n").append(modules).append("    </modules>\n");
        if (!pomReader.properties().isEmpty()) {
            sb.append("    <properties>\n");
            for (var e : pomReader.properties().entrySet()) {
                sb.append("        <").append(e.getKey()).append(">").append(xml(e.getValue()))
                        .append("</").append(e.getKey()).append(">\n");
            }
            sb.append("    </properties>\n");
        }
        if (!pomReader.dependencyManagement().isEmpty()) {
            sb.append("    <dependencyManagement>\n        <dependencies>\n");
            for (PomReader.Dep d : pomReader.dependencyManagement().values()) {
                emitDep(sb, d, "            ");
            }
            sb.append("        </dependencies>\n    </dependencyManagement>\n");
        }
        sb.append("</project>\n");
        Files.writeString(outDir.resolve("pom.xml"), sb.toString(), StandardCharsets.UTF_8);

        String deps = externalDepsXml();

        // common
        writeModulePom(outDir.resolve("common"), group, aggArtifact, aggArtifact + "-common",
                version, deps, false, null);

        // services
        String commonDep = """
                    <dependency>
                        <groupId>%s</groupId>
                        <artifactId>%s-common</artifactId>
                        <version>%s</version>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                """.formatted(xml(group), xml(aggArtifact), xml(version));
        for (String dir : serviceDirs) {
            writeModulePom(outDir.resolve(dir), group, aggArtifact, dir, version,
                    commonDep + deps, true, generatedMainClasses.get(dir));
        }
    }

    private String externalDepsXml() {
        StringBuilder sb = new StringBuilder();
        for (PomReader.Dep d : pomReader.externalDependencies()) {
            emitDep(sb, d, "            ");
        }
        return sb.toString();
    }

    private void writeModulePom(Path moduleDir, String group, String aggArtifact, String artifact,
                               String version, String depsXml, boolean bootPlugin,
                               String mainClass) throws IOException {
        // 空模块（如无内核类拷贝的 common）没有前置步骤建目录，这里兜底
        Files.createDirectories(moduleDir);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("    <parent>\n");
        sb.append("        <groupId>").append(xml(group)).append("</groupId>\n");
        sb.append("        <artifactId>").append(xml(aggArtifact)).append("</artifactId>\n");
        sb.append("        <version>").append(xml(version)).append("</version>\n");
        sb.append("    </parent>\n");
        sb.append("    <artifactId>").append(xml(artifact)).append("</artifactId>\n");
        sb.append("    <dependencies>\n").append(depsXml).append("    </dependencies>\n");
        // -parameters：Spring Boot 3.2+ 的 @RequestParam/@PathVariable 依赖参数名反射，
        // 没有它所有 Controller 运行时炸 IllegalArgumentException（spring-boot-starter-parent
        // 默认开启，但生成工程无 parent 继承，必须自带）
        sb.append("    <build>\n        <plugins>\n");
        sb.append("            <plugin>\n");
        sb.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("                <artifactId>maven-compiler-plugin</artifactId>\n");
        sb.append("                <version>3.13.0</version>\n");
        // A-18：固定编译级别——插件默认 1.8，原单体用 record/var/text block 时生成工程编译失败
        sb.append("                <configuration><parameters>true</parameters><release>")
                .append(compilerRelease()).append("</release></configuration>\n");
        sb.append("            </plugin>\n");
        if (bootPlugin) {
            String pluginVersion = springBootPluginVersion();
            sb.append("            <plugin>\n");
            sb.append("                <groupId>org.springframework.boot</groupId>\n");
            sb.append("                <artifactId>spring-boot-maven-plugin</artifactId>\n");
            if (pluginVersion != null) {
                sb.append("                <version>").append(xml(pluginVersion)).append("</version>\n");
            }
            // A-01 双保险：显式 mainClass——即使原启动类被意外拷入也不会双 main 失败
            if (mainClass != null) {
                sb.append("                <configuration><mainClass>").append(xml(mainClass))
                        .append("</mainClass></configuration>\n");
            }
            // 显式绑定 repackage：无 parent 继承时插件不自动执行，产物是薄 jar，
            // java -jar 报"没有主清单属性"
            sb.append("                <executions>\n");
            sb.append("                    <execution>\n");
            sb.append("                        <goals><goal>repackage</goal></goals>\n");
            sb.append("                    </execution>\n");
            sb.append("                </executions>\n");
            sb.append("            </plugin>\n");
        }
        sb.append("        </plugins>\n    </build>\n");
        sb.append("</project>\n");
        Files.writeString(moduleDir.resolve("pom.xml"), sb.toString(), StandardCharsets.UTF_8);
    }

    /** 插件版本取原项目属性表或 BOM 导入里的 spring-boot 版本 */
    /** A-18：编译级别取原项目 maven.compiler.release/java.version 属性，缺省 17。 */
    private String compilerRelease() {
        for (String key : List.of("maven.compiler.release", "java.version")) {
            String v = pomReader.properties().get(key);
            if (v != null && v.matches("[0-9]+")) {
                return v;
            }
        }
        return "17";
    }

    private String springBootPluginVersion() {
        for (var e : pomReader.properties().entrySet()) {
            if (e.getKey().toLowerCase().contains("spring-boot") && e.getKey().toLowerCase().contains("version")) {
                return "${" + e.getKey() + "}";
            }
        }
        for (PomReader.Dep d : pomReader.dependencyManagement().values()) {
            if ("spring-boot-dependencies".equals(d.artifactId())) {
                return d.version();
            }
        }
        return null;
    }

    private void emitDep(StringBuilder sb, PomReader.Dep d, String indent) {
        sb.append(indent).append("<dependency>\n");
        sb.append(indent).append("    <groupId>").append(xml(d.groupId())).append("</groupId>\n");
        sb.append(indent).append("    <artifactId>").append(xml(d.artifactId())).append("</artifactId>\n");
        if (d.version() != null && !d.version().isEmpty()) {
            sb.append(indent).append("    <version>").append(xml(d.version())).append("</version>\n");
        }
        if (d.scope() != null && !"compile".equals(d.scope())) {
            sb.append(indent).append("    <scope>").append(xml(d.scope())).append("</scope>\n");
        }
        if (d.type() != null && !"jar".equals(d.type())) {
            sb.append(indent).append("    <type>").append(xml(d.type())).append("</type>\n");
        }
        if (!d.exclusions().isEmpty()) {
            sb.append(indent).append("    <exclusions>\n");
            for (String ex : d.exclusions()) {
                sb.append(indent).append("        <exclusion>\n");
                sb.append(indent).append("            <groupId>*</groupId>\n");
                sb.append(indent).append("            <artifactId>").append(xml(ex)).append("</artifactId>\n");
                sb.append(indent).append("        </exclusion>\n");
            }
            sb.append(indent).append("    </exclusions>\n");
        }
        sb.append(indent).append("</dependency>\n");
    }

    private String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String camelCase(String tag) {
        StringBuilder sb = new StringBuilder();
        for (String part : tag.split("[-_]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    // ---------- MIGRATION.md ----------

    private void writeMigrationDoc(Path outDir, List<String[]> serviceDirs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 微服务迁移清单（monoGomicro 生成）\n\n");
        sb.append("源项目：").append(projectDir).append("\n\n");
        sb.append("## 服务划分\n\n");
        sb.append("| 模块 | 社区 | 类数量 |\n|---|---|---|\n");
        sb.append("| common（共享内核） | kernel | ").append(partition.getKernelClasses().size()).append(" |\n");
        for (String[] svc : serviceDirs) {
            sb.append("| ").append(svc[0]).append(" | ").append(svc[1])
                    .append(" | ").append(result.getClassesPerService().get(svc[0])).append(" |\n");
        }
        sb.append("\n## 已自动完成\n\n");
        sb.append("- 类源码按分区拷贝（包路径保持不变）\n");
        sb.append("- 跨服务服务注入改写为 REST 客户端：")
                .append(result.getRewrittenInjections().size()).append(" 处\n");
        for (String s : result.getRewrittenInjections()) {
            sb.append("  - ").append(s).append("\n");
        }
        sb.append("- RPC 桥（客户端 + 服务端 Controller）：").append(result.getGeneratedBridges().size()).append(" 组\n");
        for (String s : result.getGeneratedBridges()) {
            sb.append("  - ").append(s).append("\n");
        }
        sb.append("- MyBatis mapper XML 迁移：").append(result.getCopiedResources()).append(" 个\n");
        sb.append("- 各服务 POM/Application/application.yml（端口从 8081 递增）\n\n");
        sb.append("## 需人工处理\n\n");
        if (result.getManualItems().isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String s : result.getManualItems()) {
                sb.append("- ").append(s).append("\n");
            }
        }
        sb.append("\n## 警示\n\n");
        sb.append("- 事务边界：原本在同一事务内的跨服务调用已变为跨网络调用，需按业务重新设计（Saga/TCC 或最终一致）。\n");
        sb.append("- 生成的客户端对返回值做强转（JSON 反序列化类型擦除），上线前请为关键接口补充显式 DTO。\n");
        sb.append("- Shiro/Security 等过滤器与 AOP 的全局行为需要在新服务边界重新配置。\n");
        Files.writeString(outDir.resolve("MIGRATION.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
