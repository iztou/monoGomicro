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

    private final List<String> packagePrefixes;
    private final boolean useMavenClasspath;

    private final Map<String, String> simpleNameToFqcn = new HashMap<>();
    private final Set<String> ambiguousSimpleNames = new HashSet<>();
    private Set<String> registeredClasses;

    public ProjectScanner(List<String> packagePrefixes, boolean useMavenClasspath) {
        this.packagePrefixes = packagePrefixes;
        this.useMavenClasspath = useMavenClasspath;
    }

    public DependencyGraph scan(Path projectDir) throws IOException {
        List<Path> sourceRoots = findSourceRoots(projectDir);
        if (sourceRoots.isEmpty()) {
            throw new IOException("在 " + projectDir + " 下没有找到任何 src/main/java 源码目录");
        }
        log.info("发现 {} 个源码根目录", sourceRoots.size());

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        typeSolver.add(new ReflectionTypeSolver(true));
        if (useMavenClasspath) {
            for (Path jar : MavenClasspathResolver.resolve(projectDir)) {
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
        for (Path root : sourceRoots) {
            String moduleName = moduleNameOf(projectDir, root);
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> javaFiles = files
                        .filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> !f.toString().contains("/target/"))
                        .toList();
                for (Path file : javaFiles) {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    CompilationUnit cu = result.getResult().orElse(null);
                    if (cu == null) {
                        log.debug("解析失败 {}：{}", file, result.getProblems());
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
            throw new IOException("没有匹配包前缀 " + packagePrefixes + " 的类，请检查 --package 参数");
        }
        registeredClasses = new TreeSet<>(java.util.Comparator.reverseOrder());
        for (ClassInfo c : graph.nodes()) {
            registeredClasses.add(c.getFqcn());
        }

        units.values().forEach(u -> extractEdges(u, graph));
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
