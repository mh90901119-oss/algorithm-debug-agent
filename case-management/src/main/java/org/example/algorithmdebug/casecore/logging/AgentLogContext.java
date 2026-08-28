package org.example.algorithmdebug.casecore.logging;

import java.nio.file.Path;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;

/**
 * 一条执行日志的 Case 关联身份；只保存稳定 ID，不保存问题、输入或 Trace 正文。
 */
public record AgentLogContext(
        Path workspaceRoot,
        String projectId,
        String caseId,
        String analysisId,
        String runId,
        String planId,
        String collectionId,
        String evidenceId,
        String artifactId) {

    /** @return 尚未建立 Case 的启动上下文。 */
    public static AgentLogContext bootstrap() {
        return new AgentLogContext(null, null, null, null, null, null, null, null, null);
    }

    /** @return 已归属到指定 Case 的上下文。 */
    public static AgentLogContext forCase(
            Path workspaceRoot, ProjectId projectId, CaseId caseId) {
        if (workspaceRoot == null || projectId == null || caseId == null) {
            throw new IllegalArgumentException("Case log identity must not be null");
        }
        return new AgentLogContext(
                workspaceRoot.toAbsolutePath().normalize(), projectId.value(), caseId.value(),
                null, null, null, null, null, null);
    }

    /** @return 增加 Analysis 关联后的不可变上下文。 */
    public AgentLogContext withAnalysis(AnalysisId value) {
        return copy(value == null ? null : value.value(), runId, planId, collectionId,
                evidenceId, artifactId);
    }

    /** @return 增加 Run 关联后的不可变上下文。 */
    public AgentLogContext withRun(String value) {
        return copy(analysisId, value, planId, collectionId, evidenceId, artifactId);
    }

    /** @return 增加 Plan 关联后的不可变上下文。 */
    public AgentLogContext withPlan(String value) {
        return copy(analysisId, runId, value, collectionId, evidenceId, artifactId);
    }

    /** @return 增加 Collection 关联后的不可变上下文。 */
    public AgentLogContext withCollection(String value) {
        return copy(analysisId, runId, planId, value, evidenceId, artifactId);
    }

    /** @return 增加 Evidence 关联后的不可变上下文。 */
    public AgentLogContext withEvidence(String value) {
        return copy(analysisId, runId, planId, collectionId, value, artifactId);
    }

    /** @return 增加 Artifact 关联后的不可变上下文。 */
    public AgentLogContext withArtifact(String value) {
        return copy(analysisId, runId, planId, collectionId, evidenceId, value);
    }

    /** @return 是否具备可安全路由到 Case 的完整身份。 */
    public boolean hasCaseIdentity() {
        return workspaceRoot != null && nonBlank(projectId) && nonBlank(caseId);
    }

    private AgentLogContext copy(
            String analysis, String run, String plan, String collection,
            String evidence, String artifact) {
        return new AgentLogContext(
                workspaceRoot, projectId, caseId, analysis, run, plan, collection,
                evidence, artifact);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
