package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * JDWP Collector 的有界栈与局部变量投影。
 *
 * @param localNames 顶层局部变量或 `this` 白名单；采集 locals 时必须显式提供
 * @param fieldPaths 以 localNames 中根名称开头的字段路径，例如 `state.current`
 */
public record JdwpCaptureSpec(
        boolean locals,
        boolean stack,
        int maxFrames,
        int maxDepth,
        int maxItems,
        int maxStringLength,
        List<String> localNames,
        List<String> fieldPaths) {

    public JdwpCaptureSpec {
        if (!locals && !stack) {
            throw new IllegalArgumentException("JDWP capture must enable locals or stack");
        }
        if (maxFrames < 1 || maxFrames > 64
                || maxDepth < 0 || maxDepth > 2
                || maxItems < 1 || maxItems > 100
                || maxStringLength < 16 || maxStringLength > 1_024) {
            throw new IllegalArgumentException("JDWP capture exceeds the safety limits");
        }
        localNames = ContractChecks.immutableBoundedStrings(
                localNames == null ? List.of() : localNames, "localNames", 256);
        fieldPaths = ContractChecks.immutableBoundedStrings(
                fieldPaths == null ? List.of() : fieldPaths, "fieldPaths", 2_048);
        if (localNames.size() > 64 || fieldPaths.size() > 128) {
            throw new IllegalArgumentException("JDWP capture projection exceeds the safety limits");
        }
        if (locals && localNames.isEmpty()) {
            throw new IllegalArgumentException("locals capture requires explicit localNames");
        }
        if (!locals && (!localNames.isEmpty() || !fieldPaths.isEmpty())) {
            throw new IllegalArgumentException("localNames and fieldPaths require locals capture");
        }
        List<String> allowedRoots = localNames;
        if (fieldPaths.stream().anyMatch(path -> {
            String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            return !allowedRoots.contains(root);
        })) {
            throw new IllegalArgumentException("Each fieldPath root must be listed in localNames");
        }
    }

    public static JdwpCaptureSpec stackOnly() {
        return new JdwpCaptureSpec(false, true, 8, 1, 20, 256, List.of(), List.of());
    }
}
