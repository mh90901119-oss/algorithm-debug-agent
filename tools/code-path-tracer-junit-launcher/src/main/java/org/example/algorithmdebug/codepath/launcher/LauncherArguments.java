package org.example.algorithmdebug.codepath.launcher;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受控 Launcher 的严格命令行参数。 */
public record LauncherArguments(
        String testSelector,
        String includePackage,
        Path traceFile,
        long maxOutputBytes,
        long maxEvents) {

    private static final java.util.Set<String> NAMES = java.util.Set.of(
            "test", "include", "trace", "max-output-bytes", "max-events");

    /** 解析完整参数；未知项、重复项和缺失项全部拒绝。 */
    public static LauncherArguments parse(String[] args) {
        if (args == null || (args.length & 1) != 0) {
            throw usage("参数必须成对出现");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String token = args[index];
            if (token == null || !token.startsWith("--")) {
                throw usage("非法参数名");
            }
            String name = token.substring(2);
            if (!NAMES.contains(name) || values.putIfAbsent(name, args[index + 1]) != null) {
                throw usage("未知或重复参数 --" + name);
            }
        }
        String test = required(values, "test");
        if (!test.contains("#") || test.length() > 1_024) {
            throw usage("--test 必须是 class#method");
        }
        String include = required(values, "include");
        org.example.algorithmdebug.contracts.JavaPackageScope.contains(include, include);
        Path trace = Path.of(required(values, "trace"));
        long bytes = positiveLong(values, "max-output-bytes");
        long events = positiveLong(values, "max-events");
        if (bytes > TraceJsonlSink.HARD_MAX_OUTPUT_BYTES
                || events > TraceJsonlSink.HARD_MAX_EVENTS) {
            throw usage("采集预算超过 Agent 硬上限");
        }
        return new LauncherArguments(test, include, trace, bytes, events);
    }

    private static long positiveLong(Map<String, String> values, String name) {
        try {
            long result = Long.parseLong(required(values, name));
            if (result <= 0) {
                throw usage("--" + name + " 必须为正数");
            }
            return result;
        } catch (NumberFormatException failure) {
            throw usage("--" + name + " 必须为整数");
        }
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw usage("缺少 --" + name);
        }
        return value;
    }

    private static IllegalArgumentException usage(String message) {
        return new IllegalArgumentException(message + "; required: --test --include --trace "
                + "--max-output-bytes --max-events");
    }
}
