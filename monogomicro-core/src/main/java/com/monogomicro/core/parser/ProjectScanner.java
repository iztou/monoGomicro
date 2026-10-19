package com.monogomicro.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.monogomicro.core.graph.DependencyGraph;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.ClassStereotype;
import com.monogomicro.core.model.DependencyEdge;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 静态分析入口：解析目标项目全部源码，构建类级加权依赖图。
 * 解析策略：JavaParser 符号解析（源码 + JDK 反射 + Maven 依赖 jar）优先，
 * 失败时回退到 import 表匹配 —— 对符号解析覆盖不到的写法保住召回率。
 */
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    /** 防御性上限：误把根目录/超大 monorepo 指进来时快速失败，而不是耗尽内存。 */
    public static final int DEFAULT_MAX_SOURCE_FILES = 20_000;
    public static final int DEFAULT_MAX_EDGES = 500_000;

    private final List<String> packagePrefixes;
    private final boolean useMavenClasspath;
    private final int maxSourceFiles;
    private final int maxEdges;
    /** 可选进度监听：每解析 {@link #PROGRESS_EVERY} 个文件回调一次累计计数（MCP progress 通知用）。 */
    private java.util.function.IntConsumer progressListener;

    private final Map<String, String> simpleNameToFqcn = new HashMap<>();
    private final Set<String> ambiguousSimpleNames = new HashSet<>();
    private Set<String> registeredClasses;

    /** 最近一次 scan 的 classpath 解析结果（useMavenClasspath=false 时为 null）——
     *  供上层判断精度降级：jars=0 不代表项目没依赖，可能是 mvn 不可用/超时。 */
    private MavenClasspathResolver.ClasspathResult classpathResult;

    public ProjectScanner(List<String> packagePrefixes, boolean useMavenClasspath) {
        this(packagePrefixes, useMavenClasspath, DEFAULT_MAX_SOURCE_FILES, DEFAULT_MAX_EDGES);
    }

    public ProjectScanner(List<String> packagePrefixes, boolean useMavenClasspath,
                          int maxSourceFiles, int maxEdges) {
        this.packagePrefixes = packagePrefixes;
        this.useMavenClasspath = useMavenClasspath;
        this.maxSourceFiles = maxSourceFiles;
        this.maxEdges = maxEdges;
    }

    /** 进度回调节流间隔（文件数）。 */
    private static final int PROGRESS_EVERY = 50;
    /** A-15：解析失败的源文件（计数上报，避免"成功但缺类"的静默偏差） */
    private final java.util.List<String> parseFailures = new java.util.ArrayList<>();

    public void setProgressListener(java.util.function.IntConsumer progressListener) {
        this.progressListener = progressListener;
    }

    private void reportProgress(int files) {
        if (progressListener != null && files % PROGRESS_EVERY == 0) {
            progressListener.accept(files);
        }
    }

    /** 最近一次 scan 的 classpath 解析结果；useMavenClasspath=false 或尚未 scan 时为 null。 */
    public MavenClasspathResolver.ClasspathResult classpathResult() {
        return classpathResult;
    }

    /** A-15：本轮扫描中解析失败的源文件（生成报告时写入 AnalysisReport.parseFailures）。 */
    public List<String> parseFailures() {
        return List.copyOf(parseFailures);
    }

    public DependencyGraph scan(Path projectDir) throws IOException {
        List<Path> sourceRoots = findSourceRoots(projectDir);
        if (sourceRoots.isEmpty()) {
            throw new com.monogomicro.core.M2mException("E_PROJECT_NOT_FOUND",
                    "在 " + projectDir + " 下没有找到任何 src/main/java 源码目录",
                    "确认 projectDir 指向 Maven/Gradle 单体的根目录");
        }
        log.info("发现 {} 个源码根目录", sourceRoots.size());

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        typeSolver.add(new ReflectionTypeSolver(true));
        classpathResult = null;
        if (useMavenClasspath) {
            classpathResult = MavenClasspathResolver.resolveWithStatus(projectDir);
            for (Path jar : classpathResult.jars()) {
                try {
                    typeSolver.add(JarTypeSolver.getJarTypeSolver(jar.toString()));
                } catch (IOException e) {
                    log.debug("跳过无法加载的 jar: {}", jar);
                }
            }
        }

        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        cfg.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(cfg);

        Map<String, ParsedUnit> units = new HashMap<>();
        DependencyGraph graph = new DependencyGraph();
        int scannedFiles = 0;
        int parsedFiles = 0;
        for (Path root : sourceRoots) {
            String moduleName = moduleNameOf(projectDir, root);
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> javaFiles = files
                        .filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> !f.toString().contains("/target/"))
                        .toList();
                scannedFiles += javaFiles.size();
                if (scannedFiles > maxSourceFiles) {
                    throw new com.monogomicro.core.M2mException("E_SCAN_LIMIT",
                            "源文件数超上限：" + scannedFiles + " > " + maxSourceFiles,
                            "确认 projectDir 指向的是目标单体而非过大的目录；确属超大项目可用构造参数调大上限");
                }
                for (Path file : javaFiles) {
                    reportProgress(++parsedFiles);
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    CompilationUnit cu = result.getResult().orElse(null);
                    // F-05：JavaParser 对语法错误常走恢复路径（present CU + problems + 0 顶层类型），
                    // 只判 cu==null 会漏掉最常见的错误形态——以 problems 非空为准
                    if (cu == null || (!result.getProblems().isEmpty() && cu.getTypes().isEmpty())) {
                        parseFailures.add(file.toString());
                        log.warn("解析失败（计入 report.parseFailures）：{}：{}", file, result.getProblems());
                        continue;
                    }
                    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                    // 注册全部顶层类型：class/interface、enum、@interface（注解）
                    // —— 注解和枚举也是一等公民，漏掉会破坏共享内核的依赖闭包
                    for (TypeDeclaration<?> type : cu.getTypes()) {
                        if (type.isNestedType()) {
                            continue;
                        }
                        String fqcn = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
                        if (!matchesPrefix(fqcn)) {
                            continue;
                        }
                        ClassInfo info = new ClassInfo(fqcn, moduleName,
                                projectDir.relativize(file).toString());
                        collectClassMeta(type, info);
                        graph.addNode(info);
                        // 一个文件按一个单元处理，依赖归属首个（主）类型——内部类的依赖本就应算外层类
                        units.putIfAbsent(fqcn, new ParsedUnit(cu, type, info));
                        registerSimpleNames(cu, fqcn);
                    }
                }
            }
        }
        log.info("注册 {} 个项目类", graph.nodeCount());
        if (graph.nodeCount() == 0) {
            throw new com.monogomicro.core.M2mException("E_PKG_NO_MATCH",
                    "没有匹配包前缀 " + packagePrefixes + " 的类",
                    "检查包前缀是否与项目实际包名一致（可先试一二级包名，如 com.ruoyi）");
        }
        registeredClasses = new TreeSet<>(java.util.Comparator.reverseOrder());
        for (ClassInfo c : graph.nodes()) {
            registeredClasses.add(c.getFqcn());
        }

        for (ParsedUnit u : units.values()) {
            extractEdges(u, graph);
            if (graph.edgeCount() > maxEdges) {
                throw new com.monogomicro.core.M2mException("E_SCAN_LIMIT",
                        "依赖边数超上限：" + graph.edgeCount() + " > " + maxEdges,
                        "依赖规模超出预期；可收窄 --package 范围或用构造参数调大上限");
            }
        }
        log.info("依赖图完成：{} 节点 / {} 边 / 总权重 {}", graph.nodeCount(), graph.edgeCount(), graph.totalWeight());
        return graph;
    }

    private void collectClassMeta(TypeDeclaration<?> type, ClassInfo info) {
        Set<String> annotations = new HashSet<>();
        type.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));
        String superName = null;
        List<String> ifaces = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            superName = cid.getExtendedTypes().isEmpty() ? null
                    : cid.getExtendedTypes().get(0).getNameAsString();
            cid.getImplementedTypes().forEach(t -> ifaces.add(t.getNameAsString()));
        } else if (type instanceof com.github.javaparser.ast.body.EnumDeclaration en) {
            en.getImplementedTypes().forEach(t -> ifaces.add(t.getNameAsString()));
        }
        info.setStereotype(ClassStereotype.detect(type.getNameAsString(), info.getPackageName(),
                type instanceof ClassOrInterfaceDeclaration c && c.isInterface(),
                annotations, superName));
        info.setSuperClass(superName);
        info.setInterfaces(ifaces);
    }

    private void registerSimpleNames(CompilationUnit cu, String ownerFqcn) {
        cu.getImports().forEach(imp -> {
            if (imp.isAsterisk()) {
                return;
            }
            String name = imp.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            String prev = simpleNameToFqcn.putIfAbsent(simple, name);
            if (prev != null && !prev.equals(name)) {
                ambiguousSimpleNames.add(simple);
            }
        });
        String own = ownerFqcn.substring(ownerFqcn.lastIndexOf('.') + 1);
        simpleNameToFqcn.putIfAbsent(own, ownerFqcn);
    }

    private void extractEdges(ParsedUnit unit, DependencyGraph graph) {
        String from = unit.info().getFqcn();
        String sample = unit.info().getSimpleName() + ".java";
        TypeDeclaration<?> decl = unit.decl();

        // 继承/实现（enum 的实现接口同样计入）
        List<ClassOrInterfaceType> ancestors = new ArrayList<>();
        if (decl instanceof ClassOrInterfaceDeclaration cid) {
            ancestors.addAll(cid.getExtendedTypes());
            ancestors.addAll(cid.getImplementedTypes());
        } else if (decl instanceof com.github.javaparser.ast.body.EnumDeclaration en) {
            ancestors.addAll(en.getImplementedTypes());
        }
        for (ClassOrInterfaceType t : ancestors) {
            addTypeEdges(graph, from, t, unit, DependencyEdge.EdgeKind.INHERITANCE, sample + " 继承");
        }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            addEdge(graph, from, resolveName(ann.getNameAsString()),
                    DependencyEdge.EdgeKind.ANNOTATION, sample + " @" + ann.getNameAsString());
        }
        for (FieldDeclaration field : decl.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : field.getVariables()) {
                addTypeEdges(graph, from, var.getType(), unit, DependencyEdge.EdgeKind.FIELD,
                        sample + " 字段 " + var.getNameAsString());
            }
            // 字段上的注解（如 RuoYi 的 @Excel/@Xss）也是硬依赖
            for (AnnotationExpr ann : field.getAnnotations()) {
                addEdge(graph, from, resolveName(ann.getNameAsString()), DependencyEdge.EdgeKind.ANNOTATION,
                        sample + " 字段注解@" + ann.getNameAsString());
            }
        }
        for (MethodDeclaration method : decl.findAll(MethodDeclaration.class)) {
            addTypeEdges(graph, from, method.getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                    sample + " 返回值 " + method.getNameAsString() + "()");
            for (Parameter p : method.getParameters()) {
                addTypeEdges(graph, from, p.getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " 参数 " + p.getNameAsString());
            }
            for (AnnotationExpr ann : method.getAnnotations()) {
                addEdge(graph, from, resolveName(ann.getNameAsString()), DependencyEdge.EdgeKind.ANNOTATION,
                        sample + " 方法注解@" + ann.getNameAsString());
            }
        }
        // import 级兜底：有 import 就有引用可能（覆盖字段注解等间接用法），低权重
        unit.cu().getImports().forEach(imp -> {
            if (!imp.isAsterisk()) {
                addEdge(graph, from, sanitize(imp.getNameAsString()), DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " import " + imp.getNameAsString());
            }
        });
        decl.findAll(MethodCallExpr.class).forEach(call -> {
            String line = call.getRange().map(r -> String.valueOf(r.begin.line)).orElse("?");
            addEdge(graph, from, resolveCall(call, unit), DependencyEdge.EdgeKind.METHOD_CALL,
                    sample + ":" + line + " 调用 " + call.getNameAsString() + "()");
        });
        decl.findAll(ObjectCreationExpr.class).forEach(creation -> {
            addEdge(graph, from, resolveTypeNode(creation.getType(), unit), DependencyEdge.EdgeKind.CONSTRUCT,
                    sample + " new " + creation.getType().getNameAsString());
        });
        // 类字面量（如 @Constraint(validatedBy = XssValidator.class)）：同包无 import 时的唯一线索
        decl.findAll(com.github.javaparser.ast.expr.ClassExpr.class).forEach(classExpr -> {
            addEdge(graph, from, resolveName(classExpr.getType().asString()), DependencyEdge.EdgeKind.FIELD,
                    sample + " 类字面量 " + classExpr.getType().asString() + ".class");
        });
        decl.findAll(NameExpr.class).forEach(nameExpr -> {
            addEdge(graph, from, resolveName(nameExpr.getNameAsString()), DependencyEdge.EdgeKind.METHOD_CALL,
                    sample + " 引用 " + nameExpr.getNameAsString());
        });
        // A-16：类型位置覆盖缺口——record 组件、cast、instanceof 模式、catch 参数、
        // throws 声明、局部变量声明、方法引用（Foo::bar）
        // record 常见于嵌套声明（外层是 class 时 instanceof 判断不到），必须 findAll 下钻；
        // findAll 含自身，顶层就是 record 时也覆盖
        decl.findAll(com.github.javaparser.ast.body.RecordDeclaration.class).forEach(rec ->
                rec.getParameters().forEach(p ->
                        addTypeEdges(graph, from, p.getType(), unit, DependencyEdge.EdgeKind.FIELD,
                                sample + " record 组件 " + p.getNameAsString())));
        decl.findAll(com.github.javaparser.ast.expr.CastExpr.class).forEach(cast ->
                addTypeEdges(graph, from, cast.getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " cast (" + cast.getType().asString() + ")"));
        decl.findAll(com.github.javaparser.ast.expr.InstanceOfExpr.class).forEach(io ->
                addTypeEdges(graph, from, io.getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " instanceof " + io.getType().asString()));
        decl.findAll(com.github.javaparser.ast.stmt.CatchClause.class).forEach(cc ->
                addTypeEdges(graph, from, cc.getParameter().getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " catch " + cc.getParameter().getType().asString()));
        decl.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(m ->
                m.getThrownExceptions().forEach(t -> {
                    Type tt = t; // ClassOrInterfaceType 的 erasure 视图
                    addTypeEdges(graph, from, tt, unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                            sample + " throws " + tt.asString());
                }));
        decl.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(v -> {
            if (v.getParentNode().map(p -> p instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr)
                    .orElse(false)) {
                addTypeEdges(graph, from, v.getType(), unit, DependencyEdge.EdgeKind.PARAM_RETURN,
                        sample + " 局部变量 " + v.getNameAsString());
            }
        });
        decl.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class).forEach(ref -> {
            // Foo::bar 的 Foo 部分是类型引用（只处理纯标识符形态的 scope）
            String scope = ref.getScope().toString();
            if (scope.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                addEdge(graph, from, resolveName(scope), DependencyEdge.EdgeKind.METHOD_CALL,
                        sample + " 方法引用 " + scope + "::" + ref.getIdentifier());
            }
        });
    }

    private void addEdge(DependencyGraph graph, String from, String fqcn, DependencyEdge.EdgeKind kind, String sample) {
        if (fqcn == null) {
            return;
        }
        String registered = mapToRegistered(fqcn);
        if (registered != null && !registered.equals(from)) {
            graph.addOccurrence(from, registered, kind, sample);
        }
    }

    /** 类型 + 递归泛型参数都算引用（List<SysRole> 里的 SysRole 是硬依赖） */
    private void addTypeEdges(DependencyGraph graph, String from, com.github.javaparser.ast.type.Type type,
                              ParsedUnit unit, DependencyEdge.EdgeKind kind, String sample) {
        if (type instanceof ClassOrInterfaceType cit) {
            addEdge(graph, from, resolveTypeName(cit, unit), kind, sample);
            cit.getTypeArguments().ifPresent(args -> args.forEach(
                    a -> addTypeEdges(graph, from, a, unit, kind, sample + "<泛型>")));
        } else {
            addEdge(graph, from, resolveTypeNode(type, unit), kind, sample);
        }
    }

    /** 把解析到的 FQCN（可能带内部类后缀）映射回注册表中的顶层类；未注册返回 null */
    private String mapToRegistered(String fqcn) {
        String candidate = fqcn;
        int dollar = candidate.indexOf('$');
        if (dollar > 0) {
            candidate = candidate.substring(0, dollar);
        }
        if (registeredClasses.contains(candidate)) {
            return candidate;
        }
        // 泛型清洗后仍可能带残段，做前缀匹配（registeredClasses 按长度降序即倒序排序）
        for (String registered : registeredClasses) {
            if (candidate.startsWith(registered + ".") || candidate.startsWith(registered + "$")) {
                return registered;
            }
        }
        return null;
    }

    private String resolveTypeName(ClassOrInterfaceType type, ParsedUnit unit) {
        try {
            return sanitize(type.resolve().describe());
        } catch (Exception e) {
            return resolveName(type.getNameAsString());
        }
    }

    private String resolveTypeNode(Type type, ParsedUnit unit) {
        try {
            return sanitize(type.resolve().describe());
        } catch (Exception e) {
            if (type instanceof ClassOrInterfaceType cit) {
                return resolveName(cit.getNameAsString());
            }
            return null;
        }
    }

    private String resolveCall(MethodCallExpr call, ParsedUnit unit) {
        try {
            return sanitize(call.resolve().declaringType().getQualifiedName());
        } catch (Exception e) {
            if (call.getScope().isPresent() && call.getScope().get() instanceof NameExpr scopeName) {
                return resolveName(scopeName.getNameAsString());
            }
            return null;
        }
    }

    /** 简单名/限定名 → FQCN。符号解析失败时的 import 回退路径。 */
    private String resolveName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (name.indexOf('.') > 0) {
            return sanitize(name);
        }
        if (ambiguousSimpleNames.contains(name)) {
            return null; // 同名类无法消歧，宁缺毋滥
        }
        return sanitize(simpleNameToFqcn.get(name));
    }

    /** 清洗泛型/数组/通配符得到裸 FQCN；非限定名返回 null */
    private String sanitize(String described) {
        if (described == null) {
            return null;
        }
        String s = described;
        int lt = s.indexOf('<');
        if (lt > 0) {
            s = s.substring(0, lt);
        }
        s = s.replace("[]", "").replace("...", "").replace("?", "").trim();
        if (!s.contains(".")) {
            return null;
        }
        return s;
    }

    private boolean matchesPrefix(String fqcn) {
        for (String prefix : packagePrefixes) {
            if (fqcn.equals(prefix) || fqcn.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private List<Path> findSourceRoots(Path projectDir) throws IOException {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir, 6)) {
            walk.filter(p -> p.toString().endsWith("src/main/java"))
                    .filter(Files::isDirectory)
                    .filter(p -> !p.toString().contains("/target/"))
                    // m2m-out 是本工具的生成输出目录（CLI/MCP 默认落在项目根）：生成过一次后再分析，
                    // 生成物会被当源码扫进来自我污染（多出各服务的 Application 类、模块名混入 svc-*）
                    .filter(p -> !p.toString().contains("/m2m-out/"))
                    .forEach(roots::add);
        }
        List<Path> deduped = new ArrayList<>();
        for (Path r : roots) {
            boolean coveredByExisting = deduped.stream().anyMatch(x -> r.startsWith(x) && !r.equals(x));
            if (coveredByExisting) {
                continue;
            }
            deduped.removeIf(x -> x.startsWith(r) && !x.equals(r));
            deduped.add(r);
        }
        return deduped;
    }

    private String moduleNameOf(Path projectDir, Path sourceRoot) {
        // sourceRoot = <module>/src/main/java，向上三级到模块目录
        Path moduleDir = sourceRoot;
        for (int i = 0; i < 3 && moduleDir.getParent() != null; i++) {
            moduleDir = moduleDir.getParent();
        }
        Path rel = projectDir.relativize(moduleDir);
        if (rel.getNameCount() == 0 || rel.toString().isEmpty()) {
            return projectDir.getFileName().toString();
        }
        return rel.toString().replace('/', '-');
    }

    private record ParsedUnit(CompilationUnit cu, TypeDeclaration<?> decl, ClassInfo info) {}
}
