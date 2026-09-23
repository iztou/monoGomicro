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

    public static List<Path> resolve(Path projectDir) {
        if (!Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            log.warn("目标目录没有 pom.xml，跳过 Maven classpath 解析（仅源码+JDK 反射解析）");
            return List.of();
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
                return List.of();
            }
            exit = p.exitValue();
        } catch (IOException | InterruptedException e) {
            log.warn("执行 mvn 失败：{}，跳过 classpath 解析", e.getMessage());
            return List.of();
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
        } else {
            log.info("Maven classpath：{} 个依赖 jar{}",
                    jars.size(), exit == 0 ? "" : "（部分模块失败，已降级合并可用部分）");
        }
        return new ArrayList<>(jars);
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
