package org.example.algorithmdebug.codepath.launcher;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受控 Launcher 的严格命令行参数。 */
public record LauncherArguments(Path planFile, Path traceFile) {
    private static final java.util.Set<String> NAMES = java.util.Set.of("plan", "trace");

    /** 仅接受归档计划和原始 Trace 路径，并限制二者属于同一个 Collection。 */
    public static LauncherArguments parse(String[] args) {
        if (args == null || args.length != 4) throw usage("Arguments must appear in pairs");
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String token = args[index];
            if (token == null || !token.startsWith("--")) throw usage("Invalid argument name");
            String name = token.substring(2);
            if (!NAMES.contains(name) || values.putIfAbsent(name, args[index + 1]) != null) {
                throw usage("Unknown or duplicate argument --" + name);
            }
        }
        Path plan = Path.of(required(values, "plan")).toAbsolutePath().normalize();
        Path trace = Path.of(required(values, "trace")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(plan, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(plan)) {
            throw usage("--plan must be a regular file without symbolic links");
        }
        Path planCollection = plan.getParent() == null ? null : plan.getParent().getParent();
        Path traceCollection = trace.getParent() == null ? null : trace.getParent().getParent();
        if (planCollection == null || !planCollection.equals(traceCollection)) {
            throw usage("--plan and --trace must belong to the same Collection");
        }
        return new LauncherArguments(plan, trace);
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw usage("Missing --" + name);
        return value;
    }

    private static IllegalArgumentException usage(String message) {
        return new IllegalArgumentException(message + "; required: --plan --trace");
    }
}
