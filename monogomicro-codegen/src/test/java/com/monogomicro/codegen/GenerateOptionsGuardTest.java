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

class GenerateOptionsGuardTest {

    @TempDir
    Path temp;

    /** 守卫必须在任何删除/生成动作之前触发：目标目录里的既有文件必须原样保留。 */
    @Test
    void nonEmptyTargetWithoutOverwriteIsRejectedBeforeTouch() throws Exception {
        Path out = temp.resolve("out");
        Files.createDirectories(out);
        Path keep = out.resolve("keep.txt");
        Files.writeString(keep, "既有数据");

        MicroserviceGenerator generator = new MicroserviceGenerator(
                new AnalysisReport(), new PartitionResult(), temp);
        M2mException e = assertThrows(M2mException.class,
                () -> generator.generate(out, new GenerateOptions(false, false)));
        assertEquals("E_OUTPUT_EXISTS", e.getCode());
        assertTrue(Files.exists(keep), "守卫触发时不得触碰目标目录");
        assertEquals("既有数据", Files.readString(keep));
    }

    /** 不存在的目标目录视为新目录，守卫放行（后续生成逻辑自行工作）。 */
    @Test
    void freshTargetPassesGuard() {
        MicroserviceGenerator generator = new MicroserviceGenerator(
                new AnalysisReport(), new PartitionResult(), temp);
        // 空工程在守卫之后会因 pom 缺失等原因失败——这里只验证不再抛 E_OUTPUT_EXISTS
        Path fresh = temp.resolve("fresh-out");
        try {
            generator.generate(fresh, new GenerateOptions(false, false));
        } catch (M2mException e) {
            assertEquals(false, "E_OUTPUT_EXISTS".equals(e.getCode()));
        } catch (Exception expected) {
            // 空工程必然失败，任何非守卫异常都说明守卫已放行
        }
    }

    @Test
    void factoryDefaults() {
        assertEquals(false, GenerateOptions.legacy().isDryRun());
        assertEquals(true, GenerateOptions.legacy().isOverwrite());
        assertEquals(true, GenerateOptions.dryRun().isDryRun());
    }
}
