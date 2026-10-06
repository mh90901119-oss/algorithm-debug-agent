package org.example.algorithmdebug.staticanalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;

/**
 * Method Catalog 的不可变确定性索引。
 *
 * <p>所有方法与调用边均使用稳定排序；本类型只表达静态源码关系，不推断运行时执行事实。</p>
 */
public final class MethodCatalogIndex {
    private static final Comparator<MethodCallEdge> EDGE_ORDER = Comparator
            .comparing(MethodCallEdge::callerKey)
            .thenComparing(MethodCallEdge::calleeKey)
            .thenComparingInt(MethodCallEdge::sourceLine)
            .thenComparing(edge -> edge.resolutionKind().name());

    private final List<MethodCatalogEntry> methods;
    private final Map<String, MethodCatalogEntry> methodsByKey;
    private final Map<String, List<MethodCallEdge>> outgoing;
    private final Map<String, List<MethodCallEdge>> incoming;

    private MethodCatalogIndex(
            List<MethodCatalogEntry> methods,
            Map<String, MethodCatalogEntry> methodsByKey,
            Map<String, List<MethodCallEdge>> outgoing,
            Map<String, List<MethodCallEdge>> incoming) {
        this.methods = methods;
        this.methodsByKey = methodsByKey;
        this.outgoing = outgoing;
        this.incoming = incoming;
    }

    /** 从已校验 Catalog 构建稳定、去重、不可变索引。 */
    public static MethodCatalogIndex from(MethodCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        List<MethodCatalogEntry> sortedMethods = catalog.entries().stream()
                .sorted(Comparator.comparing(MethodCatalogEntry::methodKey))
                .toList();
        Map<String, MethodCatalogEntry> methodsByKey = new LinkedHashMap<>();
        sortedMethods.forEach(entry -> methodsByKey.put(entry.methodKey(), entry));

        Map<String, TreeSet<MethodCallEdge>> outgoingBuilders = new LinkedHashMap<>();
        Map<String, TreeSet<MethodCallEdge>> incomingBuilders = new LinkedHashMap<>();
        sortedMethods.forEach(entry -> {
            outgoingBuilders.put(entry.methodKey(), new TreeSet<>(EDGE_ORDER));
            incomingBuilders.put(entry.methodKey(), new TreeSet<>(EDGE_ORDER));
        });
        for (MethodCallEdge edge : catalog.edges()) {
            outgoingBuilders.get(edge.callerKey()).add(edge);
            incomingBuilders.get(edge.calleeKey()).add(edge);
        }
        return new MethodCatalogIndex(
                List.copyOf(sortedMethods),
                Collections.unmodifiableMap(new LinkedHashMap<>(methodsByKey)),
                immutableEdgeMap(outgoingBuilders),
                immutableEdgeMap(incomingBuilders));
    }

    /** @return methodKey 对应方法；不存在时为空 */
    public Optional<MethodCatalogEntry> method(String methodKey) {
        return Optional.ofNullable(methodsByKey.get(methodKey));
    }

    /** @return 按 methodKey 排序的全部方法 */
    public List<MethodCatalogEntry> methods() {
        return methods;
    }

    /** @return 按稳定边顺序排列的出边 */
    public List<MethodCallEdge> outgoing(String methodKey) {
        return outgoing.getOrDefault(methodKey, List.of());
    }

    /** @return 按稳定边顺序排列的入边 */
    public List<MethodCallEdge> incoming(String methodKey) {
        return incoming.getOrDefault(methodKey, List.of());
    }

    /**
     * 按大小写敏感的 Java 符号前缀搜索方法。
     *
     * <p>匹配全限定类名、简单类名、方法名、类名加方法名和完整 methodKey。</p>
     */
    public List<MethodCatalogEntry> searchSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return methods.stream().filter(entry -> matches(entry, symbol)).toList();
    }

    private static boolean matches(MethodCatalogEntry entry, String symbol) {
        String className = entry.sourceAnchor().className();
        int separator = className.lastIndexOf('.');
        String simpleClassName = separator < 0 ? className : className.substring(separator + 1);
        String methodName = entry.sourceAnchor().methodName();
        return entry.methodKey().startsWith(symbol)
                || className.startsWith(symbol)
                || simpleClassName.startsWith(symbol)
                || methodName.startsWith(symbol)
                || (className + "#" + methodName).startsWith(symbol)
                || (simpleClassName + "#" + methodName).startsWith(symbol);
    }

    private static Map<String, List<MethodCallEdge>> immutableEdgeMap(
            Map<String, TreeSet<MethodCallEdge>> builders) {
        Map<String, List<MethodCallEdge>> result = new LinkedHashMap<>();
        builders.forEach((key, value) -> result.put(key, List.copyOf(new ArrayList<>(value))));
        return Collections.unmodifiableMap(result);
    }
}
