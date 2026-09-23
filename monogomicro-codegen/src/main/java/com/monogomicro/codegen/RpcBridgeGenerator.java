package com.monogomicro.codegen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
public class RpcBridgeGenerator {

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

        // 可见性过滤：签名中的项目类型必须在对应侧可达
        Set<String> consumerScope = new java.util.LinkedHashSet<>();
        consumerScope.add(req.consumerService);
        consumerScope.add("common");
        List<MethodSig> clientMethods = new ArrayList<>();
        for (MethodSig m : methods) {
            if (typesReachable(m, consumerScope)) {
                clientMethods.add(m);
            } else {
                manualItems.add("方法 " + providerSimple + "." + m.name()
                        + " 的签名类型在消费端不可达，未生成客户端方法（服务 " + req.consumerService + "）");
            }
        }
        Set<String> providerScope = new java.util.LinkedHashSet<>();
        providerScope.add(req.providerService);
        providerScope.add("common");
        List<MethodSig> serverMethods = new ArrayList<>();
        for (MethodSig m : methods) {
            if (typesReachable(m, providerScope)) {
                serverMethods.add(m);
            } else {
                manualItems.add("方法 " + providerSimple + "." + m.name()
                        + " 的签名类型在提供端不可达，未生成 RPC 端点（服务 " + req.providerService + "）");
            }
        }

        writeClient(req, providerSimple, clientMethods, consumerSrcRoot, basePackage);
        writeController(req, providerSimple, serverMethods, providerSrcRoot, basePackage);
        return !clientMethods.isEmpty() || !serverMethods.isEmpty();
    }

    /** 签名引用的项目类型（非 JDK/外部）全部落在 allowed 集合内 */
    private boolean typesReachable(MethodSig m, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        collectNames(m.returnType(), names);
        for (String p : m.paramTypes()) {
            collectNames(p, names);
        }
        for (String name : names) {
            String fqcn = simpleNameToFqcn.get(name);
            if (fqcn == null) {
                continue; // JDK/外部类型，生成端依赖原项目依赖即可
            }
            String owner = ownerOf.apply(fqcn);
            if (owner != null && !allowed.contains(owner)) {
                return false;
            }
        }
        return true;
    }

    /** 从类型字符串提取标识符（含泛型参数） */
    private void collectNames(String type, Set<String> names) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(type);
        while (matcher.find()) {
            names.add(matcher.group());
        }
    }

    private void writeClient(BridgeRequest req, String providerSimple, List<MethodSig> methods,
                             Path srcRoot, String basePackage) throws IOException {
        String pkg = basePackage + ".client";
        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.beans.factory.annotation.Value");
        imports.add("org.springframework.stereotype.Component");
        imports.add("org.springframework.web.client.RestTemplate");
        for (var m : methods) {
            collectImports(m, imports);
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
        impl.append("}\n");
        write(srcRoot.resolve(pkg.replace('.', '/') + "/" + providerSimple + "Client.java"), sb.toString());
        write(srcRoot.resolve(pkg.replace('.', '/') + "/" + implName + ".java"), impl.toString());
    }

    private void emitClientMethod(StringBuilder sb, MethodSig m, String providerSimple) {
        String url = "baseUrl + \"/rpc/" + providerSimple + "/" + m.name() + "\"";
        sb.append("    @Override\n    public ").append(m.returnType()).append(" ").append(m.name())
                .append("(").append(paramsDecl(m)).append(") {\n");
        if (m.paramTypes().isEmpty()) {
            sb.append("        Object result = restTemplate.postForObject(").append(url)
                    .append(", null, Object.class);\n");
        } else if (m.paramTypes().size() == 1) {
            sb.append("        Object result = restTemplate.postForObject(").append(url)
                    .append(", ").append(m.paramNames().get(0)).append(", Object.class);\n");
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
            sb.append("    @PostMapping(\"/").append(m.name()).append("\")\n");
            sb.append("    public Object ").append(m.name()).append("(@RequestBody java.util.Map<String, Object> body) {\n");
            sb.append(isVoid ? "        service." : "        return service.").append(m.name()).append("(");
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
            sb.append(");\n");
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
            } else if (name.equals("List")) {
                imports.add("java.util.List");
            } else if (name.equals("Map")) {
                imports.add("java.util.Map");
            } else if (name.equals("Set")) {
                imports.add("java.util.Set");
            } else if (name.equals("Date")) {
                imports.add("java.util.Date");
            } else if (name.equals("BigDecimal")) {
                imports.add("java.math.BigDecimal");
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
