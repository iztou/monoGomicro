package com.monogomicro.web;

import com.monogomicro.core.M2mException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A-12：业务错误映射——{@link M2mException} 曾被默认处理器包装成无信息的 500，
 * E_OUTPUT_EXISTS 等错误码在 UI 完全不可见。统一映射为 4xx + {code, message, hint}，
 * 与 MCP 的结构化错误契约同形。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(M2mException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(M2mException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        if (e.getHint() != null) {
            body.put("hint", e.getHint());
        }
        boolean badInput = e.getCode() != null && (e.getCode().startsWith("E_BAD_")
                || e.getCode().equals("E_OUTPUT_EXISTS") || e.getCode().equals("E_OUT_OF_WORKSPACE"));
        return ResponseEntity.status(badInput ? 400 : 409).body(body);
    }
}
