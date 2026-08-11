package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ProjectDescriptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Wafer Demo Adapter 内部共用的契约与项目校验。 */
final class WaferDemoChecks {

    static final String PROJECT_ID = "wafer-scheduling-demo";

    private WaferDemoChecks() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " 不能为空");
    }

    static <T> List<T> immutableList(List<T> values, String fieldName) {
        requireNonNull(values, fieldName);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " 不允许包含 null");
        }
        return List.copyOf(values);
    }

    static List<String> immutableNonBlankStrings(List<String> values, String fieldName) {
        List<String> copied = immutableList(values, fieldName);
        copied.forEach(value -> requireNonBlank(value, fieldName + " item"));
        return copied;
    }

    static Map<String, String> immutableNonBlankMap(Map<String, String> values, String fieldName) {
        requireNonNull(values, fieldName);
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
                requireNonBlank(key, fieldName + " key"),
                requireNonBlank(value, fieldName + " value")));
        return Collections.unmodifiableMap(copied);
    }

    static void requireWaferDemoProject(ProjectDescriptor project) throws AdapterException {
        requireNonNull(project, "project");
        if (!PROJECT_ID.equals(project.projectId().value())) {
            throw new AdapterException(
                    "ADAPTER_PROJECT_NOT_SUPPORTED",
                    "ProjectDescriptor 不是 Wafer Scheduling Demo: " + project.projectId().value());
        }
    }
}

