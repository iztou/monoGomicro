package com.monogomicro.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-02 回归（failsafe，真 fork 子进程）：入口类在 <b>include 包之外</b>（模拟 Spring 的
 * DispatcherServlet 位于 org.springframework 的真实形态）时，agent 也必须产生 E 行、
 * 修复前 E 行永不产生（入口织入被 include 类型过滤器挡掉），运行时分析整体失效。
 *
 * fixture：com.framework.DispatcherServlet#doDispatch（include 外）→ com.demo.Entry#controller
 * → com.demo.Entry#service（include=com.demo. 内）。
 */
class AgentEntryIT {

    private static final String FRAMEWORK_ENTRY = """
            package com.framework;
            public class DispatcherServlet {
                public void doDispatch() { new com.demo.Entry().controller(); }
            }
            """;

    private static final String BUSINESS = """
            package com.demo;
            public class Entry {
                public static void main(String[] args) { new com.framework.DispatcherServlet().doDispatch(); }
                public void controller() { service(); }
                public void service() { System.out.println("business"); }
            }
            """;

    @Test
    @Timeout(90)
    void frameworkEntryOutsideIncludeProducesUseCases() throws Exception {
        Path work = Files.createTempDirectory("agent-it");
        Path src = work.resolve("src");
        Files.createDirectories(src.resolve("com/framework"));
        Files.createDirectories(src.resolve("com/demo"));
        Files.writeString(src.resolve("com/framework/DispatcherServlet.java"), FRAMEWORK_ENTRY);
        Files.writeString(src.resolve("com/demo/Entry.java"), BUSINESS);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // 固定 --release 17：fixture 默认按宿主 JDK 编译（如 JDK 27 → class v69），
        // ByteBuddy 1.15.10 解析不了新版本 class 文件会静默零织入（N-01），IT 必须与环境解耦
        assertEquals(0, compiler.run(null, System.err, null,
                "--release", "17",
                "-cp", src.toString(),
                src.resolve("com/framework/DispatcherServlet.java").toString(),
                src.resolve("com/demo/Entry.java").toString()),
                "fixture 编译失败");

        String javaHome = System.getProperty("java.home");
        String agentJar = "target/monogomicro-agent-" + projectVersion() + ".jar";
        assertTrue(Files.isRegularFile(Path.of(agentJar)), "agent fat jar 未产出：" + agentJar);
        Path trace = work.resolve("trace.log");

        Process app = new ProcessBuilder(
                        javaHome + "/bin/java",
                        "-javaagent:" + agentJar + "=out=" + trace + ",include=com.demo.,entry=DispatcherServlet",
                        "-cp", src.toString(), "com.demo.Entry")
                .redirectErrorStream(true)
                .start();
        app.getInputStream().readAllBytes();
        assertTrue(app.waitFor(30, TimeUnit.SECONDS), "被测进程超时");
        assertEquals(0, app.exitValue(), "被测进程异常退出");

        String log = Files.readString(trace);
        long entries = log.lines().filter(l -> l.contains("\tE\t")).count();
        assertTrue(entries >= 1,
                "入口类在 include 外时必须产生 E 行（R-02 修复前为 0）。trace:\n" + log);
        assertTrue(log.lines().anyMatch(l -> l.contains("\tN\t")), "业务方法 N 行缺失");
    }

    private static String projectVersion() {
        String v = AgentEntryIT.class.getPackage().getImplementationVersion();
        return v == null ? System.getProperty("m2m.test.version", "0.3.0") : v;
    }
}
