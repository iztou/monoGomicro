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
            // A-24：非有限 double（NaN/Infinity）按规范写成 null token，而不是字符串 "NaN"
            //（字符串会破坏 JSON number 契约——消费端拿到的类型随内容漂移）
            .configure(com.fasterxml.jackson.core.JsonGenerator.Feature.QUOTE_NON_NUMERIC_NUMBERS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static {
        // F-06：非有限 double（NaN/Infinity）写 null token——QUOTE_NON_NUMERIC_NUMBERS=false
        // 只会写裸 NaN token，不是合法 JSON（jq/浏览器/默认 Jackson 都读不了）
        MAPPER.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                .addSerializer(Double.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
                    @Override
                    public void serialize(Double value,
                                          com.fasterxml.jackson.core.JsonGenerator gen,
                                          com.fasterxml.jackson.databind.SerializerProvider sp)
                            throws java.io.IOException {
                        if (value != null && !Double.isFinite(value)) {
                            gen.writeNull();
                        } else {
                            gen.writeNumber(value);
                        }
                    }
                })
                // 基本类型 double 字段走不到 Double.class 序列化器——raw cast 注册
                .addSerializer(double.class, (com.fasterxml.jackson.databind.JsonSerializer<Double>) (Object)
                        new com.fasterxml.jackson.databind.JsonSerializer<Double>() {
                    @Override
                    public void serialize(Double value,
                                          com.fasterxml.jackson.core.JsonGenerator gen,
                                          com.fasterxml.jackson.databind.SerializerProvider sp)
                            throws java.io.IOException {
                        if (!Double.isFinite(value)) {
                            gen.writeNull();
                        } else {
                            gen.writeNumber(value);
                        }
                    }
                }));
    }

    private JsonIO() {
    }

    public static void write(Object value, Path file) throws IOException {
        // 统一盖章当前格式版本：旧文件读入（version=0）再写出即升到当前版
        if (value instanceof AnalysisReport r) {
            r.setFormatVersion(AnalysisReport.CURRENT_FORMAT_VERSION);
        } else if (value instanceof PartitionResult p) {
            p.setFormatVersion(PartitionResult.CURRENT_FORMAT_VERSION);
        }
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            // F-14：已存在的父目录（如 /tmp，常为符号链接）再 createDirectories 会抛
            // FileAlreadyExistsException——只在缺失时创建
            Files.createDirectories(parent);
        }
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
