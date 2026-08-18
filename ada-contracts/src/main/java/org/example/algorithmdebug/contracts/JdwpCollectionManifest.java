package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 对 JDWP 双进程运行生成的可信、可移植 Manifest。
 *
 * <p>外部 Collector Manifest 作为 Raw Artifact 保存；本契约只暴露相对路径和经过校验的运行事实。</p>
 */
public record JdwpCollectionManifest(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String toolName,
        String toolVersion,
        String planSha256,
        JdwpCollectionCompletion completion,
        JdwpCollectionStage stage,
        boolean targetStarted,
        boolean collectorStarted,
        int targetExitCode,
        int collectorExitCode,
        boolean timedOut,
        boolean truncated,
        long eventCount,
        long rawBytes,
        Map<String, Integer> hitCounts,
        Map<String, Integer> installedLocations,
        Optional<String> rawTraceSha256,
        Optional<AgentFailureDiagnostic> agentFailure,
        String rawTraceRelativePath,
        String collectorManifestRelativePath,
        String targetStdoutLog,
        String targetStderrLog,
        String collectorStdoutLog,
        String collectorStderrLog,
        Instant startedAt,
        Instant completedAt) {

    /** 校验身份、工具、计数、Hash、路径和时间顺序，并防御性复制计数器。 */
    public JdwpCollectionManifest {
        if (!SchemaVersions.JDWP_COLLECTION_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 JdwpCollectionManifest schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        toolName = ContractChecks.requireBoundedText(toolName, "toolName", 128, false);
        toolVersion = ContractChecks.requireBoundedText(toolVersion, "toolVersion", 256, false);
        planSha256 = ContractChecks.requireSha256(planSha256, "planSha256");
        completion = ContractChecks.requireNonNull(completion, "completion");
        stage = ContractChecks.requireNonNull(stage, "stage");
        if (eventCount < 0 || rawBytes < 0) {
            throw new IllegalArgumentException("JDWP 事件数和 Raw 字节数不能为负数");
        }
        hitCounts = immutableCounters(hitCounts, "hitCounts");
        installedLocations = immutableCounters(installedLocations, "installedLocations");
        rawTraceSha256 = ContractChecks.requireNonNull(rawTraceSha256, "rawTraceSha256")
                .map(value -> ContractChecks.requireSha256(value, "rawTraceSha256 value"));
        agentFailure = ContractChecks.requireNonNull(agentFailure, "agentFailure");
        if (timedOut != (completion == JdwpCollectionCompletion.TIMED_OUT)) {
            throw new IllegalArgumentException("timedOut 与 completion 不一致");
        }
        if (truncated != (completion == JdwpCollectionCompletion.TRUNCATED)) {
            throw new IllegalArgumentException("truncated 与 completion 不一致");
        }
        if (completion == JdwpCollectionCompletion.AGENT_FAILED && agentFailure.isEmpty()) {
            throw new IllegalArgumentException("AGENT_FAILED 必须携带 agentFailure");
        }
        if (!targetStarted && targetExitCode != -1) {
            throw new IllegalArgumentException("目标未启动时 targetExitCode 必须为 -1");
        }
        if (!collectorStarted && collectorExitCode != -1) {
            throw new IllegalArgumentException("Collector 未启动时 collectorExitCode 必须为 -1");
        }
        if (completion == JdwpCollectionCompletion.SUCCESS
                && (!targetStarted || !collectorStarted || targetExitCode != 0
                || collectorExitCode != 0 || rawTraceSha256.isEmpty()
                || stage == JdwpCollectionStage.FAILED)) {
            throw new IllegalArgumentException("SUCCESS 与进程、Raw Trace 或阶段事实不一致");
        }
        rawTraceRelativePath = ContractChecks.requirePortableRelativePath(
                rawTraceRelativePath, "rawTraceRelativePath");
        collectorManifestRelativePath = ContractChecks.requirePortableRelativePath(
                collectorManifestRelativePath, "collectorManifestRelativePath");
        targetStdoutLog = ContractChecks.requirePortableRelativePath(targetStdoutLog, "targetStdoutLog");
        targetStderrLog = ContractChecks.requirePortableRelativePath(targetStderrLog, "targetStderrLog");
        collectorStdoutLog = ContractChecks.requirePortableRelativePath(
                collectorStdoutLog, "collectorStdoutLog");
        collectorStderrLog = ContractChecks.requirePortableRelativePath(
                collectorStderrLog, "collectorStderrLog");
        startedAt = ContractChecks.requireNonNull(startedAt, "startedAt");
        completedAt = ContractChecks.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt 不能早于 startedAt");
        }
    }

    private static Map<String, Integer> immutableCounters(
            Map<String, Integer> values, String fieldName) {
        ContractChecks.requireNonNull(values, fieldName);
        LinkedHashMap<String, Integer> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String checkedKey = ContractChecks.requireOpaqueId(key, fieldName + " key");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(fieldName + " value 不能为负数或 null");
            }
            copied.put(checkedKey, value);
        });
        if (copied.size() > 20) {
            throw new IllegalArgumentException(fieldName + " 数量不能超过 20");
        }
        return Collections.unmodifiableMap(copied);
    }
}
