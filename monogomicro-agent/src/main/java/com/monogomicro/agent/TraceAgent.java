package com.monogomicro.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * 运行时轨迹采集 agent。
 * 用法：java -javaagent:monogomicro-agent.jar=out=/tmp/trace.log,include=com.ruoyi.,entry=DispatcherServlet -jar app.jar
 * - include：要插桩的包前缀（分号分隔，必填，防止插桩 JDK/框架内部造成雪崩）
 * - entry：HTTP 入口类名子串（默认 DispatcherServlet），命中即切分新业务用例
 * 轨迹行格式：threadId \t E|N|X \t nanos \t fqcn \t method
 */
public final class TraceAgent {

    public static void premain(String args, Instrumentation inst) throws Exception {
        String out = "/tmp/monogomicro-trace.log";
        String entryClass = "DispatcherServlet";
        String entryMethod = "doDispatch";
        java.util.List<String> includes = new java.util.ArrayList<>();
        if (args != null && !args.isBlank()) {
            for (String kv : args.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = kv.substring(0, eq).trim();
                String value = kv.substring(eq + 1).trim();
                switch (key) {
                    case "out" -> out = value;
                    case "entry" -> {
                        entryClass = value;
                        entryMethod = "doDispatch";
                    }
                    case "entryMethod" -> entryMethod = value;
                    case "include" -> {
                        for (String p : value.split(";")) {
                            if (!p.isBlank()) {
                                includes.add(p.trim());
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
        if (includes.isEmpty()) {
            System.err.println("[monogomicro] 未配置 include 包前缀，agent 不插桩。示例：include=com.ruoyi.");
            return;
        }

        // agent 类必须对应用类加载器可见（织入的代码引用 TraceRecorder）
        var location = TraceAgent.class.getProtectionDomain().getCodeSource().getLocation();
        if (location != null) {
            inst.appendToSystemClassLoaderSearch(new JarFile(java.nio.file.Path.of(location.toURI()).toFile()));
        }

        TraceRecorder.start(out);

        var typeMatcher = ElementMatchers.<net.bytebuddy.description.type.TypeDescription>none();
        for (String prefix : includes) {
            typeMatcher = typeMatcher.or(ElementMatchers.nameStartsWith(prefix));
        }
        typeMatcher = typeMatcher.and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.monogomicro.agent")));

        String finalEntryClass = entryClass;
        String finalEntryMethod = entryMethod;

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.NoOp.INSTANCE)
                .type(typeMatcher)
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(MethodAdvice.class).on(
                                ElementMatchers.isMethod()
                                        .and(ElementMatchers.not(ElementMatchers.isSynthetic())))))
                .installOn(inst);

        // 入口类（如 Spring 的 DispatcherServlet）几乎总在框架包、不在 include 业务包内——
        // 必须用独立 builder 织入，不受 include 限制（R-02：曾与业务类型共用一个 type 过滤器，
        // 导致入口永不匹配、E 行永不产生、运行时分析整体失效）。入口统一由这里织入
        // （上面的业务 builder 不含 EntryAdvice，include 内外都不会重复）。
        var entryType = ElementMatchers.<net.bytebuddy.description.type.TypeDescription>nameContains(finalEntryClass)
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.monogomicro.agent")));
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.NoOp.INSTANCE)
                .type(entryType)
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(EntryAdvice.class).on(
                                ElementMatchers.named(finalEntryMethod)
                                        .and(ElementMatchers.isDeclaredBy(
                                                ElementMatchers.nameContains(finalEntryClass))))))
                .installOn(inst);

        System.out.println("[monogomicro] 轨迹采集已启动 -> " + out + "（include=" + includes + "）");
    }

    private TraceAgent() {
    }

    /** 业务用例入口边界 */
    public static class EntryAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRecorder.record('E', type, method);
        }
    }

    /** 普通方法进出 */
    public static class MethodAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRecorder.record('N', type, method);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRecorder.record('X', type, method);
        }
    }
}
