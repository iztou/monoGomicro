package com.monogomicro.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-05 回归：parent-first POM（Spring Boot 常见形态）的项目坐标必须取
 * &lt;project&gt; 直接子元素，而不是文档序第一个 groupId/version（那是 parent 的）。
 * 根 POM 必须固定为 projectDir/pom.xml（路径排序会把 app/pom.xml 排到根前面）。
 */
class PomReaderCoordinateTest {

    @TempDir
    Path tmp;

    @Test
    void parentFirstPomTakesOwnCoordinates() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("parent-first"));
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.5</version>
                  </parent>
                  <groupId>com.real</groupId>
                  <artifactId>app</artifactId>
                  <version>1.2.3</version>
                </project>""");
        PomReader r = new PomReader();
        r.readProject(project);
        assertEquals("com.real", r.projectGroupId(), "应取项目自身 groupId 而非 parent 的");
        assertEquals("1.2.3", r.projectVersion(), "应取项目自身 version 而非 parent 的");
    }

    @Test
    void rootPomIsProjectRootEvenWhenSortedLater() throws Exception {
        // app/pom.xml 路径排序在 pom.xml 之前（a < p）；坐标仍必须来自根 POM
        Path project = Files.createDirectories(tmp.resolve("multi"));
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.root</groupId><artifactId>root</artifactId><version>9.9.9</version>
                </project>""");
        Files.writeString(project.resolve("app/pom.xml"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.root</groupId><artifactId>root</artifactId><version>9.9.9</version>
                  </parent>
                  <artifactId>app</artifactId>
                </project>""");
        PomReader r = new PomReader();
        r.readProject(project);
        assertEquals("com.root", r.projectGroupId());
        assertEquals("9.9.9", r.projectVersion());
    }

    @Test
    void coordinateInheritanceFallsBackToParent() throws Exception {
        // 无自身 groupId/version 时回退 parent 声明（继承语义）
        Path project = Files.createDirectories(tmp.resolve("inherited"));
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.parent</groupId><artifactId>p</artifactId><version>2.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>""");
        PomReader r = new PomReader();
        r.readProject(project);
        assertEquals("com.parent", r.projectGroupId());
        assertEquals("2.0.0", r.projectVersion());
    }
}
