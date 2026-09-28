package org.example.algorithmdebug.contracts.coordination;

import java.util.HashSet;
import java.util.List;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * MCP Tool 面向模型的统一结构化结果。
 *
 * @param schemaVersion Schema 版本
 * @param outcome 工具动作结果
 * @param code 稳定结果代码
 * @param message 有界结果说明
 * @param data 具体动作的 typed 数据
 * @param artifacts 本次动作产生或推荐读取的 Artifact 引用
 * @param control 动作后的确定性控制视图
 * @param <T> 具体动作结果类型
 */
public record CoordinatedToolResult<T>(
        String schemaVersion,
        ActionOutcome outcome,
        String code,
        String message,
        T data,
        List<ArtifactReference> artifacts,
        AnalysisControlView control) {

    /** 校验结果、Artifact 唯一性和拒绝无副作用约束。 */
    public CoordinatedToolResult {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.COORDINATED_TOOL_RESULT,
                "CoordinatedToolResult");
        outcome = CoordinationContractChecks.requireNonNull(outcome, "outcome");
        code = CoordinationContractChecks.requireText(
                code, "code", CoordinationLimits.MAX_CODE_LENGTH, false);
        message = CoordinationContractChecks.requireText(
                message, "message", CoordinationLimits.MAX_MESSAGE_LENGTH, false);
        data = CoordinationContractChecks.requireNonNull(data, "data");
        artifacts = CoordinationContractChecks.immutableUniqueList(
                artifacts, "artifacts", CoordinationLimits.MAX_ARTIFACTS);
        if (new HashSet<>(artifacts.stream().map(ArtifactReference::artifactId).toList()).size()
                != artifacts.size()) {
            throw new IllegalArgumentException("artifacts must have unique artifactId values");
        }
        control = CoordinationContractChecks.requireNonNull(control, "control");
        if (outcome == ActionOutcome.REJECTED && !artifacts.isEmpty()) {
            throw new IllegalArgumentException(
                    "A rejected action cannot return side-effect artifacts");
        }
        if (outcome == ActionOutcome.REJECTED
                && control.decision() != ActionDecisionCode.REJECTED) {
            throw new IllegalArgumentException("A rejected outcome requires a rejected control view");
        }
        if (outcome != ActionOutcome.REJECTED
                && control.decision() != ActionDecisionCode.ALLOWED) {
            throw new IllegalArgumentException(
                    "An executed action requires an allowed control view");
        }
    }
}
