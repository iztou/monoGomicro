package com.monogomicro.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** 类节点：依赖图的节点。字段可变以支持 Jackson 往返序列化。 */
@JsonPropertyOrder({"fqcn", "simpleName", "packageName", "mavenModule", "stereotype", "tier", "sourcePath", "superClass", "interfaces"})
public class ClassInfo {
    private String fqcn;
    private String simpleName;
    private String packageName;
    private String mavenModule;
    private String sourcePath;
    private ClassStereotype stereotype;
    private String superClass;
    private List<String> interfaces = List.of();

    public ClassInfo() {
    }

    public ClassInfo(String fqcn, String mavenModule, String sourcePath) {
        this.fqcn = fqcn;
        this.simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        this.packageName = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        this.mavenModule = mavenModule;
        this.sourcePath = sourcePath;
    }

    public String getFqcn() { return fqcn; }
    public void setFqcn(String fqcn) { this.fqcn = fqcn; }
    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getMavenModule() { return mavenModule; }
    public void setMavenModule(String mavenModule) { this.mavenModule = mavenModule; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public ClassStereotype getStereotype() { return stereotype; }
    public void setStereotype(ClassStereotype stereotype) { this.stereotype = stereotype; }
    public String getSuperClass() { return superClass; }
    public void setSuperClass(String superClass) { this.superClass = superClass; }
    public List<String> getInterfaces() { return interfaces; }
    public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }
    public String getTier() { return stereotype == null ? "shared" : stereotype.tier(); }
}
