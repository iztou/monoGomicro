package com.monogomicro.codegen;

import com.monogomicro.core.M2mException;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-01 回归：生成器删除行为的安全边界。
 *
 * <ul>
 *   <li>守卫矩阵：空路径 / 当前工作目录 / 原项目根 / 包含二者的祖先目录 → 删除前拒绝（E_BAD_OUT_DIR）；</li>
 *   <li>顺序纪律：任何会让生成失败的输入校验（如缺 POM）必须发生在删除目标目录之前——
 *       失败后目标目录内的既有文件必须原样保留；</li>
 *   <li>默认不覆盖：目标非空且未显式 overwrite → E_OUTPUT_EXISTS。</li>
 * </ul>
 */
class GenerateGuardTest {

    @TempDir
    Path tmp;

    private static AnalysisReport emptyReport() {
        return new AnalysisReport();
    }

    @Test
    void emptyOutDirIsRejectedBeforeDeletion() throws Exception {
        // 空路径等价当前工作目录——曾经实测清空了 cwd（R-01 复现步骤 B）
        MicroserviceGenerator g = new MicroserviceGenerator(emptyReport(), new PartitionResult(), tmp);
        M2mException e = assertThrows(M2mException.class, () -> g.generate(Path.of("")));
        assertEquals("E_BAD_OUT_DIR", e.getCode());
    }

    @Test
    void projectRootAndAncestorsAreRejected() throws Exception {
        MicroserviceGenerator g = new MicroserviceGenerator(emptyReport(), new PartitionResult(), tmp);
        assertEquals("E_BAD_OUT_DIR", assertThrows(M2mException.class,
                () -> g.generate(tmp)).getCode());
        assertEquals("E_BAD_OUT_DIR", assertThrows(M2mException.class,
                () -> g.generate(tmp.getParent())).getCode()); // 祖先（含 @TempDir 的父）
        assertEquals("E_BAD_OUT_DIR", assertThrows(M2mException.class,
                () -> g.generate(Path.of("").toAbsolutePath())).getCode()); // cwd
    }

    @Test
    void validationFailureKeepsTargetDirectoryIntact() throws Exception {
        // 原"先删后校验"bug 的核心场景：无 POM 的项目 + 有效子目录里有文件 →
        // 生成失败，但目录内容必须保留（修复前 keep.txt 会被删掉）
        Path project = Files.createDirectories(tmp.resolve("nopom-project"));
        Path out = Files.createDirectories(tmp.resolve("scratch-out"));
        Files.writeString(out.resolve("keep.txt"), "must survive");
        MicroserviceGenerator g = new MicroserviceGenerator(emptyReport(), new PartitionResult(), project);
        assertThrows(Exception.class, () -> g.generate(out));
        assertTrue(Files.exists(out.resolve("keep.txt")),
                "输入校验失败的生成不得删除目标目录内容（R-01）");
    }

    @Test
    void nonEmptyTargetRequiresExplicitOverwrite() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("p1"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.t</groupId><artifactId>t</artifactId><version>1</version>
                </project>""");
        Path out = Files.createDirectories(tmp.resolve("occupied"));
        Files.writeString(out.resolve("existing.txt"), "x");
        MicroserviceGenerator g = new MicroserviceGenerator(emptyReport(), new PartitionResult(), project);
        M2mException e = assertThrows(M2mException.class,
                () -> g.generate(out, new GenerateOptions(false, false)));
        assertEquals("E_OUTPUT_EXISTS", e.getCode());
        assertTrue(Files.exists(out.resolve("existing.txt")), "拒绝覆盖时不得改动既有目录");
    }
}
