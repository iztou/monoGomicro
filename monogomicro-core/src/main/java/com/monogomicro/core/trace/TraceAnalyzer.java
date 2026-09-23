package com.monogomicro.core.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * 离线解析 agent 产生的调用轨迹：
 * 行格式 <threadId>\t<E|N|X>\t<nanos>\t<fqcn>\t<method>
 * E=用例入口（新的业务用例开始） N=方法进入 X=方法退出。
 * 按线程用栈配对 N/X 重建 caller->callee，按 E 切分用例。
 */
public class TraceAnalyzer {

    private record RawLine(long nanos, char kind, String fqcn, String method) {}

    public List<UseCase> analyze(Path traceFile) throws IOException {
        List<String> lines = Files.readAllLines(traceFile, StandardCharsets.UTF_8);
        Map<String, List<RawLine>> byThread = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 5) {
                continue;
            }
            try {
                RawLine raw = new RawLine(Long.parseLong(parts[2]), parts[1].charAt(0), parts[3], parts[4]);
                byThread.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(raw);
            } catch (NumberFormatException ignore) {
            }
        }
        byThread.values().forEach(l -> l.sort(Comparator.comparingLong(RawLine::nanos)));

        List<UseCase> useCases = new ArrayList<>();
        for (List<RawLine> threadLines : byThread.values()) {
            UseCase current = null;
            Stack<String> stack = new Stack<>();
            int seq = 0;
            for (RawLine raw : threadLines) {
                switch (raw.kind()) {
                    case 'E' -> {
                        if (current != null && !current.getClasses().isEmpty()) {
                            useCases.add(current);
                        }
                        seq++;
                        current = new UseCase(raw.fqcn() + "." + raw.method() + "#" + seq);
                        stack.clear();
                    }
                    case 'N' -> {
                        if (current != null) {
                            String caller = stack.isEmpty() ? null : stack.peek();
                            current.addCall(caller, raw.fqcn());
                        }
                        stack.push(raw.fqcn());
                    }
                    case 'X' -> {
                        if (!stack.isEmpty()) {
                            stack.pop();
                        }
                    }
                    default -> { }
                }
            }
            if (current != null && !current.getClasses().isEmpty()) {
                useCases.add(current);
            }
        }
        return useCases;
    }

    /** 全部用例聚合出的动态调用权重（对称化） */
    public Map<String, Map<String, Long>> dynamicWeights(List<UseCase> useCases) {
        Map<String, Long> directed = new LinkedHashMap<>();
        for (UseCase uc : useCases) {
            for (Map.Entry<String, Long> e : uc.getEdgeWeights().entrySet()) {
                directed.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        Map<String, Map<String, Long>> sym = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : directed.entrySet()) {
            String key = e.getKey();
            int sep = key.indexOf("->");
            if (sep <= 0) {
                continue;
            }
            String from = key.substring(0, sep);
            String to = key.substring(sep + 2);
            sym.computeIfAbsent(from, k -> new LinkedHashMap<>()).merge(to, e.getValue(), Long::sum);
            sym.computeIfAbsent(to, k -> new LinkedHashMap<>()).merge(from, e.getValue(), Long::sum);
        }
        return sym;
    }
}
