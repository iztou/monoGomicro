package com.monogomicro.core.model;

import java.util.Locale;
import java.util.Set;

/**
 * 类构造型。决定图视图中的"层"（UI / 业务 / 数据 / 共享）与生成微服务时的角色。
 * 识别靠注解名 + 命名惯例 + 包路径，不解析注解语义，保证对任意项目有 best-effort 结果。
 */
public enum ClassStereotype {
    CONTROLLER("controller"),
    SERVICE("service"),
    SERVICE_IMPL("service"),
    MAPPER("data"),
    DOMAIN("data"),
    ENTITY("data"),
    CONFIG("shared"),
    ASPECT("shared"),
    UTIL("shared"),
    OTHER("shared");

    /** 图视图的分层归属 */
    private final String tier;

    ClassStereotype(String tier) {
        this.tier = tier;
    }

    public String tier() {
        return tier;
    }

    public static ClassStereotype detect(String simpleName, String packageName, boolean isInterface,
                                         Set<String> annotations, String superClassName) {
        String name = simpleName.toLowerCase(Locale.ROOT);
        String pkg = packageName.toLowerCase(Locale.ROOT);
        Set<String> ann = annotations.stream().map(a -> a.toLowerCase(Locale.ROOT))
                .map(a -> a.substring(a.lastIndexOf('.') + 1)).collect(java.util.stream.Collectors.toSet());

        if (ann.contains("restcontroller") || ann.contains("controller") || name.endsWith("controller")) {
            return CONTROLLER;
        }
        if (ann.contains("aspect")) {
            return ASPECT;
        }
        if (ann.contains("mapper") || name.endsWith("mapper") || name.endsWith("dao")) {
            return MAPPER;
        }
        if ((ann.contains("service") && !isInterface) || name.endsWith("serviceimpl")) {
            return SERVICE_IMPL;
        }
        if (isInterface && name.startsWith("i") && name.endsWith("service")) {
            return SERVICE;
        }
        if (pkg.contains(".domain.") || pkg.contains(".entity.") || pkg.contains(".model.")
                || name.endsWith("domain") || name.endsWith("entity")
                || (superClassName != null && superClassName.toLowerCase(Locale.ROOT).endsWith(".BaseEntity"))) {
            return DOMAIN;
        }
        if (ann.contains("configuration") || name.endsWith("config") || name.endsWith("configuration")) {
            return CONFIG;
        }
        if (pkg.contains(".util") || name.endsWith("util") || name.endsWith("utils")) {
            return UTIL;
        }
        return OTHER;
    }
}
