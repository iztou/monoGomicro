package com.monogomicro.core.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 现状架构视图（as-is）：服务 → 类集合。与 {@link com.monogomicro.core.model.PartitionResult}
 * 完全独立的概念——后者是"算法建议的分区"，本类是"工程今天实际的服务边界"。
 *
 * <p>构建后不可变；{@code shared} 服务（common/client 等类库模块）在指标口径上单独处理
 * （对它们的边是编译期依赖，不是跨进程调用），见设计文档 §5.2。
 *
 * @see LandscapeFactory
 */
public final class ServiceLandscape {

    /** 一个部署级服务（或被标记为 shared 的类库模块）。 */
    public record Service(String name, List<String> fqcns, boolean shared) {
    }

    private final List<Service> services;
    /** fqcn → 服务名（含 shared 服务），构建时一次生成。 */
    private final Map<String, String> owner;

    ServiceLandscape(List<Service> services) {
        this.services = List.copyOf(services);
        Map<String, String> index = new LinkedHashMap<>();
        for (Service s : services) {
            for (String fqcn : s.fqcns()) {
                String prev = index.put(fqcn, s.name());
                if (prev != null) {
                    throw new IllegalStateException(
                            "类 " + fqcn + " 同时归属 " + prev + " 与 " + s.name() + "（构建器职责：保证互斥）");
                }
            }
        }
        this.owner = Collections.unmodifiableMap(index);
    }

    public List<Service> services() {
        return services;
    }

    /** 业务服务（非 shared）。 */
    public List<Service> businessServices() {
        return services.stream().filter(s -> !s.shared()).toList();
    }

    /** shared 类库服务。 */
    public List<Service> sharedServices() {
        return services.stream().filter(Service::shared).toList();
    }

    public Optional<String> serviceOf(String fqcn) {
        return Optional.ofNullable(owner.get(fqcn));
    }

    public boolean isSharedService(String serviceName) {
        return services.stream().anyMatch(s -> s.shared() && s.name().equals(serviceName));
    }

    /** landscape 覆盖的类数（含 shared）。 */
    public int classCount() {
        return owner.size();
    }
}
