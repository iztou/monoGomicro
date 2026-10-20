package com.monogomicro.codegen;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-03 回归（CI 首跑暴露）：原单体（Spring Boot 单体必然）自己声明 spring-boot-starter-web，
 * 生成器又在服务模块固定写入一份——重复依赖声明在部分 Maven 版本是 model 校验错误
 * （CI 直接 "must be unique" 拒绝构建），本地 3.9.16 只当警告所以一直没炸。
 * 服务模块依赖必须按 groupId:artifactId 去重，硬编码的（无版本、走 BOM）优先。
 */
class GeneratedPomDependencyDedupTest {

    @TempDir
    Path temp;

    @Test
    void starterWebFromOriginalPomIsNotDuplicatedInServicePoms() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.demo</groupId><artifactId>mono</artifactId><version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.3.5</version>
                                <type>pom</type><scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>""");
        Files.writeString(project.resolve("src/main/java/demo/App.java"),
                "package demo;\npublic class App { public void run() {} }\n");

        AnalysisReport report = new AnalysisReport();
        report.setProject(project.toString());
        report.setClasses(List.of(new ClassInfo("demo.App", "mono", "src/main/java/demo/App.java")));
        report.setEdges(List.of());

        PartitionResult partition = new PartitionResult();
        PartitionResult.Community c = new PartitionResult.Community();
        c.setId(0);
        c.setName("app");
        c.setClasses(new ArrayList<>(List.of("demo.App")));
        partition.setCommunities(List.of(c));

        Path out = temp.resolve("out");
        new MicroserviceGenerator(report, partition, project).generate(out);

        Path svcPom = out.resolve("svc-app/pom.xml");
        assertTrue(Files.isRegularFile(svcPom), "服务 POM 应生成");
        String pom = Files.readString(svcPom);
        int occurrences = pom.split("<artifactId>spring-boot-starter-web</artifactId>", -1).length - 1;
        assertEquals(1, occurrences,
                "starter-web 在服务 POM 里只能出现一次（原单体声明 + 生成器固定写入会重复）；POM:\n" + pom);
        // common 依赖也只出现一次（聚合构件名 = <目录名>-microservices-common）
        assertEquals(1, pom.split("<artifactId>project-microservices-common</artifactId>", -1).length - 1);
    }
}
