package com.monogomicro.core.audit;

import com.monogomicro.core.M2mException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * I-02：services.json 的 null 元素必须给 E_BAD_SERVICES_JSON 而不是裸 NPE——
 * services:[null]（元素 null）与 exclude:[null] 两个入口曾绕过 validate 直接炸在
 * getName()/globMatch 上。
 */
class ServicesConfigNullElementTest {

    @Test
    void nullServiceEntryRejected() {
        ServicesConfig cfg = new ServicesConfig();
        List<ServicesConfig.ServiceEntry> services = new ArrayList<>();
        services.add(null);
        cfg.setServices(services);
        assertThrows(M2mException.class, cfg::validate, "services:[null] 应抛 E_BAD_SERVICES_JSON");
    }

    @Test
    void nullExcludePatternRejected() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setExclude(Arrays.asList("com.ok.**", null));
        assertThrows(M2mException.class, cfg::validate, "exclude:[null] 应抛 E_BAD_SERVICES_JSON");
    }

    /** F-11：modules:[null] 曾在 globMatch 上裸 NPE（pattern 为 null）。 */
    @Test
    void nullModulePatternRejected() {
        ServicesConfig cfg = new ServicesConfig();
        ServicesConfig.ServiceEntry entry = new ServicesConfig.ServiceEntry();
        entry.setName("svc");
        entry.setModules(Arrays.asList("ruoyi-system", null));
        cfg.setServices(List.of(entry));
        assertThrows(M2mException.class, cfg::validate, "modules:[null] 应抛 E_BAD_SERVICES_JSON");
    }

    @Test
    void auditorValidatesConfigInternally() {
        // I-03：ArchitectureAuditor.audit 公共 API 必须内校验——库调用方绕过 CLI 也传不进非法阈值
        AuditConfig cfg = new AuditConfig();
        cfg.setMergePairRatio(Double.POSITIVE_INFINITY);
        assertThrows(M2mException.class, () ->
                ArchitectureAuditor.audit(new com.monogomicro.core.graph.DependencyGraph(),
                        new ServiceLandscape(List.of()), cfg));
    }
}
