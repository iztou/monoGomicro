package com.monogomicro.core.report;

import com.monogomicro.core.model.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-06 回归：非有限 double（NaN/Infinity）必须序列化为合法 JSON（null token），
 * 且 JsonIO 自己能读回——裸 NaN token 对 jq/浏览器/默认 Jackson 都是非法 JSON。
 */
class NonFiniteDoubleSerializationTest {

    @TempDir
    Path temp;

    @Test
    void nanWritesAsNullTokenAndReadsBack() throws Exception {
        PartitionResult p = new PartitionResult();
        p.setModularity(Double.NaN);
        Path file = temp.resolve("partition-nan.json");
        JsonIO.write(p, file);

        String json = Files.readString(file);
        assertFalse(json.contains(": NaN"), "不得写出裸 NaN token（非法 JSON）：" + json);
        assertTrue(json.contains("\"modularity\" : null") || json.contains("\"modularity\":null"),
                "NaN 应写成 null token：" + json);
        // round-trip：自己的 reader 必须能读回（null → 基本类型 double 走 Jackson 默认 0.0）
        assertDoesNotThrow(() -> JsonIO.read(file, PartitionResult.class));
    }

    @Test
    void infinityAlsoWritesAsNullToken() throws Exception {
        PartitionResult p = new PartitionResult();
        p.setModularity(Double.POSITIVE_INFINITY);
        Path file = temp.resolve("partition-inf.json");
        JsonIO.write(p, file);

        String json = Files.readString(file);
        assertFalse(json.contains("Infinity"), "不得写出 Infinity token：" + json);
        assertDoesNotThrow(() -> JsonIO.read(file, PartitionResult.class));
    }
}
