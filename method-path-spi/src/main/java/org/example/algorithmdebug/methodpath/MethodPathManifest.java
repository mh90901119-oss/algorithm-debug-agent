package org.example.algorithmdebug.methodpath;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;

/** 外部 CodePath 工具执行后保留的有界、版本化事实清单。 */
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
        String captureScope,
        String evidenceScope,
        String matchPrecision,
        List<String> packagePrefixes,
        CollectionCompletion completion,
        String stage,
        boolean processStarted,
        int exitCode,
        boolean timedOut,
        long rawEventCount,
        long retainedEventCount,
        long exactDescriptorMatchCount,
        long degradedClassMethodMatchCount,
        long rawBytes,
        long filteredBytes,
        Optional<String> rawSha256,
        Optional<String> filteredSha256,
        List<String> truncationReasons,
        Optional<AgentFailureDiagnostic> agentFailure,
        String stdoutLog,
        String stderrLog,
        Instant startedAt,
        Instant completedAt) {

    /** 校验身份、Hash、范围、计数与完成状态一致性。 */
    public MethodPathManifest {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 MethodPathManifest 版本");
        }
        caseId = Objects.requireNonNull(caseId, "caseId");
        contextId = Objects.requireNonNull(contextId, "contextId");
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        runId = Objects.requireNonNull(runId, "runId");
        planId = Objects.requireNonNull(planId, "planId");
        collectionId = Objects.requireNonNull(collectionId, "collectionId");
        toolName = bounded(toolName, "toolName", 128);
        toolVersion = bounded(toolVersion, "toolVersion", 256);
        toolSha256 = optionalSha(toolSha256, "toolSha256");
        planSha256 = sha(planSha256, "planSha256");
        if (!"PACKAGE_SUPERSET".equals(captureScope)) {
            throw new IllegalArgumentException("captureScope 必须为 PACKAGE_SUPERSET");
        }
        if (!"METHOD_ALLOWLIST".equals(evidenceScope)) {
            throw new IllegalArgumentException("evidenceScope 必须为 METHOD_ALLOWLIST");
        }
        if (!("EXACT_DESCRIPTOR".equals(matchPrecision)
                || "CLASS_METHOD_SUPERSET".equals(matchPrecision)
                || "NONE".equals(matchPrecision))) {
            throw new IllegalArgumentException("matchPrecision 非法");
        }
        packagePrefixes = List.copyOf(Objects.requireNonNull(packagePrefixes, "packagePrefixes"));
        if (packagePrefixes.isEmpty() || packagePrefixes.size() > 200) {
            throw new IllegalArgumentException("packagePrefixes 数量非法");
        }
        completion = Objects.requireNonNull(completion, "completion");
        if (!("REQUEST_ARCHIVED".equals(stage) || "SOURCE_VALIDATED".equals(stage)
                || "CLASSPATH_RESOLVED".equals(stage) || "PROCESS_COMPLETED".equals(stage)
                || "RAW_VALIDATED".equals(stage) || "FILTERED".equals(stage)
                || "COMPLETE".equals(stage) || "FAILED".equals(stage))) {
            throw new IllegalArgumentException("stage 非法");
        }
        if (rawEventCount < 0 || retainedEventCount < 0 || retainedEventCount > rawEventCount
                || exactDescriptorMatchCount < 0 || degradedClassMethodMatchCount < 0
                || exactDescriptorMatchCount + degradedClassMethodMatchCount != retainedEventCount
                || rawBytes < 0 || filteredBytes < 0) {
            throw new IllegalArgumentException("采集计数非法");
        }
        rawSha256 = optionalSha(rawSha256, "rawSha256");
        filteredSha256 = optionalSha(filteredSha256, "filteredSha256");
        truncationReasons = List.copyOf(Objects.requireNonNull(
                truncationReasons, "truncationReasons"));
        if (truncationReasons.size() > 16) {
            throw new IllegalArgumentException("truncationReasons 超限");
        }
        truncationReasons.forEach(value -> bounded(value, "truncationReason", 2_048));
        if (completion == CollectionCompletion.TRUNCATED && truncationReasons.isEmpty()) {
            throw new IllegalArgumentException("TRUNCATED 状态必须携带 truncationReasons");
        }
        agentFailure = Objects.requireNonNull(agentFailure, "agentFailure");
        if (completion == CollectionCompletion.AGENT_FAILED && agentFailure.isEmpty()) {
            throw new IllegalArgumentException("AGENT_FAILED 状态必须携带 agentFailure");
        }
        stdoutLog = portablePath(stdoutLog, "stdoutLog");
        stderrLog = portablePath(stderrLog, "stderrLog");
        if (timedOut != (completion == CollectionCompletion.TIMED_OUT)) {
            throw new IllegalArgumentException("timedOut 与 completion 不一致");
        }
        if (!processStarted && exitCode != -1) {
            throw new IllegalArgumentException("未启动进程时 exitCode 必须为 -1");
        }
        if (degradedClassMethodMatchCount > 0
                && !"CLASS_METHOD_SUPERSET".equals(matchPrecision)) {
            throw new IllegalArgumentException("缺 descriptor 时必须披露降级精度");
        }
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt 不能早于 startedAt");
        }
    }

    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }

    private static String sha(String value, String name) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(name + " 必须是 SHA-256");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static Optional<String> optionalSha(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(item -> sha(item, name));
    }

    private static String portablePath(String value, String name) {
        String path = bounded(value, name, 1_024);
        if (path.startsWith("/") || path.contains("\\") || path.contains("..")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(name + " 必须为可移植相对路径");
        }
        return path;
    }
}
