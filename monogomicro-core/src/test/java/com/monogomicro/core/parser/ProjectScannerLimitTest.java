package com.monogomicro.core.parser;

import com.monogomicro.core.M2mException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectScannerLimitTest {

    @TempDir
    Path project;

    private void writeClass(String pkg, String name, String body) throws IOException {
        Path dir = project.resolve("src/main/java/" + pkg.replace('.', '/'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"),
                "package " + pkg + ";\nclass " + name + " { " + body + " }");
    }

    @Test
    void sourceFileLimitTriggersStructuredError() throws Exception {
        writeClass("com.example", "A", "");
        writeClass("com.example", "B", "");
        M2mException e = assertThrows(M2mException.class,
                () -> new ProjectScanner(List.of("com.example"), false, 1, 100_000).scan(project));
        assertEquals("E_SCAN_LIMIT", e.getCode());
    }

    @Test
    void edgeLimitTriggersStructuredError() throws Exception {
        writeClass("com.example", "A", "B b = new B(); C c = new C();");
        writeClass("com.example", "B", "");
        writeClass("com.example", "C", "");
        M2mException e = assertThrows(M2mException.class,
                () -> new ProjectScanner(List.of("com.example"), false, 10, 1).scan(project));
        assertEquals("E_SCAN_LIMIT", e.getCode());
    }
}
