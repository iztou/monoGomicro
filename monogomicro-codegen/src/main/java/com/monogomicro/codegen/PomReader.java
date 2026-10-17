package com.monogomicro.codegen;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从原单体项目的 Maven POM 提取依赖信息，供生成的微服务 POM 复用
 * （属性表、dependencyManagement 的 BOM 导入、全部 dependencies 并集）。
 * 用 JDK 自带 DOM 解析，命名空间不敏感（按 local name 取标签）。
 */
public class PomReader {

    /** 一条依赖坐标。version 可能是 ${property} 引用，原样保留（属性表会一并拷贝）。 */
    public record Dep(String groupId, String artifactId, String version,
                      String scope, String type, List<String> exclusions) {
        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    private String projectGroupId = "generated";
    private String projectVersion = "1.0.0";
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<String, Dep> dependencyManagement = new LinkedHashMap<>();
    private final Map<String, Dep> dependencies = new LinkedHashMap<>();

    public void readProject(Path projectDir) throws Exception {
        List<Path> poms = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir, 4)) {
            walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .forEach(poms::add);
        }
        if (poms.isEmpty()) {
            throw new IllegalStateException("原项目没有 pom.xml，无法生成 Maven 工程");
        }
        // 根 POM 固定为 projectDir/pom.xml（poms 排序按路径，app/pom.xml 会排在根前面）；
        // 坐标只取 <project> 直接子元素——getElementsByTagName 取文档序第一个同名标签，
        // parent-first POM（Spring Boot 常见）会先命中 <parent> 里的 groupId/version（R-05）
        Path rootPom = projectDir.resolve("pom.xml").normalize();
        if (!poms.contains(rootPom)) {
            rootPom = poms.get(0);
        }
        Document root = parse(rootPom);
        projectGroupId = directChildText(root.getDocumentElement(), "groupId");
        if (projectGroupId == null) {
            projectGroupId = textOf(root, "groupId", "generated"); // 回退 parent 声明
        }
        projectVersion = directChildText(root.getDocumentElement(), "version");
        if (projectVersion == null) {
            projectVersion = textOf(root, "version", "1.0.0");
        }
        for (Path pom : poms) {
            merge(parse(pom));
        }
    }

    /** <project> 的直接子元素文本（不含 parent/dependencies 内的同名标签）；无则 null。 */
    private static String directChildText(Element project, String tag) {
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && el.getTagName().equals(tag)) {
                return el.getTextContent().trim();
            }
        }
        return null;
    }

    private void merge(Document doc) {
        // properties（子模块覆盖根模块）
        NodeList props = doc.getElementsByTagName("properties");
        for (int i = 0; i < props.getLength(); i++) {
            var children = props.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                var node = children.item(j);
                if (node instanceof Element el) {
                    properties.put(el.getTagName(), node.getTextContent().trim());
                }
            }
        }
        // dependencyManagement（BOM 导入等）
        NodeList depMgmt = doc.getElementsByTagName("dependencyManagement");
        for (int i = 0; i < depMgmt.getLength(); i++) {
            for (Element dep : descendantsOf(depMgmt.item(i), "dependency")) {
                Dep d = toDep(dep);
                dependencyManagement.putIfAbsent(d.key(), d);
            }
        }
        // dependencies（各模块并集，去重）
        NodeList deps = doc.getElementsByTagName("dependencies");
        for (int i = 0; i < deps.getLength(); i++) {
            // 跳过 dependencyManagement 内部的 dependencies（上面已处理）
            if (insideTag(deps.item(i), "dependencyManagement")) {
                continue;
            }
            for (Element dep : descendantsOf(deps.item(i), "dependency")) {
                Dep d = toDep(dep);
                if (!"test".equals(d.scope())) {
                    dependencies.putIfAbsent(d.key(), d);
                }
            }
        }
    }

    private boolean insideTag(org.w3c.dom.Node node, String tagName) {
        var parent = node.getParentNode();
        while (parent != null) {
            if (parent instanceof Element el && tagName.equals(el.getTagName())) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    private List<Element> descendantsOf(org.w3c.dom.Node scope, String tagName) {
        List<Element> result = new ArrayList<>();
        if (!(scope instanceof Element el)) {
            return result;
        }
        NodeList list = el.getElementsByTagName(tagName);
        for (int i = 0; i < list.getLength(); i++) {
            // getElementsByTagName 已按容器范围限定，无需再按父节点过滤
            result.add((Element) list.item(i));
        }
        return result;
    }

    private Dep toDep(Element dep) {
        String groupId = childText(dep, "groupId");
        String artifactId = childText(dep, "artifactId");
        String version = childText(dep, "version");
        String scope = childText(dep, "scope");
        String type = childText(dep, "type");
        List<String> exclusions = new ArrayList<>();
        NodeList excl = dep.getElementsByTagName("exclusion");
        for (int i = 0; i < excl.getLength(); i++) {
            exclusions.add(childText((Element) excl.item(i), "artifactId"));
        }
        return new Dep(groupId, artifactId, version, scope, type, exclusions);
    }

    private String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().trim();
    }

    private String textOf(Document doc, String tag, String fallback) {
        NodeList list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? fallback : list.item(0).getTextContent().trim();
    }

    private Document parse(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(pom.toFile());
    }

    public String projectGroupId() { return projectGroupId; }
    public String projectVersion() { return projectVersion; }
    public Map<String, String> properties() { return properties; }
    public Map<String, Dep> dependencyManagement() { return dependencyManagement; }

    /** 原项目依赖并集，剔除项目内部模块依赖（由生成的 common 取代） */
    public List<Dep> externalDependencies() {
        List<Dep> result = new ArrayList<>();
        for (Dep d : dependencies.values()) {
            if (d.groupId() != null && d.groupId().equals(projectGroupId)) {
                continue;
            }
            result.add(d);
        }
        return result;
    }
}
