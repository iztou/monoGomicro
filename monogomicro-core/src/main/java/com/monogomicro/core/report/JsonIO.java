package com.monogomicro.core.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON 读写门面。pretty-print 输出，读取忽略未知字段（版本间兼容）。 */
public final class JsonIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonIO() {
    }

    public static void write(Object value, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        MAPPER.writeValue(file.toFile(), value);
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
