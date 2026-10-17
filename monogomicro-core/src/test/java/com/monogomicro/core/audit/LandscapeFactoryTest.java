package com.monogomicro.core.audit;

import com.monogomicro.core.M2mException;
import com.monogomicro.core.model.AnalysisReport;
import com.monogomicro.core.model.ClassInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1：现状边界建模——聚合、启发 shared、services.json 覆盖（精确/通配/合并）、
 * exclude、嵌套命名对齐（moduleNameOf 目录推导产出形如 ruoyi-modules-ruoyi-system）。
 */
class LandscapeFactoryTest {

    private static AnalysisReport reportOf(ClassInfo... classes) {
        AnalysisReport r = new AnalysisReport();
        r.setClasses(List.of(classes));
        return r;
    }

    private static ClassInfo cls(String fqcn, String module) {
        return new ClassInfo(fqcn, module, "src/" + fqcn.replace('.', '/') + ".java");
    }

    private static ServicesConfig.ServiceEntry entry(String name, boolean shared, String... modules) {
        ServicesConfig.ServiceEntry e = new ServicesConfig.ServiceEntry();
        e.setName(name);
        e.setModules(List.of(modules));
        e.setShared(shared);
        return e;
    }

    @Test
    void aggregatesByMavenModuleByDefault() {
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.a.Foo", "svc-a"), cls("com.a.Bar", "svc-a"), cls("com.b.Baz", "svc-b")));
        assertEquals(2, l.services().size());
        assertEquals(List.of("com.a.Foo", "com.a.Bar"),
                l.services().get(0).fqcns());
        assertEquals("svc-b", l.serviceOf("com.b.Baz").orElseThrow());
        assertEquals(3, l.classCount());
    }

    @Test
    void marksSharedModuleByHint() {
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.x.UserDto", "ruoyi-common"), cls("com.x.Svc", "ruoyi-system")));
        assertTrue(l.isSharedService("ruoyi-common"));
        assertFalse(l.isSharedService("ruoyi-system"));
        assertEquals(1, l.sharedServices().size());
        assertEquals(1, l.businessServices().size());
    }

    @Test
    void nestedModuleNameStaysAsDerived() {
        // moduleNameOf 的目录推导：嵌套布局产出带路径前缀的名——landscape 原样使用
        ServiceLandscape l = LandscapeFactory.from(reportOf(cls("com.x.A", "ruoyi-modules-ruoyi-system")));
        assertEquals("ruoyi-modules-ruoyi-system", l.services().get(0).name());
    }

    @Test
    void configMergesAndRenamesModules() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setServices(List.of(entry("user-service", false,
                "ruoyi-modules-ruoyi-system", "ruoyi-modules-ruoyi-system-account")));
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.x.A", "ruoyi-modules-ruoyi-system"),
                cls("com.x.B", "ruoyi-modules-ruoyi-system-account"),
                cls("com.x.C", "ruoyi-job")), cfg);
        assertEquals("user-service", l.serviceOf("com.x.A").orElseThrow());
        assertEquals("user-service", l.serviceOf("com.x.B").orElseThrow());
        assertEquals(2, l.services().size()); // user-service + 自成服务的 ruoyi-job
        assertEquals(2, l.services().get(0).fqcns().size());
    }

    @Test
    void configWildcardAndExplicitSharedFlag() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setServices(List.of(
                entry("platform", true, "ruoyi-common", "*-client"),
                entry("user-service", false, "ruoyi-modules-*")));
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.x.D", "ruoyi-common"),
                cls("com.x.E", "pay-client"),
                cls("com.x.F", "ruoyi-modules-ruoyi-system")), cfg);
        assertTrue(l.isSharedService("platform"));
        assertEquals("platform", l.serviceOf("com.x.D").orElseThrow());
        assertEquals("platform", l.serviceOf("com.x.E").orElseThrow());
        assertEquals("user-service", l.serviceOf("com.x.F").orElseThrow());
    }

    @Test
    void excludedModuleDropsItsClasses() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setExclude(List.of("test-*"));
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.x.A", "svc-main"), cls("com.x.T", "test-fixture")), cfg);
        assertEquals(1, l.services().size());
        assertNull(l.serviceOf("com.x.T").orElse(null));
        assertEquals(1, l.classCount());
    }

    @Test
    void undeclaredModuleBecomesOwnService() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setServices(List.of(entry("known", false, "mod-a")));
        ServiceLandscape l = LandscapeFactory.from(reportOf(
                cls("com.x.A", "mod-a"), cls("com.x.B", "mod-other")), cfg);
        assertEquals(2, l.services().size());
        assertEquals("mod-other", l.serviceOf("com.x.B").orElseThrow());
    }

    @Test
    void firstDeclaredOwnerWinsOnOverlappingPatterns() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setServices(List.of(
                entry("first", false, "mod-*"),
                entry("second", false, "mod-b")));
        ServiceLandscape l = LandscapeFactory.from(reportOf(cls("com.x.B", "mod-b")), cfg);
        assertEquals("first", l.serviceOf("com.x.B").orElseThrow());
    }

    @Test
    void validateRejectsMissingNameAndModules() {
        ServicesConfig cfg = new ServicesConfig();
        cfg.setServices(List.of(entry("  ", false, "x")));
        assertThrows(M2mException.class, cfg::validate);

        ServicesConfig empty = new ServicesConfig();
        ServicesConfig.ServiceEntry noModules = new ServicesConfig.ServiceEntry();
        noModules.setName("x");
        empty.setServices(List.of(noModules));
        assertThrows(M2mException.class, empty::validate);
    }

    @Test
    void landscapeRejectsDuplicateClassOwnership() {
        assertThrows(IllegalStateException.class, () -> new ServiceLandscape(List.of(
                new ServiceLandscape.Service("a", List.of("com.X"), false),
                new ServiceLandscape.Service("b", List.of("com.X"), false))));
    }

    @Test
    void globMatchCoversExactPrefixSuffixAndMiddle() {
        assertTrue(ServicesConfig.globMatch("mod-a", "mod-a"));
        assertFalse(ServicesConfig.globMatch("mod-a", "mod-ab"));
        assertTrue(ServicesConfig.globMatch("ruoyi-modules-*", "ruoyi-modules-ruoyi-system"));
        assertFalse(ServicesConfig.globMatch("ruoyi-modules-*", "ruoyi-common"));
        assertTrue(ServicesConfig.globMatch("*-client", "pay-client"));
        assertTrue(ServicesConfig.globMatch("*modules*", "ruoyi-modules-ruoyi-system"));
        assertTrue(ServicesConfig.globMatch("a*b*c", "aXXbYYc"));
        assertFalse(ServicesConfig.globMatch("a*b*c", "acb"));
        // glob 标准语义：* 匹配零个或多个字符（fnmatch 一致）；实际不会有空 module 名，锁语义防回归
        assertTrue(ServicesConfig.globMatch("*", ""));
        assertTrue(ServicesConfig.globMatch("*", "anything"));
    }
}
