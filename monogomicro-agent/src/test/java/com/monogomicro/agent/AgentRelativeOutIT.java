package com.monogomicro.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-01 回归（failsafe，真 fork 子进程）：相对 {@code out=} 路径。
 *
 * 修复前：相对路径的 getParent() 为 null，Files.createDirectories(null) 抛 NPE——
 * NPE 不在 catch(IOException) 范围内，writer 线程直接死亡、轨迹零落盘，
 * 而启动横幅仍报"已启动"。修复后 outFile 先转绝对路径再取 parent。
 */
class AgentRelativeOutIT {

    private static final String BUSINESS = """
            package com.demo;
            public class Entry {
                public static void main(String[] args) { run(); }
                public static void run() { System.out.println("business"); }
            }
            """;

    @Test
    @Timeout(90)
    void relativeOutPathProducesTraceWithoutWriterNpe() throws Exception {
        Path work = Files.createTempDirectory("agent-rel-it");
        Path src = work.resolve("src");
        Files.createDirectories(src.resolve("com/demo"));
        Files.writeString(src.resolve("com/demo/Entry.java"), BUSINESS);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, System.err, null,
                        "--release", "17",
                        "-cp", src.toString(),
                        src.resolve("com/demo/Entry.java").toString()),
                "fixture 编译失败");

        String javaHome = System.getProperty("java.home");
        // cwd 已切到 work：agent jar 必须用绝对路径
        Path agentJar = Path.of("target", "monogomicro-agent-" + projectVersion() + ".jar")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(agentJar), "agent fat jar 未产出：" + agentJar);

        // cwd = work：相对 out=rel.log 解析为 work/rel.log
        Process app = new ProcessBuilder(
                        javaHome + "/bin/java",
                        "-javaagent:" + agentJar
                                + "=out=rel.log,include=com.demo.,entry=Entry,entryMethod=run",
                        "-cp", "src", "com.demo.Entry")
                .directory(work.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(app.getInputStream().readAllBytes());
        assertTrue(app.waitFor(30, TimeUnit.SECONDS), "被测进程超时");
        assertEquals(0, app.exitValue(), "被测进程异常退出；输出：" + output);

        assertFalse(output.contains("NullPointerException"),
                "相对 out= 不得触发 writer 线程 NPE（I-01 修复前 rel.log 零落盘）；输出：" + output);

        Path rel = work.resolve("rel.log");
        assertTrue(Files.isRegularFile(rel), "相对路径轨迹文件必须生成");
        String log = Files.readString(rel);
        assertTrue(log.lines().anyMatch(l -> l.contains("\tE\t")), "E 行缺失。trace:\n" + log);
        assertTrue(log.lines().anyMatch(l -> l.contains("\tN\t")), "N 行缺失。trace:\n" + log);
        // A-33：POSIX 0640——同机其他用户不可读
        try {
            assertEquals("rw-r-----",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(rel)),
                    "轨迹文件权限应为 0640");
        } catch (UnsupportedOperationException nonPosix) {
            // 非 POSIX 文件系统：跳过权限断言
        }
    }

    private static String projectVersion() {
        String v = AgentRelativeOutIT.class.getPackage().getImplementationVersion();
        return v == null ? System.getProperty("m2m.test.version", "0.3.0") : v;
    }
}
