package com.monogomicro.codegen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨服务调用桥生成：
 * 消费端 —— <Provider>Client 接口（镜像原服务接口方法）+ RestTemplate 实现类；
 * 提供端 —— <Provider>RpcController，把原接口方法暴露为 POST /rpc/<Provider>/<method>。
 * 方法签名引用的类型若不在本服务编译可达范围（本服务/共享内核/JDK），该方法跳过并记录人工项。
 *
 * 失败策略（R-08，底线 = 不生成编译不过的代码）：
 * 1. 接口重载方法只保留首个，其余进人工项（桥端统一 Map body 签名，重载会产生同名方法/重复路由）；
 * 2. 签名类型三分类：项目类型按服务可达性判定；已知 JDK 类型（java.lang + 白名单）放行并补 import；
 *    其余未知简单名一律视为不可处理——跳过并进人工项，不赌它能编译；
 * 3. 泛型元素/数组反序列化为 LinkedHashMap 的运行时风险维持现状（README 已要求关键接口显式 DTO）。
 */
public class RpcBridgeGenerator {

    /** 编译期无需 import 的 java.lang 类型 + 基本类型/void（见 collectNames 提取粒度） */
    private static final Set<String> JAVA_LANG = Set.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Number", "StringBuilder", "StringBuffer", "Math", "System", "Class",
            "Enum", "Throwable", "Exception", "RuntimeException", "Override", "SuppressWarnings",
            "Deprecated", "SafeVarargs", "void", "int", "long", "double", "float", "boolean",
            "byte", "short", "char", "Iterable", "Comparable", "Void", "CharSequence", "Runnable",
            "Record", "Cloneable", "AutoCloseable", "Thread");

    /** 已知 JDK 类型 → 需要补的 import（白名单；不在表内的未知类型不再放行——R-08） */
    private static final Map<String, String> KNOWN_JDK_IMPORTS = Map.ofEntries(
            Map.entry("List", "java.util.List"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("Set", "java.util.Set"),
            Map.entry("HashMap", "java.util.HashMap"),
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("HashSet", "java.util.HashSet"),
            Map.entry("Collection", "java.util.Collection"),
            Map.entry("Optional", "java.util.Optional"),
            Map.entry("Date", "java.util.Date"),
            Map.entry("UUID", "java.util.UUID"),
            Map.entry("BigDecimal", "java.math.BigDecimal"),
            Map.entry("BigInteger", "java.math.BigInteger"),
            Map.entry("LocalDate", "java.time.LocalDate"),
            Map.entry("LocalDateTime", "java.time.LocalDateTime"),
            Map.entry("LocalTime", "java.time.LocalTime"),
            Map.entry("Instant", "java.time.Instant"),
            Map.entry("Duration", "java.time.Duration"),
            Map.entry("Stream", "java.util.stream.Stream"),
            Map.entry("Collectors", "java.util.stream.Collectors"),
            Map.entry("Objects", "java.util.Objects"),
            Map.entry("Arrays", "java.util.Arrays"),
            Map.entry("Collections", "java.util.Collections"),
            Map.entry("LinkedList", "java.util.LinkedList"),
            Map.entry("Timestamp", "java.sql.Timestamp"),
            Map.entry("Time", "java.sql.Time"),
            Map.entry("SQLException", "java.sql.SQLException"),
            Map.entry("File", "java.io.File"),
            Map.entry("InputStream", "java.io.InputStream"),
            Map.entry("OutputStream", "java.io.OutputStream"),
            Map.entry("IOException", "java.io.IOException"),
            Map.entry("Pattern", "java.util.regex.Pattern"),
            Map.entry("Matcher", "java.util.regex.Matcher"),
            Map.entry("TimeZone", "java.util.TimeZone"),
            Map.entry("SimpleDateFormat", "java.text.SimpleDateFormat"),
            Map.entry("Calendar", "java.util.Calendar"),
            Map.entry("Entry", "java.util.Map.Entry"));

    /** 一次跨服务注入的完整描述 */
    public static class BridgeRequest {
        String consumerService;      // 消费服务目录名（svc-xxx）
        String providerService;      // 提供服务目录名
        String providerInterfaceFqcn;
        String providerInterfaceSource; // 原接口源码
        String providerNameKey;      // 配置键（svc.<key>.base-url）
    }

    private final Map<String, String> simpleNameToFqcn;
    private final java.util.function.Function<String, String> ownerOf; // fqcn -> 服务目录名 或 "common"
    private final List<String> manualItems;

    public RpcBridgeGenerator(Map<String, String> simpleNameToFqcn,
                              java.util.function.Function<String, String> ownerOf,
                              List<String> manualItems) {
        this.simpleNameToFqcn = simpleNameToFqcn;
        this.ownerOf = ownerOf;
        this.manualItems = manualItems;
    }

    /** 在消费/提供两个服务的源码根下生成桥接类。返回是否生成成功。 */
    public boolean generate(BridgeRequest req, Path consumerSrcRoot, Path providerSrcRoot,
                            String basePackage) throws IOException {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(req.providerInterfaceSource);
        } catch (Exception e) {
            manualItems.add("无法解析服务接口 " + req.providerInterfaceFqcn + "，需人工桥接");
            return false;
        }
        String providerSimple = simpleName(req.providerInterfaceFqcn);

        // 注意：getPrimaryType() 依赖文件名上下文（parse(String) 时为空），这里用 getType(0)
        if (cu.getTypes().isEmpty() || !cu.getType(0).isClassOrInterfaceDeclaration()) {
            manualItems.add("provider 不是类/接口声明（enum/record/package-info？），需人工桥接："
                    + req.providerInterfaceFqcn);
            return false;
        }

        List<MethodSig> methods = new ArrayList<>();
        for (MethodDeclaration m : cu.getType(0).asClassOrInterfaceDeclaration().getMethods()) {
            if (m.isDefault() || m.isStatic()) {
                continue;
            }
            List<String> paramTypes = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            for (var p : m.getParameters()) {
                // asString() 不含 varargs 的 "..."，必须补上，否则镜像签名变形
                paramTypes.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
                paramNames.add(p.getNameAsString());
            }
            methods.add(new MethodSig(m.getNameAsString(), m.getType().asString(), paramTypes, paramNames));
        }

        // 可见性过滤：签名中的项目类型必须在对应侧可达；未知类型（非项目、非已知 JDK）不再放行（R-08）
        Set<String> consumerScope = new java.util.LinkedHashSet<>();
        consumerScope.add(req.consumerService);
        consumerScope.add("common");
        List<MethodSig> clientMethods = new ArrayList<>();
        int typeSkippedConsumer = 0;
        for (MethodSig m : methods) {
            Set<String> bad = unreachableTypes(m, consumerScope);
            if (bad.isEmpty()) {
                clientMethods.add(m);
            } else {
                typeSkippedConsumer++;
                manualItems.add("方法 " + providerSimple + "." + m.name()
                        + " 的签名类型在消费端不可达或无法识别 " + bad + "（服务 "
                        + req.consumerService + "）");
            }
        }
        Set<String> providerScope = new java.util.LinkedHashSet<>();
        providerScope.add(req.providerService);
        providerScope.add("common");
        List<MethodSig> serverMethods = new ArrayList<>();
        int typeSkippedProvider = 0;
        for (MethodSig m : methods) {
            Set<String> bad = unreachableTypes(m, providerScope);
            if (bad.isEmpty()) {
                serverMethods.add(m);
            } else {
                typeSkippedProvider++;
            }
        }

        // A-08 失败策略：任一侧存在"类型不可达"的被跳过方法时，无法给出可编译的镜像签名，
        // 整对不改写注入（消费端保留原引用并记人工项），绝不生成编译不过的代码
        boolean consumerBlocked = typeSkippedConsumer > 0;
        boolean providerBlocked = typeSkippedProvider > 0;
        if (consumerBlocked || providerBlocked) {
            manualItems.add("接口 " + providerSimple + "（服务 " + req.providerService + "）存在签名类型"
                    + "不可达的方法，未生成 RPC 桥、未改写消费端注入（保持原引用可读但编译需人工："
                    + "把相关类型提升进 common，或手工实现桥接）");
            return false;
        }

        // 重载冲突（R-08）：桥端方法统一为 name(Map body) 签名 + @PostMapping("/name")，
        // 同名重载会产生重复方法与重复路由——同名只保留首个，其余生成保底 stub
        // （A-08：stub 抛 UnsupportedOperationException，编译永远通过，调用时明确失败而非编译失败）
        List<MethodSig> clientStubs = new ArrayList<>();
        clientMethods = dedupeOverloads(clientMethods, clientStubs);
        serverMethods = dedupeOverloads(serverMethods, null);

        writeClient(req, providerSimple, clientMethods, clientStubs, consumerSrcRoot, basePackage);
        writeController(req, providerSimple, serverMethods, providerSrcRoot, basePackage);
        return !clientMethods.isEmpty() || !serverMethods.isEmpty() || !clientStubs.isEmpty();
    }


    /** 同名方法只保留首个（重载在桥端无法按名路由），其余记人工项；stubs 非空时收集为保底签名 */
    private List<MethodSig> dedupeOverloads(List<MethodSig> methods, List<MethodSig> stubs) {
        Set<String> seen = new HashSet<>();
        List<MethodSig> kept = new ArrayList<>();
        for (MethodSig m : methods) {
            if (seen.add(m.name())) {
                kept.add(m);
            } else if (stubs != null) {
                stubs.add(m);
                manualItems.add("重载方法 " + m.name() + " 的额外变体生成保底 stub"
                        + "（调用时抛 UnsupportedOperationException），需人工按参数区分桥接");
            }
        }
        return kept;
    }

    /** 签名引用的不可处理类型：项目类型不在 allowed 内，或不是已知 JDK/外部类型（返回空集 = 全部可达） */
    private Set<String> unreachableTypes(MethodSig m, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        collectNames(m.returnType(), names);
        for (String p : m.paramTypes()) {
            collectNames(p, names);
        }
        Set<String> bad = new LinkedHashSet<>();
        for (String name : names) {
            String fqcn = simpleNameToFqcn.get(name);
            if (fqcn == null) {
                // 非项目类型：仅已知 JDK 类型与单字母类型变量（T/E/K/V，声明侧泛形）放行；
                // 其余不再赌它能编译（R-08）——类型变量的镜像签名两端一致，编译可行
                boolean typeVar = name.length() == 1 && Character.isUpperCase(name.charAt(0));
                if (!typeVar && !JAVA_LANG.contains(name) && !KNOWN_JDK_IMPORTS.containsKey(name)) {
                    bad.add(name);
                }
                continue;
            }
            String owner = ownerOf.apply(fqcn);
            if (owner != null && !allowed.contains(owner)) {
                bad.add(name);
            }
        }
        return bad;
    }

    /** 从类型字符串提取标识符（含泛型参数；跳过限定名中 '.' 之后的段，如 Map.Entry 的 Entry） */
    /** 通配/泛型关键字：不是类型引用（A-09：曾把 extends 当类型丢弃整方法） */
    private static final Set<String> NON_TYPE_KEYWORDS =
            Set.of("extends", "super", "final");

    private void collectNames(String type, Set<String> names) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(type);
        String lastQualified = null; // 限定名的最后一段才是类型简单名（A-09：com.foo.Bar 只取 Bar）
        int lastQualifiedEnd = -1;
        while (matcher.find()) {
            if (matcher.start() > 0 && type.charAt(matcher.start() - 1) == '.') {
                lastQualified = matcher.group();
                lastQualifiedEnd = matcher.end();
                continue; // 限定名的成员段——最后一段会在下方统一补收
            }
            String token = matcher.group();
            if (!NON_TYPE_KEYWORDS.contains(token)) {
                names.add(token);
            }
        }
        if (lastQualified != null && !NON_TYPE_KEYWORDS.contains(lastQualified)) {
            names.add(lastQualified);
        }
    }

    private void writeClient(BridgeRequest req, String providerSimple, List<MethodSig> methods,
                             List<MethodSig> stubs, Path srcRoot, String basePackage) throws IOException {
        String pkg = basePackage + ".client";
        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.beans.factory.annotation.Value");
        imports.add("org.springframework.stereotype.Component");
        imports.add("org.springframework.web.client.RestTemplate");
        for (var m : methods) {
            collectImports(m, imports);
        }
        for (var m : stubs) {
            collectImports(m, imports); // stub 签名同样需要类型可达
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 monoGomicro 生成的跨服务客户端，替代原本地服务注入\n");
        sb.append("package ").append(pkg).append(";\n\n");
        emitImports(imports, sb);
        sb.append("\npublic interface ").append(providerSimple).append("Client {\n");
        for (var m : methods) {
            sb.append("    ").append(m.returnType()).append(" ").append(m.name()).append("(")
                    .append(paramsDecl(m)).append(");\n");
        }
        for (var m : stubs) {
            sb.append("    ").append(m.returnType()).append(" ").append(m.name()).append("(")
                    .append(paramsDecl(m)).append(");\n");
        }
        sb.append("}\n");

        // 实现类
        String implName = providerSimple + "ClientImpl";
        StringBuilder impl = new StringBuilder();
        impl.append("package ").append(pkg).append(";\n\n");
        emitImports(imports, impl);
        impl.append("\n@Component\npublic class ").append(implName).append(" implements ")
                .append(providerSimple).append("Client {\n");
        impl.append("    private final RestTemplate restTemplate = new RestTemplate();\n");
        impl.append("    @Value(\"${svc.").append(req.providerNameKey).append(".base-url}\")\n");
        impl.append("    private String baseUrl;\n\n");
        for (var m : methods) {
            emitClientMethod(impl, m, providerSimple);
        }
        for (var m : stubs) {
            // A-08 保底：编译通过，调用时明确失败（优于生成编译不过的代码）
            impl.append("    @Override\n    public ").append(m.returnType()).append(" ")
                    .append(m.name()).append("(").append(paramsDecl(m)).append(") {\n")
                    .append("        throw new UnsupportedOperationException(\"")
                    .append(m.name()).append(" 的重载变体无法按名自动桥接，需人工实现\");\n")
                    .append("    }\n");
        }
        impl.append("}\n");
        write(srcRoot.resolve(pkg.replace('.', '/') + "/" + providerSimple + "Client.java"), sb.toString());
        write(srcRoot.resolve(pkg.replace('.', '/') + "/" + implName + ".java"), impl.toString());
    }

    private void emitClientMethod(StringBuilder sb, MethodSig m, String providerSimple) {
        String url = "baseUrl + \"/rpc/" + providerSimple + "/" + m.name() + "\"";
        sb.append("    @Override\n    public ").append(m.returnType()).append(" ").append(m.name())
                .append("(").append(paramsDecl(m)).append(") {\n");
        if (m.paramTypes().isEmpty()) {
            // 发空 Map 而非 null body：桥端 @RequestBody 空 body 会 400（required 语义）
            sb.append("        Object result = restTemplate.postForObject(").append(url)
                    .append(", java.util.Map.of(), Object.class);\n");
        } else if (m.paramTypes().size() == 1) {
            // 与桥端 @RequestBody Map 契约对齐：参数包装成 {"p0": value}，
            // 裸值会被 RestTemplate 以 text/plain 发出，桥端 415
            sb.append("        Object result = restTemplate.postForObject(").append(url)
                    .append(", java.util.Map.of(\"p0\", ").append(m.paramNames().get(0))
                    .append("), Object.class);\n");
        } else {
            sb.append("        java.util.Map<String, Object> body = new java.util.HashMap<>();\n");
            for (int i = 0; i < m.paramNames().size(); i++) {
                sb.append("        body.put(\"p").append(i).append("\", ").append(m.paramNames().get(i)).append(");\n");
            }
            sb.append("        Object result = restTemplate.postForObject(").append(url)
                    .append(", body, Object.class);\n");
        }
        String ret = m.returnType();
        if (ret.equals("void")) {
            sb.append("        // 原方法无返回值\n");
        } else if (isPrimitive(ret)) {
            sb.append("        if (result == null) return ").append(primitiveDefault(ret)).append(";\n");
            sb.append("        return ").append(unbox(ret)).append(";\n");
        } else {
            sb.append("        @SuppressWarnings(\"unchecked\")\n        ").append(ret)
                    .append(" typed = (").append(ret).append(") result;\n");
            sb.append("        return typed;\n");
        }
        sb.append("    }\n\n");
    }

    private void writeController(BridgeRequest req, String providerSimple, List<MethodSig> methods,
                                 Path srcRoot, String basePackage) throws IOException {
        String pkg = basePackage + ".rpc";
        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.beans.factory.annotation.Autowired");
        imports.add("org.springframework.web.bind.annotation.PostMapping");
        imports.add("org.springframework.web.bind.annotation.RequestBody");
        imports.add("org.springframework.web.bind.annotation.RequestMapping");
        imports.add("org.springframework.web.bind.annotation.RestController");
        imports.add("com.fasterxml.jackson.databind.ObjectMapper");
        imports.add(req.providerInterfaceFqcn);
        for (var m : methods) {
            collectImports(m, imports);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 monoGomicro 生成的 RPC 端点，把原本地服务暴露给其他微服务\n");
        sb.append("package ").append(pkg).append(";\n\n");
        emitImports(imports, sb);
        sb.append("\n@RestController\n@RequestMapping(\"/rpc/").append(providerSimple).append("\")\n");
        sb.append("public class ").append(providerSimple).append("RpcController {\n\n");
        sb.append("    private static final ObjectMapper MAPPER = new ObjectMapper();\n\n");
        sb.append("    @Autowired\n    private ").append(simpleName(req.providerInterfaceFqcn)).append(" service;\n\n");
        for (var m : methods) {
            boolean isVoid = "void".equals(m.returnType());
            // produces + 显式 JSON 序列化：裸 String 返回值会被 StringHttpMessageConverter
            // 以非 JSON 形态输出，客户端 Jackson 反序列化必炸
            sb.append("    @PostMapping(value = \"/").append(m.name())
                    .append("\", produces = \"application/json\")\n");
            // required=false + 空 body 归一：容忍不发 body 的客户端（无参方法的合理调用形态）
            sb.append("    public String ").append(m.name())
                    .append("(@RequestBody(required = false) java.util.Map<String, Object> body) throws Exception {\n");
            sb.append("        if (body == null) body = java.util.Map.of();\n");
            sb.append(isVoid ? "        service." : "        return MAPPER.writeValueAsString(service.")
                    .append(m.name()).append("(");
            for (int i = 0; i < m.paramTypes().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String paramType = m.paramTypes().get(i);
                String castType = paramType.endsWith("...") ? paramType.replace("...", "[]") : paramType;
                String classArg = rawType(castType) + ".class"; // 泛型类字面量不存在，用裸类型
                sb.append("(").append(castType).append(") convert(body.get(\"p")
                        .append(i).append("\"), ").append(classArg).append(")");
            }
            sb.append(isVoid ? ");\n" : "));\n"); // 非 void 多一层 writeValueAsString( 的闭合
            if (isVoid) {
                sb.append("        return null;\n");
            }
            sb.append("    }\n\n");
        }
        if (!methods.isEmpty()) {
            sb.append("    private static Object convert(Object value, Class<?> type) {\n");
            sb.append("        if (value == null || type.isInstance(value)) {\n            return value;\n        }\n");
            sb.append("        return MAPPER.convertValue(value, type);\n    }\n");
        }
        sb.append("}\n");
        write(srcRoot.resolve(pkg.replace('.', '/') + "/" + providerSimple + "RpcController.java"), sb.toString());
    }

    private void collectImports(MethodSig m, Set<String> imports) {
        Set<String> names = new LinkedHashSet<>();
        collectNames(m.returnType(), names);
        m.paramTypes().forEach(p -> collectNames(p, names));
        for (String name : names) {
            String fqcn = simpleNameToFqcn.get(name);
            if (fqcn != null) {
                imports.add(fqcn);
            } else {
                String jdk = KNOWN_JDK_IMPORTS.get(name); // R-08：白名单驱动，新增类型只改一处
                if (jdk != null) {
                    imports.add(jdk);
                }
            }
        }
    }

    private void emitImports(Set<String> imports, StringBuilder sb) {
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
    }

    private String paramsDecl(MethodSig m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.paramTypes().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(m.paramTypes().get(i)).append(" ").append(m.paramNames().get(i));
        }
        return sb.toString();
    }

    private String rawType(String type) {
        int lt = type.indexOf('<');
        return lt > 0 ? type.substring(0, lt) : type;
    }

    private boolean isPrimitive(String type) {
        return Set.of("int", "long", "boolean", "double", "float", "short", "byte", "char").contains(type);
    }

    private String primitiveDefault(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            default -> "0";
        };
    }

    private String unbox(String type) {
        return switch (type) {
            case "int" -> "((Number) result).intValue()";
            case "long" -> "((Number) result).longValue()";
            case "short" -> "((Number) result).shortValue()";
            case "byte" -> "((Number) result).byteValue()";
            case "double" -> "((Number) result).doubleValue()";
            case "float" -> "((Number) result).floatValue()";
            case "boolean" -> "(Boolean) result";
            default -> "(Character) result";
        };
    }

    private String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private record MethodSig(String name, String returnType, List<String> paramTypes, List<String> paramNames) {}
}
