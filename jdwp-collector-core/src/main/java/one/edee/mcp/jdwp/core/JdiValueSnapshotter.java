package one.edee.mcp.jdwp.core;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 JDI Value 转为有类型、可追溯且有界的 JSON 结构，全程不调用目标对象方法。
 */
public final class JdiValueSnapshotter {
    private final SnapshotLimits limits;

    public JdiValueSnapshotter(SnapshotLimits limits) {
        this.limits = limits;
    }

    public Object snapshot(Value value) {
        return snapshot(value, Set.of());
    }

    public Object snapshot(Value value, Set<String> fieldPaths) {
        return snapshot(value, 0, new HashSet<>(), sanitize(fieldPaths));
    }

    private Object snapshot(Value value, int depth, Set<Long> seen, Set<String> fieldPaths) {
        if (value == null) {
            return null;
        }
        if (value instanceof StringReference stringReference) {
            return snapshotString(stringReference.value());
        }
        if (value instanceof PrimitiveValue primitiveValue) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("$kind", "primitive");
            result.put("$type", primitiveValue.type().name());
            result.put("$value", primitiveValueValue(primitiveValue));
            return result;
        }
        if (!(value instanceof ObjectReference objectReference)) {
            return Map.of(
                "$kind", "value",
                "$type", value.type().name(),
                "$value", truncateText(value.toString())
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("$kind", objectReference instanceof ArrayReference ? "array" : "object");
            result.put("$type", objectReference.referenceType().name());
            result.put("$id", objectReference.uniqueID());
            if (!seen.add(objectReference.uniqueID())) {
                result.put("$cycle", true);
                return result;
            }
            if (depth >= limits.maxDepth()) {
                result.put("$truncated", "maxDepth");
                return result;
            }
            if (objectReference instanceof ArrayReference arrayReference) {
                snapshotArray(arrayReference, depth, seen, result);
            } else {
                snapshotFields(objectReference, depth, seen, fieldPaths, result);
            }
        } catch (ObjectCollectedException collected) {
            result.put("$collected", true);
        } catch (RuntimeException inaccessible) {
            result.put("$error", inaccessible.getClass().getSimpleName() + ": " + inaccessible.getMessage());
        }
        return result;
    }

    private Map<String, Object> snapshotString(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("$kind", "string");
        result.put("$type", "java.lang.String");
        if (value.length() <= limits.maxStringLength()) {
            result.put("$value", value);
        } else {
            result.put("$value", value.substring(0, limits.maxStringLength()));
            result.put("$truncated", true);
            result.put("$originalLength", value.length());
        }
        return result;
    }

    private void snapshotArray(
        ArrayReference array,
        int depth,
        Set<Long> seen,
        Map<String, Object> result
    ) {
        int length = array.length();
        int count = Math.min(length, limits.maxItems());
        List<Object> elements = new ArrayList<>(count);
        for (Value element : array.getValues(0, count)) {
            elements.add(snapshot(element, depth + 1, seen, Set.of()));
        }
        result.put("$length", length);
        result.put("elements", elements);
        if (count < length) {
            result.put("$remaining", length - count);
        }
    }

    private void snapshotFields(
        ObjectReference object,
        int depth,
        Set<Long> seen,
        Set<String> fieldPaths,
        Map<String, Object> result
    ) {
        List<Field> candidates = object.referenceType().allFields().stream()
            .filter(field -> fieldPaths.isEmpty() || fieldSelected(field.name(), fieldPaths))
            .toList();
        int count = Math.min(candidates.size(), limits.maxItems());
        List<Map<String, Object>> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Field field = candidates.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", field.name());
            item.put("declaringType", field.declaringType().name());
            item.put("declaredType", field.typeName());
            item.put("static", field.isStatic());
            Value fieldValue = field.isStatic()
                ? field.declaringType().getValue(field)
                : object.getValue(field);
            item.put("value", snapshot(fieldValue, depth + 1, seen, nestedPaths(field.name(), fieldPaths)));
            values.add(item);
        }
        result.put("fields", values);
        if (count < candidates.size()) {
            result.put("$remainingFields", candidates.size() - count);
        }
    }

    private static Object primitiveValueValue(PrimitiveValue value) {
        String type = value.type().name();
        String text = value.toString();
        return switch (type) {
            case "boolean" -> Boolean.parseBoolean(text);
            case "byte" -> Byte.parseByte(text);
            case "short" -> Short.parseShort(text);
            case "int" -> Integer.parseInt(text);
            case "long" -> Long.parseLong(text);
            case "float" -> Float.parseFloat(text);
            case "double" -> Double.parseDouble(text);
            case "char" -> text;
            default -> text;
        };
    }

    private String truncateText(String value) {
        return value.length() <= limits.maxStringLength()
            ? value
            : value.substring(0, limits.maxStringLength());
    }

    private static boolean fieldSelected(String name, Set<String> paths) {
        return paths.stream().anyMatch(path -> path.equals(name) || path.startsWith(name + "."));
    }

    private static Set<String> nestedPaths(String name, Set<String> paths) {
        Set<String> result = new LinkedHashSet<>();
        String prefix = name + ".";
        for (String path : paths) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                result.add(path.substring(prefix.length()));
            }
        }
        return result;
    }

    private static Set<String> sanitize(Set<String> paths) {
        Set<String> result = new LinkedHashSet<>();
        if (paths != null) {
            paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(String::trim)
                .forEach(result::add);
        }
        return Set.copyOf(result);
    }
}
