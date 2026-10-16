package com.monogomicro.core.report;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatVersionTest {

    @TempDir
    Path temp;

    @Test
    void writeStampsCurrentVersion() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setProject("/demo");
        Path file = temp.resolve("analysis.json");
        JsonIO.write(report, file);

        assertTrue(Files.readString(file).contains("\"formatVersion\""));
        assertEquals(AnalysisReport.CURRENT_FORMAT_VERSION, JsonIO.read(file, AnalysisReport.class).getFormatVersion());

        Path pFile = temp.resolve("partition.json");
        JsonIO.write(new PartitionResult(), pFile);
        assertEquals(PartitionResult.CURRENT_FORMAT_VERSION, JsonIO.read(pFile, PartitionResult.class).getFormatVersion());
    }

    @Test
    void legacyFileReadsAsV1AndUpgradesOnRewrite() throws Exception {
        // v1 旧文件：无 formatVersion 字段
        String legacy = """
                {
                  "project": "/ruoyi",
                  "classes": [
                    {"fqcn": "com.x.A", "mavenModule": "mod-a", "sourcePath": "a.java"},
                    {"fqcn": "com.x.B", "mavenModule": "mod-b", "sourcePath": "b.java"}
                  ],
                  "edges": []
                }
                """;
        Path file = temp.resolve("legacy.json");
        Files.writeString(file, legacy);

        AnalysisReport report = JsonIO.read(file, AnalysisReport.class);
        assertEquals(0, report.getFormatVersion()); // 0 = v1 标记
        // mavenModules 缺失时由 classes 惰性聚合（自愈）
        assertEquals(List.of("mod-a", "mod-b"), report.getMavenModules());

        // 旧文件重写后升级到当前版本
        JsonIO.write(report, temp.resolve("upgraded.json"));
        assertEquals(AnalysisReport.CURRENT_FORMAT_VERSION,
                JsonIO.read(temp.resolve("upgraded.json"), AnalysisReport.class).getFormatVersion());
    }

    @Test
    void mavenModulesExplicitValueWins() {
        AnalysisReport report = new AnalysisReport();
        report.setClasses(List.of(new ClassInfo("com.x.A", "mod-a", "a.java")));
        report.setMavenModules(List.of("custom"));
        assertEquals(List.of("custom"), report.getMavenModules());
    }
}
