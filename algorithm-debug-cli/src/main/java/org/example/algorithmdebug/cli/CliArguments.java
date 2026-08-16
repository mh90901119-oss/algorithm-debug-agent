package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 无文件系统副作用的严格 CLI 参数解析器。 */
public final class CliArguments {

    private CliArguments() {
    }

    /**
     * 解析唯一支持的三类命令，拒绝未知、重复、缺值和多余参数。
     *
     * @param arguments main 参数
     * @return 封闭命令 DTO
     */
    public static CliCommand parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            throw invalid("缺少命令");
        }
        if (matches(arguments, "workspace", "init")) {
            Map<String, String> options = options(arguments, 2, Set.of("--root"));
            requireExactly(options, Set.of("--root"));
            return new CliCommand.WorkspaceInit(path(options.get("--root"), "--root"));
        }
        if (matches(arguments, "project", "register")) {
            Map<String, String> options = options(
                    arguments, 2, Set.of("--workspace", "--project", "--project-id"));
            requirePresent(options, "--workspace", "--project");
            Optional<ProjectId> projectId = Optional.ofNullable(options.get("--project-id")).map(ProjectId::new);
            return new CliCommand.ProjectRegister(
                    path(options.get("--workspace"), "--workspace"),
                    path(options.get("--project"), "--project"),
                    projectId);
        }
        if ("doctor".equals(arguments[0])) {
            Map<String, String> options = options(arguments, 1, Set.of("--workspace", "--project"));
            requirePresent(options, "--workspace");
            Optional<Path> module = Optional.ofNullable(options.get("--project"))
                    .map(value -> path(value, "--project"));
            return new CliCommand.Doctor(path(options.get("--workspace"), "--workspace"), module);
        }
        throw invalid("未知命令");
    }

    private static boolean matches(String[] arguments, String first, String second) {
        return arguments.length >= 2 && first.equals(arguments[0]) && second.equals(arguments[1]);
    }

    private static Map<String, String> options(
            String[] arguments,
            int start,
            Set<String> allowed) {
        Map<String, String> parsed = new HashMap<>();
        for (int index = start; index < arguments.length; index += 2) {
            String option = arguments[index];
            if (option == null || !allowed.contains(option)) {
                throw invalid("未知或多余选项");
            }
            if (parsed.containsKey(option)) {
                throw invalid("选项重复: " + option);
            }
            if (index + 1 >= arguments.length) {
                throw invalid("选项缺少值: " + option);
            }
            String value = arguments[index + 1];
            if (value == null || value.isBlank() || !value.equals(value.strip()) || value.startsWith("--")) {
                throw invalid("选项值无效: " + option);
            }
            parsed.put(option, value);
        }
        return Map.copyOf(parsed);
    }

    private static void requireExactly(Map<String, String> options, Set<String> required) {
        requirePresent(options, required.toArray(String[]::new));
        if (options.size() != required.size()) {
            throw invalid("命令包含多余选项");
        }
    }

    private static void requirePresent(Map<String, String> options, String... names) {
        for (String name : names) {
            if (!options.containsKey(name)) {
                throw invalid("缺少必需选项: " + name);
            }
        }
    }

    private static Path path(String value, String option) {
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            throw invalid("路径选项无效: " + option);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
