package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.core.CaseRunException;
import org.example.algorithmdebug.plan.PlanCompilationException;

import java.util.Locale;

/** 将稳定错误码转换为有界、脱敏且可供模型决策的公开消息。 */
final class CliFailureMessages {
    private static final String NOT_TARGET_EVIDENCE =
            " This tool result is not target-test evidence.";

    private CliFailureMessages() {
    }

    static String forCaseRun(CaseRunException failure) {
        if (("PLAN_COMPILATION_FAILED".equals(failure.code())
                || "JDWP_PLAN_COMPILATION_FAILED".equals(failure.code()))
                && failure.getCause() instanceof PlanCompilationException planFailure) {
            return forPlanCompilation(failure.code(), planFailure);
        }
        return forCode(failure.code());
    }

    static String forPlanCompilation(String code, PlanCompilationException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            return sentence(baseMessage(code)
                    + "; correct the collection plan before submitting it again");
        }
        String singleLine = detail.replaceAll("\\s+", " ").strip();
        if (singleLine.length() > 512) {
            singleLine = singleLine.substring(0, 509) + "...";
        }
        return sentence(baseMessage(code) + ": " + singleLine
                + "; correct the collection plan before submitting it again");
    }

    static String forCode(String code) {
        return sentence(baseMessage(code));
    }

    private static String baseMessage(String code) {
        return switch (code) {
            case "TARGET_TEST_NOT_FOUND" ->
                    "Target test was not found; verify the exact test class and method, then start a new analysis";
            case "MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED" ->
                    "The target UT declares multiple algorithm inputs and is outside current support; stop this analysis";
            case "ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED" ->
                    "The algorithm input path must be a direct String literal in the target test method; stop this analysis";
            case "ANALYSIS_INPUT_NOT_CAPTURED" ->
                    "Capture the current Analysis algorithm input before running or collecting";
            case "MAVEN_NOT_FOUND" ->
                    "Maven is unavailable; update agent-settings.json, reinstall the OpenCode integration, and run installer Check";
            case "CODEPATH_TOOL_NOT_CONFIGURED", "CODEPATH_TOOL_MISSING", "CODEPATH_JAVA_MISSING" ->
                    "CodePath is unavailable; rebuild or reinstall the Agent and run installer Check without changing the target project POM";
            case "JDWP_TOOL_NOT_CONFIGURED", "JDWP_TOOL_MISSING" ->
                    "JDWP is unavailable; rebuild or reinstall the Agent and run installer Check without changing the target project POM";
            case "PLAN_EVIDENCE_NOT_FOUND" ->
                    "The Plan references Evidence that is not available in the current Case; use a complete existing Evidence ID";
            case "CASE_ARTIFACT_INTEGRITY_MISMATCH" ->
                    "Artifact content no longer matches its registration; stop using it and regenerate current evidence";
            case "CASE_EVIDENCE_QUERY_SCAN_LIMIT_EXCEEDED" ->
                    "Evidence query scan limit was exceeded; narrow the exact filters or sequence range before querying again";
            case "CASE_EVIDENCE_QUERY_ARTIFACT_UNSUPPORTED" ->
                    "Evidence query supports only CODEPATH_INVOCATIONS and JDWP_SNAPSHOT_SUMMARY Artifacts; "
                            + "use artifact_read for other registered Artifacts or select one of those two Artifact types";
            case "CASE_EVIDENCE_QUERY_BUDGET_TOO_SMALL", "CASE_EVIDENCE_QUERY_RECORD_TOO_LARGE" ->
                    "Evidence query output budget cannot return the selected record; increase maxBytes up to 65536 "
                            + "or create a narrower collection projection";
            case "CLI_TOOLCHAIN_FILE_MISSING" ->
                    "A configured Java or Maven executable is missing; update agent-settings.json, reinstall the OpenCode integration, and run installer Check";
            case "CLI_BOOTSTRAP_FAILED" ->
                    "The Agent CLI could not initialize; report the Agent failure and inspect the local bootstrap DFX log";
            case "INTERNAL_ERROR" ->
                    "The Agent encountered an internal error; report the Agent failure and inspect the local Case DFX log";
            case "PLAN_COMPILATION_FAILED" -> "Collection plan could not be compiled";
            case "JDWP_PLAN_COMPILATION_FAILED" -> "JDWP collection plan could not be compiled";
            default -> humanize(code);
        };
    }

    private static String humanize(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            return "Agent operation failed";
        }
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String sentence(String message) {
        String normalized = message.strip();
        if (!normalized.endsWith(".") && !normalized.endsWith("!") && !normalized.endsWith("?")) {
            normalized += ".";
        }
        return normalized + NOT_TARGET_EVIDENCE;
    }
}
