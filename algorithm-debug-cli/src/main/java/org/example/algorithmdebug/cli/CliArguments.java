package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.casecore.ContextMode;

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
     * 解析支持的控制面命令，拒绝未知、重复、缺值和多余参数。
     *
     * @param arguments main 参数
     * @return 封闭命令 DTO
     */
    public static CliCommand parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            throw invalid("Missing command");
        }
        if (matches(arguments, "workspace", "init")) {
            Map<String, String> options = options(arguments, 2, Set.of("--root"));
            requireExactly(options, Set.of("--root"));
            return new CliCommand.WorkspaceInit(path(options.get("--root"), "--root"));
        }
        if (matches(arguments, "project", "register")) {
            Map<String, String> options = options(
                    arguments, 2, Set.of(
                            "--workspace", "--project", "--project-id", "--result-directory"));
            requirePresent(options, "--workspace", "--project");
            Optional<ProjectId> projectId = Optional.ofNullable(options.get("--project-id")).map(ProjectId::new);
            return new CliCommand.ProjectRegister(
                    path(options.get("--workspace"), "--workspace"),
                    path(options.get("--project"), "--project"),
                    projectId,
                    Optional.ofNullable(options.get("--result-directory")));
        }
        if (matches(arguments, "case", "open")) {
            Map<String, String> options = options(arguments, 2, Set.of(
                    "--workspace", "--project-id", "--test", "--question-file",
                    "--case-id", "--adapter", "--context-mode"));
            requirePresent(options, "--workspace", "--project-id", "--test", "--question-file");
            return new CliCommand.CaseOpen(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    targetTest(options.get("--test")),
                    path(options.get("--question-file"), "--question-file"),
                    Optional.ofNullable(options.get("--case-id")).map(CaseId::new),
                    Optional.ofNullable(options.get("--adapter")),
                    contextMode(options.get("--context-mode")));
        }
        if (matches(arguments, "case", "inspect")) {
            Map<String, String> options = options(
                    arguments, 2, Set.of("--workspace", "--project-id", "--case-id"));
            requireExactly(options, Set.of("--workspace", "--project-id", "--case-id"));
            return new CliCommand.CaseInspect(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")));
        }
        if (matches(arguments, "case", "audit")) {
            Map<String, String> options = options(arguments, 2, Set.of("--workspace", "--project-id", "--case-id"));
            requireExactly(options, Set.of("--workspace", "--project-id", "--case-id"));
            return new CliCommand.CaseAudit(path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")), new CaseId(options.get("--case-id")));
        }
        if (matches(arguments, "gantt", "inspect")) {
            Map<String, String> options = options(arguments, 2, Set.of("--workspace", "--project-id", "--case-id",
                    "--artifact-id", "--operation", "--json-pointer", "--offset", "--limit"));
            requirePresent(options, "--workspace", "--project-id", "--case-id", "--artifact-id");
            return new CliCommand.GanttInspect(path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")), new CaseId(options.get("--case-id")),
                    options.get("--artifact-id"), options.getOrDefault("--operation", "summary"),
                    options.getOrDefault("--json-pointer", ""),
                    boundedInt(options.getOrDefault("--offset", "0"), "--offset", 0, Integer.MAX_VALUE),
                    boundedInt(options.getOrDefault("--limit", "100"), "--limit", 1, 100));
        }
        if (matches(arguments, "run", "execute")) {
            Map<String, String> options = options(arguments, 2, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id"));
            requireExactly(options, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id"));
            return new CliCommand.RunExecute(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")),
                    new AnalysisId(options.get("--analysis-id")));
        }
        if (matches(arguments, "static", "analyze")) {
            Map<String, String> options = options(arguments, 2, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id"));
            requireExactly(options, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id"));
            return new CliCommand.StaticAnalyze(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")),
                    new AnalysisId(options.get("--analysis-id")));
        }
        if (arguments.length >= 3 && "plan".equals(arguments[0])
                && "codepath".equals(arguments[1]) && "create".equals(arguments[2])) {
            Map<String, String> options = options(arguments, 3, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--request-file"));
            requireExactly(options, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--request-file"));
            return new CliCommand.CodePathPlanCreate(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")),
                    new AnalysisId(options.get("--analysis-id")),
                    path(options.get("--request-file"), "--request-file"));
        }
        if (arguments.length >= 3 && "collection".equals(arguments[0])
                && "codepath".equals(arguments[1]) && "execute".equals(arguments[2])) {
            Map<String, String> options = options(arguments, 3, Set.of(
                    "--workspace", "--project-id", "--case-id", "--plan-id"));
            requireExactly(options, Set.of("--workspace", "--project-id", "--case-id", "--plan-id"));
            return new CliCommand.CodePathCollectionExecute(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")), new PlanId(options.get("--plan-id")));
        }
        if (matches(arguments, "artifact", "read")) {
            Map<String, String> options = options(arguments, 2, Set.of(
                    "--workspace", "--project-id", "--case-id", "--artifact-id",
                    "--offset-bytes", "--max-bytes"));
            requirePresent(options, "--workspace", "--project-id", "--case-id", "--artifact-id");
            return new CliCommand.ArtifactRead(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")), options.get("--artifact-id"),
                    nonNegativeLong(options.getOrDefault("--offset-bytes", "0"), "--offset-bytes"),
                    boundedInt(options.getOrDefault("--max-bytes", "16384"), "--max-bytes", 1, 65_536));
        }
        if (matches(arguments, "analysis", "complete")) {
            Map<String, String> options = options(arguments, 2, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--result-file"));
            requireExactly(options, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--result-file"));
            return new CliCommand.AnalysisComplete(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")),
                    new AnalysisId(options.get("--analysis-id")),
                    path(options.get("--result-file"), "--result-file"));
        }
        if (arguments.length >= 3 && "plan".equals(arguments[0])
                && "jdwp".equals(arguments[1]) && "create".equals(arguments[2])) {
            Map<String, String> options = options(arguments, 3, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--request-file"));
            requireExactly(options, Set.of(
                    "--workspace", "--project-id", "--case-id", "--analysis-id", "--request-file"));
            return new CliCommand.JdwpPlanCreate(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")),
                    new AnalysisId(options.get("--analysis-id")),
                    path(options.get("--request-file"), "--request-file"));
        }
        if (arguments.length >= 3 && "collection".equals(arguments[0])
                && "jdwp".equals(arguments[1]) && "execute".equals(arguments[2])) {
            Map<String, String> options = options(arguments, 3, Set.of(
                    "--workspace", "--project-id", "--case-id", "--plan-id"));
            requireExactly(options, Set.of("--workspace", "--project-id", "--case-id", "--plan-id"));
            return new CliCommand.JdwpCollectionExecute(
                    path(options.get("--workspace"), "--workspace"),
                    new ProjectId(options.get("--project-id")),
                    new CaseId(options.get("--case-id")), new PlanId(options.get("--plan-id")));
        }
        if ("doctor".equals(arguments[0])) {
            Map<String, String> options = options(arguments, 1, Set.of("--workspace", "--project"));
            requirePresent(options, "--workspace");
            Optional<Path> module = Optional.ofNullable(options.get("--project"))
                    .map(value -> path(value, "--project"));
            return new CliCommand.Doctor(path(options.get("--workspace"), "--workspace"), module);
        }
        throw invalid("Unknown command");
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
                throw invalid("Unknown or unexpected option");
            }
            if (parsed.containsKey(option)) {
                throw invalid("Duplicate option: " + option);
            }
            if (index + 1 >= arguments.length) {
                throw invalid("Missing option value: " + option);
            }
            String value = arguments[index + 1];
            if (value == null || value.isBlank() || !value.equals(value.strip()) || value.startsWith("--")) {
                throw invalid("Invalid option value: " + option);
            }
            parsed.put(option, value);
        }
        return Map.copyOf(parsed);
    }

    private static void requireExactly(Map<String, String> options, Set<String> required) {
        requirePresent(options, required.toArray(String[]::new));
        if (options.size() != required.size()) {
            throw invalid("Command contains unexpected options");
        }
    }

    private static void requirePresent(Map<String, String> options, String... names) {
        for (String name : names) {
            if (!options.containsKey(name)) {
                throw invalid("Missing required option: " + name);
            }
        }
    }

    private static Path path(String value, String option) {
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            throw invalid("Invalid path option: " + option);
        }
    }

    private static TargetTest targetTest(String selector) {
        int separator = selector == null ? -1 : selector.lastIndexOf('#');
        if (separator <= 0 || separator == selector.length() - 1) {
            throw invalid("--test must use the fully.qualified.Class#method format");
        }
        try {
            return new TargetTest(
                    selector.substring(0, separator), selector.substring(separator + 1));
        } catch (IllegalArgumentException failure) {
            throw invalid("--test is not a valid target test selector");
        }
    }

    private static ContextMode contextMode(String value) {
        if (value == null || "reuse".equals(value)) {
            return ContextMode.REUSE_LATEST;
        }
        if ("new".equals(value)) {
            return ContextMode.CREATE_NEW;
        }
        throw invalid("CONTEXT_MODE_INVALID: --context-mode supports only reuse or new");
    }

    private static long nonNegativeLong(String value, String option) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw invalid(option + " must be a non-negative integer");
            return parsed;
        } catch (NumberFormatException failure) {
            throw invalid(option + " must be a non-negative integer");
        }
    }

    private static int boundedInt(String value, String option, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw invalid(option + " is outside the allowed range");
            return parsed;
        } catch (NumberFormatException failure) {
            throw invalid(option + " must be an integer");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
