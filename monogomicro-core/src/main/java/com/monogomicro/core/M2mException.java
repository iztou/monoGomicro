package com.monogomicro.core;

/**
 * 结构化业务异常：code 供上层（CLI / web / MCP）映射为机器可读错误，
 * hint 给出可执行的下一步建议。工具层应捕获并转译，而不是透传堆栈。
 */
public class M2mException extends RuntimeException {

    private final String code;
    private final String hint;

    public M2mException(String code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public String getCode() { return code; }
    public String getHint() { return hint; }
}
