package com.monogomicro.core.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.PartitionResult;

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
        // 统一盖章当前格式版本：旧文件读入（version=0）再写出即升到当前版
        if (value instanceof AnalysisReport r) {
            r.setFormatVersion(AnalysisReport.CURRENT_FORMAT_VERSION);
        } else if (value instanceof PartitionResult p) {
            p.setFormatVersion(PartitionResult.CURRENT_FORMAT_VERSION);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        MAPPER.writeValue(file.toFile(), value);
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }

    /** 展示用 mapper（缩进）：落盘文件与人读输出。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 协议/机器通道用 mapper：禁止缩进——多行 JSON 会破坏按行分帧的协议
     * （stdio JSON-RPC 每行一帧、SSE 的 data: 行载荷必须是单行，否则不是合法 SSE）。
     */
    public static ObjectMapper compactMapper() {
        return MAPPER.copy().disable(SerializationFeature.INDENT_OUTPUT);
    }
}
