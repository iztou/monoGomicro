package com.monogomicro.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.monogomicro.core.M2mException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * services.json 契约：对"按 Maven module 自动推导的现状边界"的声明式修正
 * （module 重命名 / 多 module 合并为一个服务 / 标记 shared / 排除无关模块）。
 *
 * <p>modules 匹配支持三种写法（对齐 {@code ProjectScanner.moduleNameOf} 的目录推导命名，
 * 嵌套布局下形如 {@code ruoyi-modules-ruoyi-system}）：精确名、含 {@code *} 的通配
 * （前缀 / 后缀 / 包含）、以及二者混用；exclude 为纯 glob 列表。
 *
 * <p>解析容错遵循 JsonIO 惯例（未知字段忽略）；契约自带 formatVersion，只升不破。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ServicesConfig {

    /** 当前契约版本；只用于向前声明，读入不拒绝旧版。 */
    public static final int CURRENT_FORMAT_VERSION = 1;

    private int formatVersion = CURRENT_FORMAT_VERSION;
    private List<ServiceEntry> services = new ArrayList<>();
    private List<String> exclude = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceEntry {
        private String name;
        private List<String> modules = new ArrayList<>();
        private boolean shared;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getModules() {
            return modules;
        }

        public void setModules(List<String> modules) {
            this.modules = modules;
        }

        public boolean isShared() {
            return shared;
        }

        public void setShared(boolean shared) {
            this.shared = shared;
        }
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public List<ServiceEntry> getServices() {
        return services;
    }

    public void setServices(List<ServiceEntry> services) {
        this.services = services;
    }

    public List<String> getExclude() {
        return exclude;
    }

    public void setExclude(List<String> exclude) {
        this.exclude = exclude;
    }

    /** 校验必填项，给出口径统一的业务错误。 */
    public void validate() {
        Set<String> names = new LinkedHashSet<>();
        for (ServiceEntry s : services == null ? List.<ServiceEntry>of() : services) {
            // I-02：services:[null] 曾裸 NPE（s.getName() 打在 null 元素上）
            if (s == null) {
                throw new M2mException("E_BAD_SERVICES_JSON", "services 含 null 元素", null);
            }
            if (s.getName() == null || s.getName().isBlank()) {
                throw new M2mException("E_BAD_SERVICES_JSON", "services[].name 不能为空", null);
            }
            if (!names.add(s.getName())) {
                throw new M2mException("E_BAD_SERVICES_JSON", "服务名重复声明：" + s.getName(), null);
            }
            if (s.getModules() == null || s.getModules().isEmpty()) {
                throw new M2mException("E_BAD_SERVICES_JSON",
                        "服务 " + s.getName() + " 缺少 modules（至少一个匹配模式）", null);
            }
            // F-11：modules:[null] 曾裸 NPE（globMatch 对 null pattern）
            for (String pattern : s.getModules()) {
                if (pattern == null || pattern.isBlank()) {
                    throw new M2mException("E_BAD_SERVICES_JSON",
                            "服务 " + s.getName() + " 的 modules 含 null/空模式", null);
                }
            }
        }
        // I-02：exclude:[null] 同样裸 NPE（globMatch 打在 null 上）
        if (exclude != null) {
            for (String pattern : exclude) {
                if (pattern == null || pattern.isBlank()) {
                    throw new M2mException("E_BAD_SERVICES_JSON", "exclude 含 null/空模式", null);
                }
            }
        }
    }

    /**
     * module → 命中的服务名（按声明顺序首个命中，行为稳定可预期）。
     * 仅供 LandscapeFactory 内部与测试使用。
     */
    Map<String, String> resolveModuleOwners(Set<String> moduleNames) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String module : moduleNames) {
            for (ServiceEntry s : services == null ? List.<ServiceEntry>of() : services) {
                if (s.getModules().stream().anyMatch(p -> globMatch(p, module))) {
                    owners.put(module, s.getName());
                    break;
                }
            }
        }
        return owners;
    }

    /** module 是否被 exclude 命中（A-27：null 列表按空处理，不再裸 NPE）。 */
    boolean isExcluded(String module) {
        return exclude == null ? false : exclude.stream().anyMatch(p -> globMatch(p, module));
    }

    /** 极简 glob：{@code *} 匹配任意字符序列；无通配符时退化为精确匹配。 */
    static boolean globMatch(String pattern, String value) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(value);
        }
        String prefix = pattern.substring(0, star);
        String rest = pattern.substring(star + 1);
        if (!value.startsWith(prefix)) {
            return false;
        }
        int next = rest.indexOf('*');
        while (next >= 0) {
            String segment = rest.substring(0, next);
            int at = value.indexOf(segment, prefix.length());
            if (at < 0) {
                return false;
            }
            prefix = value.substring(0, at + segment.length());
            rest = rest.substring(next + 1);
            next = rest.indexOf('*');
        }
        return value.endsWith(rest) && value.length() >= prefix.length() + rest.length();
    }
}
