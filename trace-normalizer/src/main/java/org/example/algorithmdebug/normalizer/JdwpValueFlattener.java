package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.TraceProvenance;

/** 将 Collector 已捕获的 JSON 值树转换为有界、稳定的通用值路径。 */
final class JdwpValueFlattener {

    private static final List<String> STRUCTURAL_MARKERS = List.of(
            "$id", "$cycle", "$truncated", "$remaining", "$remainingFields",
            "$collected", "$error", "$length");
    private static final List<String> LIMIT_MARKERS = List.of(
            "$cycle", "$truncated", "$remaining", "$remainingFields", "$error");

    Result flatten(
            List<RootValue> roots,
            TraceProvenance provenance,
            NormalizationBudget budget,
            int remainingValueFacts) {
        if (roots == null || provenance == null || budget == null || remainingValueFacts < 0) {
            throw new IllegalArgumentException("JDWP 值归一化参数非法");
        }
        ArrayList<JdwpSnapshotSummary.ValueFact> facts = new ArrayList<>();
        ArrayList<JdwpSnapshotSummary.CollectorLimitFact> limits = new ArrayList<>();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        Deque<PendingValue> pending = new ArrayDeque<>();
        for (int index = roots.size() - 1; index >= 0; index--) {
            RootValue root = roots.get(index);
            pending.addLast(new PendingValue(root.path(), root.value()));
        }
        while (!pending.isEmpty()) {
            PendingValue current = pending.removeLast();
            if (current.path().length() > 2_048) {
                reasons.add("VALUE_PATH_BUDGET_EXCEEDED");
                continue;
            }
            if (facts.size() >= remainingValueFacts) {
                reasons.add("VALUE_BUDGET_EXCEEDED");
                break;
            }
            JsonNode value = current.value();
            List<String> markers = markers(value);
            Preview preview = preview(value, budget.maxScalarChars());
            if (preview.truncated()) reasons.add("SCALAR_PREVIEW_BUDGET_EXCEEDED");
            facts.add(new JdwpSnapshotSummary.ValueFact(
                    current.path(), kind(value), runtimeType(value), preview.text(),
                    preview.truncated(), markers, provenance));
            addLimitFacts(current.path(), value, provenance, limits, reasons);
            addChildren(current.path(), value, pending);
        }
        return new Result(List.copyOf(facts), List.copyOf(limits), List.copyOf(reasons));
    }

    private static void addChildren(String path, JsonNode value, Deque<PendingValue> pending) {
        if (!value.isContainerNode()) return;
        if (value.isArray()) {
            for (int index = value.size() - 1; index >= 0; index--) {
                pending.addLast(new PendingValue(path + "[" + index + "]", value.get(index)));
            }
            return;
        }
        JsonNode fields = value.get("fields");
        if (fields != null && fields.isObject()) {
            List<String> names = new ArrayList<>();
            fields.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (int index = names.size() - 1; index >= 0; index--) {
                String name = names.get(index);
                pending.addLast(new PendingValue(path + ".fields." + name, fields.get(name)));
            }
        }
        JsonNode elements = value.get("elements");
        if (elements != null && elements.isArray()) {
            for (int index = elements.size() - 1; index >= 0; index--) {
                pending.addLast(new PendingValue(
                        path + ".elements[" + index + "]", elements.get(index)));
            }
        }
    }

    private static List<String> markers(JsonNode value) {
        if (!value.isObject()) return List.of();
        ArrayList<String> result = new ArrayList<>();
        for (String marker : STRUCTURAL_MARKERS) {
            JsonNode markerValue = value.get(marker);
            if (markerValue != null) result.add(marker + "=" + scalar(markerValue));
        }
        return List.copyOf(result);
    }

    private static void addLimitFacts(
            String path,
            JsonNode value,
            TraceProvenance provenance,
            List<JdwpSnapshotSummary.CollectorLimitFact> limits,
            LinkedHashSet<String> reasons) {
        if (!value.isObject()) return;
        for (String marker : LIMIT_MARKERS) {
            JsonNode markerValue = value.get(marker);
            if (markerValue == null || !activeLimit(marker, markerValue)) continue;
            if (limits.size() >= 1_024) {
                reasons.add("LIMIT_FACT_BUDGET_EXCEEDED");
                return;
            }
            limits.add(new JdwpSnapshotSummary.CollectorLimitFact(
                    path, marker, bounded(scalar(markerValue), 1_024), provenance));
            reasons.add("COLLECTOR_VALUE_LIMIT");
        }
    }

    private static boolean activeLimit(String marker, JsonNode value) {
        if ("$truncated".equals(marker) || "$cycle".equals(marker)) {
            return !value.isBoolean() || value.booleanValue();
        }
        if ("$remaining".equals(marker) || "$remainingFields".equals(marker)) {
            return !value.isNumber() || value.longValue() > 0;
        }
        return true;
    }

    private static String kind(JsonNode value) {
        if (value == null || value.isNull()) return "NULL";
        if (value.isBoolean()) return "BOOLEAN";
        if (value.isIntegralNumber()) return "INTEGER";
        if (value.isFloatingPointNumber()) return "DECIMAL";
        if (value.isTextual()) return "STRING";
        if (value.isArray() || value.has("elements")) return "ARRAY";
        return "OBJECT";
    }

    private static Optional<String> runtimeType(JsonNode value) {
        if (value != null && value.isObject()) {
            JsonNode type = value.get("$type");
            if (type != null && type.isTextual() && !type.textValue().isBlank()) {
                return Optional.of(bounded(type.textValue(), 1_024));
            }
        }
        return Optional.empty();
    }

    private static Preview preview(JsonNode value, int maximumChars) {
        if (value == null || value.isNull()) return new Preview("null", false);
        if (value.isContainerNode()) return new Preview("", false);
        String scalar = scalar(value);
        if (scalar.length() <= maximumChars) return new Preview(scalar, false);
        int end = maximumChars;
        if (end > 0 && Character.isHighSurrogate(scalar.charAt(end - 1))) end--;
        return new Preview(scalar.substring(0, end), true);
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        return value.toString();
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    record RootValue(String path, JsonNode value) {
        RootValue {
            if (path == null || path.isBlank() || value == null) {
                throw new IllegalArgumentException("JDWP 根值非法");
            }
        }
    }

    record Result(
            List<JdwpSnapshotSummary.ValueFact> facts,
            List<JdwpSnapshotSummary.CollectorLimitFact> limits,
            List<String> reasons) {}

    private record PendingValue(String path, JsonNode value) {}

    private record Preview(String text, boolean truncated) {}
}
