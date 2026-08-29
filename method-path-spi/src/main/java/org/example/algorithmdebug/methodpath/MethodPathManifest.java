package org.example.algorithmdebug.methodpath;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;

/** 外部 CodePath 工具执行后保留的单原始流 v2 事实清单。 */
public record MethodPathManifest(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String toolName,
        String toolVersion,
        CollectionCompletion completion,
        String stage,
        boolean processStarted,
        int exitCode,
        boolean timedOut,
        String targetOutcome,
        long testsFound,
        long testsSucceeded,
        long testsAborted,
        long testsFailed,
        long capturedEventCount,
        long capturedBytes,
        List<String> truncationReasons,
        Optional<AgentFailureDiagnostic> agentFailure,
        String rawTrace,
        String stdoutLog,
        String stderrLog,
        Instant startedAt,
        Instant completedAt) {

    /** 校验身份、Hash、计数、完成状态和可移植路径。 */
    public MethodPathManifest {
        if (!"2.0".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MethodPathManifest version");
        caseId = Objects.requireNonNull(caseId); contextId = Objects.requireNonNull(contextId);
        analysisId = Objects.requireNonNull(analysisId); runId = Objects.requireNonNull(runId);
        planId = Objects.requireNonNull(planId); collectionId = Objects.requireNonNull(collectionId);
        toolName = bounded(toolName, "toolName", 128); toolVersion = bounded(toolVersion, "toolVersion", 256);
        completion = Objects.requireNonNull(completion, "completion");
        if (!("REQUEST_ARCHIVED".equals(stage) || "CLASSPATH_RESOLVED".equals(stage)
                || "PROCESS_COMPLETED".equals(stage) || "COMPLETE".equals(stage)
                || "FAILED".equals(stage))) throw new IllegalArgumentException("stage is invalid");
        if (capturedEventCount < 0 || capturedBytes < 0) throw new IllegalArgumentException("Collection counts are invalid");
        if (!("PASSED".equals(targetOutcome) || "FAILED".equals(targetOutcome)
                || "NOT_EXECUTED".equals(targetOutcome))) {
            throw new IllegalArgumentException("targetOutcome is invalid");
        }
        if (testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound) {
            throw new IllegalArgumentException("JUnit targetcounts are invalid");
        }
        if (("NOT_EXECUTED".equals(targetOutcome)) != (testsFound == 0)
                || ("FAILED".equals(targetOutcome) != (testsFailed > 0 || testsAborted > 0))
                || ("PASSED".equals(targetOutcome) && testsSucceeded == 0)) {
            throw new IllegalArgumentException("targetOutcome does not match JUnit counts");
        }
        truncationReasons = List.copyOf(Objects.requireNonNull(truncationReasons));
        if (truncationReasons.size() > 16) throw new IllegalArgumentException("truncationReasons exceeds the limit");
        truncationReasons.forEach(value -> bounded(value, "truncationReason", 2_048));
        if (completion == CollectionCompletion.TRUNCATED && truncationReasons.isEmpty()) {
            throw new IllegalArgumentException("TRUNCATED must include truncation reasons");
        }
        agentFailure = Objects.requireNonNull(agentFailure);
        if ((completion == CollectionCompletion.AGENT_FAILED || completion == CollectionCompletion.TOOL_FAILED)
                && agentFailure.isEmpty()) throw new IllegalArgumentException("A failed status must include a diagnostic");
        rawTrace = portablePath(rawTrace, "rawTrace"); stdoutLog = portablePath(stdoutLog, "stdoutLog");
        stderrLog = portablePath(stderrLog, "stderrLog");
        if (timedOut != (completion == CollectionCompletion.TIMED_OUT)) throw new IllegalArgumentException("timedOut is inconsistent");
        if (!processStarted && exitCode != -1) throw new IllegalArgumentException("when not started exitCode must be -1");
        startedAt = Objects.requireNonNull(startedAt); completedAt = Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt must not be before startedAt");
    }

    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
    private static String portablePath(String value, String name) {
        String path = bounded(value, name, 1_024);
        if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(name + " must be a portable relative path");
        }
        return path;
    }
}
