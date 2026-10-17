package com.monogomicro.core.audit;

import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从静态分析结果构建 {@link ServiceLandscape}（现状服务边界）：
 *
 * <ul>
 *   <li>默认：按 {@link ClassInfo#getMavenModule()} 聚合，module 名即服务名
 *       （命名来自 ProjectScanner 的目录推导，非 artifactId）；</li>
 *   <li>启发式：module 名命中 shared 提示词（common/shared/api/client/dto，可配）→ shared 服务；</li>
 *   <li>覆盖：可选 {@link ServicesConfig}（services.json）声明式重命名/合并/标记/排除。</li>
 * </ul>
 *
 * 未被配置命中且未被排除的 module 自成服务。类未携带 module 信息（理论不发生）时归入
 * {@code unnamed} 服务，不静默丢弃。
 */
public final class LandscapeFactory {

    /** module 名包含任一提示词即视为 shared 类库（v2 设计 §5.1，可配）。 */
    private static final Set<String> DEFAULT_SHARED_HINTS = Set.of("common", "shared", "api", "client", "dto");

    private LandscapeFactory() {
    }

    public static ServiceLandscape from(AnalysisReport report) {
        return from(report, null, DEFAULT_SHARED_HINTS);
    }

    public static ServiceLandscape from(AnalysisReport report, ServicesConfig config) {
        return from(report, config, DEFAULT_SHARED_HINTS);
    }

    public static ServiceLandscape from(AnalysisReport report, ServicesConfig config, Set<String> sharedHints) {
        // module → fqcn 集合（排除项在此过滤）
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        List<String> unnamed = new ArrayList<>();
        for (ClassInfo c : report.getClasses()) {
            String module = c.getMavenModule();
            if (module == null || module.isBlank()) {
                unnamed.add(c.getFqcn());
            } else if (config == null || !config.isExcluded(module)) {
                byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(c.getFqcn());
            }
        }

        // module → 最终服务名 / shared 标记
        Map<String, String> moduleOwners = config == null
                ? Map.of()
                : config.resolveModuleOwners(byModule.keySet());
        Map<String, ServiceLandscape.Service> services = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byModule.entrySet()) {
            String module = e.getKey();
            String name = moduleOwners.getOrDefault(module, module);
            boolean shared = looksShared(name, sharedHints)
                    || (config != null && isDeclaredShared(config, name));
            mergeInto(services, name, shared, e.getValue());
        }
        if (!unnamed.isEmpty()) {
            mergeInto(services, "unnamed", false, unnamed);
        }
        return new ServiceLandscape(new ArrayList<>(services.values()));
    }

    /**
     * 词元边界匹配（R-10 修正）：module 名按非字母数字切词元，词元与提示词完全相等才命中。
     * contains 会把 rapid-service（含 "api"）这类业务名误判为 shared。
     * RuoYi 的 ruoyi-api-* / ruoyi-common-* 仍按词元命中。
     */
    private static boolean looksShared(String moduleName, Set<String> hints) {
        for (String token : moduleName.toLowerCase().split("[^a-z0-9]+")) {
            if (hints.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeclaredShared(ServicesConfig config, String serviceName) {
        return config.getServices().stream()
                .anyMatch(s -> serviceName.equals(s.getName()) && s.isShared());
    }

    private static void mergeInto(Map<String, ServiceLandscape.Service> services,
                                  String name, boolean shared, List<String> fqcns) {
        ServiceLandscape.Service existing = services.get(name);
        if (existing == null) {
            services.put(name, new ServiceLandscape.Service(name, new ArrayList<>(fqcns), shared));
            return;
        }
        // 已存在（多 module 合并场景）：shared 一旦为 true 即保持（宁枉勿纵，口径见设计 §5.2）
        List<String> merged = new ArrayList<>(existing.fqcns());
        merged.addAll(fqcns);
        services.put(name, new ServiceLandscape.Service(name, merged, existing.shared() || shared));
    }

    /** landscape 内出现过的全部 module 名（诊断用，按字典序）。 */
    public static Set<String> moduleNames(AnalysisReport report) {
        Set<String> names = new TreeSet<>();
        for (ClassInfo c : report.getClasses()) {
            if (c.getMavenModule() != null && !c.getMavenModule().isBlank()) {
                names.add(c.getMavenModule());
            }
        }
        return names;
    }
}
