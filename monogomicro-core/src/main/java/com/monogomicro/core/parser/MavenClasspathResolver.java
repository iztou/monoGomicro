package com.monogomicro.core.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 通过 maven-dependency-plugin 拿到目标项目依赖 jar 路径，供 JarTypeSolver 做精确类型解析。
 * 多模块项目在根目录跑一次：每个模块把 classpath 写到自己的 basedir/m2m-cp.txt，读取后合并。
 * -fae 容错：部分模块因 reactor 内部 jar 未安装而失败时，其余模块的 classpath 仍然产出，
 * 只要收集到 jar 就视为降级成功（内部类由源码 TypeSolver 解析，缺的只是内部 jar）。
 */
public class MavenClasspathResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenClasspathResolver.class);
    private static final String CP_FILE = "m2m-cp.txt";

    /** 解析状态：OK 全部成功；PARTIAL 部分模块失败已降级合并；NO_POM 目标无 pom.xml；
     *  UNAVAILABLE mvn 不可执行（未安装/不在 PATH）；TIMEOUT 执行超时；EMPTY 执行完但无产出。 */
    public enum Status { OK, PARTIAL, NO_POM, UNAVAILABLE, TIMEOUT, EMPTY }

    /** 解析结果：jar 清单 + 状态 + 细节。状态供上层（MCP/CLI）判断是否提示精度降级。 */
    public record ClasspathResult(List<Path> jars, Status status, String detail) {}

    /** 兼容入口：只要 jar 清单（既有 CLI 行为——静默降级继续解析）。 */
    public static List<Path> resolve(Path projectDir) {
        return resolveWithStatus(projectDir).jars();
    }

    /** 轻量探测 mvn 是否可执行，供上层在昂贵扫描前 fail-fast（E_MAVEN_MISSING）。 */
    public static boolean mvnAvailable() {
        try {
            return new ProcessBuilder("mvn", "-v").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static ClasspathResult resolveWithStatus(Path projectDir) {
        if (!Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            log.warn("目标目录没有 pom.xml，跳过 Maven classpath 解析（仅源码+JDK 反射解析）");
            return new ClasspathResult(List.of(), Status.NO_POM, "目标目录没有 pom.xml");
        }
        cleanStaleOutputs(projectDir);
        int exit = 1;
        try {
            Process p = new ProcessBuilder("mvn", "-q", "-B", "-U", "-fae", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + CP_FILE,
                    "-Dmdep.failOnMissingReactorArtifact=false")
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            // 依赖解析可能下载插件，给足 5 分钟
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                log.warn("mvn dependency:build-classpath 超时，跳过 classpath 解析");
                return new ClasspathResult(List.of(), Status.TIMEOUT, "mvn 执行超过 5 分钟");
            }
            exit = p.exitValue();
        } catch (IOException e) {
            log.warn("执行 mvn 失败：{}，跳过 classpath 解析", e.getMessage());
            return new ClasspathResult(List.of(), Status.UNAVAILABLE, "mvn 不可执行：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("执行 mvn 被中断，跳过 classpath 解析");
            return new ClasspathResult(List.of(), Status.UNAVAILABLE, "mvn 执行被中断");
        }

        Set<Path> jars = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(f -> f.getFileName() != null && f.getFileName().toString().equals(CP_FILE))
                    .forEach(cpFile -> {
                        try {
                            String content = Files.readString(cpFile, StandardCharsets.UTF_8).trim();
                            if (!content.isEmpty()) {
                                for (String entry : content.split(":")) {
                                    if (entry.endsWith(".jar") && Files.isRegularFile(Path.of(entry))) {
                                        jars.add(Path.of(entry));
                                    }
                                }
                            }
                            Files.deleteIfExists(cpFile);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException e) {
            log.warn("遍历 classpath 输出失败：{}", e.getMessage());
        }
        if (jars.isEmpty()) {
            log.warn("classpath 解析无产出（mvn 退出码 {}），使用源码+import 回退解析", exit);
            return new ClasspathResult(List.of(), Status.EMPTY, "mvn 退出码 " + exit + "，无 classpath 产出");
        }
        log.info("Maven classpath：{} 个依赖 jar{}",
                jars.size(), exit == 0 ? "" : "（部分模块失败，已降级合并可用部分）");
        return exit == 0
                ? new ClasspathResult(new ArrayList<>(jars), Status.OK, null)
                : new ClasspathResult(new ArrayList<>(jars), Status.PARTIAL,
                        "mvn 退出码 " + exit + "，部分模块失败已降级合并");
    }

    private static void cleanStaleOutputs(Path projectDir) {
        try (Stream<Path> walk = Files.walk(projectDir, 3)) {
            walk.filter(f -> f.getFileName() != null && f.getFileName().toString().equals(CP_FILE))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException ignore) {
        }
    }
}
