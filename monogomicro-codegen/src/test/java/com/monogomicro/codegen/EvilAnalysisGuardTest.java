package com.monogomicro.codegen;

import com.monogomicro.core.M2mException;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-01/F-07 回归：analysis.json 是用户/LLM 可编辑输入，生成器不得被其内容诱导。
 *
 * F-01（A-04 残余）：packageName 携带 "../"、sourcePath 绝对路径时，copyClasses 与
 * writeApplicationClass 曾把文件写到 outDir 之外（任意位置写盘）。三层防线：
 * 包名合法性、sourcePath 相对且不出 projectDir、目标路径 normalize+startsWith 模块根。
 *
 * F-07（A-07 残余）：outDir 包含受保护输入（-a/-p 指向的文件）时必须在清空重建前拒绝，
 * 否则 "--overwrite + -o 输入所在目录" 会静默删掉本次输入。
 */
class EvilAnalysisGuardTest {

    @TempDir
    Path temp;

    private Path fixtureProject() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/evil"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.demo</groupId><artifactId>t</artifactId><version>1</version>
                </project>""");
        Files.writeString(project.resolve("src/main/java/evil/A.java"), """
                package evil;
                public class A { public void m() {} }
                """);
        return project;
    }

    /** 恶意 packageName/sourcePath：生成可以完成，但 outDir 之外不得出现任何文件。 */
    @Test
    void traversalPackageAndAbsoluteSourceWriteNothingOutsideOutDir() throws Exception {
        Path project = fixtureProject();
        Path out = temp.resolve("out");
        Path escapeTarget = temp.resolve("evil-escape"); // "../.." 从 out 出发的落点

        AnalysisReport report = new AnalysisReport();
        report.setProject(project.toString());
        // fqcn 看似正常，packageName 字段被直接改成穿越串（Jackson 可变字段，恶意 JSON 可达）
        ClassInfo evil = new ClassInfo("evil.A", "demo", "src/main/java/evil/A.java");
        evil.setPackageName("../../../../evil-escape");
        // sourcePath 绝对路径：projectDir.resolve(绝对路径) 曾原样接受、读到项目外文件
        ClassInfo absSource = new ClassInfo("evil.B", "demo", project.resolve("src/main/java/evil/A.java").toString());
        report.setClasses(List.of(evil, absSource));
        report.setEdges(List.of());

        PartitionResult partition = new PartitionResult();
        PartitionResult.Community c = new PartitionResult.Community();
        c.setId(0);
        c.setName("evilsvc");
        c.setClasses(new ArrayList<>(List.of("evil.A", "evil.B")));
        partition.setCommunities(List.of(c));

        GenerationResult result = new MicroserviceGenerator(report, partition, project).generate(out);

        // 逃逸目标不得出现（修复前：copyClasses/writeApplicationClass 都会在 outDir 外落文件）
        assertTrue(Files.notExists(escapeTarget), "包名穿越不得写出 outDir：" + escapeTarget);
        // 全树扫描：除 fixture 源码外，任何 java 文件都必须在 outDir 内
        try (Stream<Path> all = Files.walk(temp)) {
            all.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(project))
                    .forEach(p -> assertTrue(p.startsWith(out),
                            "outDir 之外出现生成文件：" + p));
        }
        // 非法包名的类进人工项，而不是被拷贝
        assertTrue(result.getManualItems().stream().anyMatch(m -> m.contains("evil.A")),
                "非法包名类应列入人工项；实际：" + result.getManualItems());
        // 全部包名非法时 commonPackagePrefix 回退 com.generated，
        // Application 必须落在 outDir 内（修复前用未校验前缀拼路径、可逃逸到 outDir 外）
        assertTrue(Files.isRegularFile(out.resolve(
                        "svc-evilsvc/src/main/java/com/generated/M2mEvilsvcApplication.java")),
                "Application 应落在回退包 com.generated 下");
    }

    /** outDir 包含输入文件：清空重建前必须拒绝，输入原样保留。 */
    @Test
    void outDirContainingProtectedInputIsRejectedBeforeDelete() throws Exception {
        Path project = fixtureProject();
        Path out = temp.resolve("out");
        Files.createDirectories(out);
        Path analysis = out.resolve("analysis.json");
        Files.writeString(analysis, "{\"classes\":[]}");

        MicroserviceGenerator generator = new MicroserviceGenerator(
                new AnalysisReport(), new PartitionResult(), project);
        M2mException e = assertThrows(M2mException.class, () ->
                generator.generate(out, new GenerateOptions(false, true).protectedInputs(analysis)));
        assertEquals("E_BAD_OUT_DIR", e.getCode());
        assertTrue(Files.exists(analysis), "输入文件必须原样保留");
        assertEquals("{\"classes\":[]}", Files.readString(analysis));
    }
}
