package com.monogomicro.core;

/**
 * 运行时版本号：取所在 jar manifest 的 Implementation-Version（构建时由 Maven ${project.version} 写入，
 * cli/mcp 的 fat jar 在 shade transformer 里统一注入）。IDE 或测试等非 jar 运行场景回退 "dev"。
 */
public final class Versions {

    private Versions() {
    }

    public static String get() {
        String v = Versions.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
