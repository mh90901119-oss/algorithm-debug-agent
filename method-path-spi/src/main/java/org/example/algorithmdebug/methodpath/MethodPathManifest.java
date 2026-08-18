package org.example.algorithmdebug.methodpath;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
        Optional<String> toolSha256,
        String planSha256,
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
        Optional<String> rawSha256,
        List<String> truncationReasons,
        Optional<AgentFailureDiagnostic> agentFailure,
        String rawTrace,
        String stdoutLog,
        String stderrLog,
        Instant startedAt,
        Instant completedAt) {

    /** 校验身份、Hash、计数、完成状态和可移植路径。 */
    public MethodPathManifest {
        if (!"2.0".equals(schemaVersion)) throw new IllegalArgumentException("不支持的 MethodPathManifest 版本");
        caseId = Objects.requireNonNull(caseId); contextId = Objects.requireNonNull(contextId);
        analysisId = Objects.requireNonNull(analysisId); runId = Objects.requireNonNull(runId);
        planId = Objects.requireNonNull(planId); collectionId = Objects.requireNonNull(collectionId);
        toolName = bounded(toolName, "toolName", 128); toolVersion = bounded(toolVersion, "toolVersion", 256);
        toolSha256 = optionalSha(toolSha256, "toolSha256"); planSha256 = sha(planSha256, "planSha256");
        completion = Objects.requireNonNull(completion, "completion");
        if (!("REQUEST_ARCHIVED".equals(stage) || "CLASSPATH_RESOLVED".equals(stage)
                || "PROCESS_COMPLETED".equals(stage) || "COMPLETE".equals(stage)
                || "FAILED".equals(stage))) throw new IllegalArgumentException("stage 非法");
        if (capturedEventCount < 0 || capturedBytes < 0) throw new IllegalArgumentException("采集计数非法");
        if (!("PASSED".equals(targetOutcome) || "FAILED".equals(targetOutcome)
                || "NOT_EXECUTED".equals(targetOutcome))) {
            throw new IllegalArgumentException("targetOutcome 非法");
        }
        if (testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound) {
            throw new IllegalArgumentException("JUnit 目标计数非法");
        }
        if (("NOT_EXECUTED".equals(targetOutcome)) != (testsFound == 0)
                || ("FAILED".equals(targetOutcome) != (testsFailed > 0 || testsAborted > 0))
                || ("PASSED".equals(targetOutcome) && testsSucceeded == 0)) {
            throw new IllegalArgumentException("targetOutcome 与 JUnit 计数不一致");
        }
        rawSha256 = optionalSha(rawSha256, "rawSha256");
        truncationReasons = List.copyOf(Objects.requireNonNull(truncationReasons));
        if (truncationReasons.size() > 16) throw new IllegalArgumentException("truncationReasons 超限");
        truncationReasons.forEach(value -> bounded(value, "truncationReason", 2_048));
        if (completion == CollectionCompletion.TRUNCATED && truncationReasons.isEmpty()) {
            throw new IllegalArgumentException("TRUNCATED 必须携带截断原因");
        }
        agentFailure = Objects.requireNonNull(agentFailure);
        if ((completion == CollectionCompletion.AGENT_FAILED || completion == CollectionCompletion.TOOL_FAILED)
                && agentFailure.isEmpty()) throw new IllegalArgumentException("失败状态必须携带诊断");
        rawTrace = portablePath(rawTrace, "rawTrace"); stdoutLog = portablePath(stdoutLog, "stdoutLog");
        stderrLog = portablePath(stderrLog, "stderrLog");
        if (timedOut != (completion == CollectionCompletion.TIMED_OUT)) throw new IllegalArgumentException("timedOut 不一致");
        if (!processStarted && exitCode != -1) throw new IllegalArgumentException("未启动时 exitCode 必须为 -1");
        startedAt = Objects.requireNonNull(startedAt); completedAt = Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt 不能早于 startedAt");
    }

    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " 非法");
        return value;
    }
    private static String sha(String value, String name) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException(name + " 必须是 SHA-256");
        return value.toLowerCase(Locale.ROOT);
    }
    private static Optional<String> optionalSha(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(item -> sha(item, name));
    }
    private static String portablePath(String value, String name) {
        String path = bounded(value, name, 1_024);
        if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(name + " 必须为可移植相对路径");
        }
        return path;
    }
}
