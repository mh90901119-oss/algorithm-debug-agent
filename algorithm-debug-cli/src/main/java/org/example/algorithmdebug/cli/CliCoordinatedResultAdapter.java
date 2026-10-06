package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.core.ArtifactBackedResult;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;

/** 将 Coordinator 结果收窄为历史 {@code ToolResponse 2.0} 输出，不复制任何授权规则。 */
final class CliCoordinatedResultAdapter {

    /**
     * 保留稳定结果码和 Artifact，同时移除旧 CLI 无法表达的控制视图。
     *
     * @param coordinated Coordinator 的完整 typed 结果
     * @return 兼容 CLI 输出
     */
    ToolResponse<?> adapt(CoordinatedToolResult<?> coordinated) {
        if (coordinated == null) {
            throw new IllegalArgumentException("coordinated must not be null");
        }
        if (coordinated.outcome() == ActionOutcome.SUCCEEDED) {
            return ToolResponse.success(
                    legacyData(coordinated.data()), coordinated.artifacts());
        }
        return ToolResponse.failure(
                coordinated.code(),
                CliFailureMessages.forCode(coordinated.code()),
                coordinated.artifacts());
    }

    private static Object legacyData(Object data) {
        if (data instanceof ArtifactBackedResult<?> result) {
            return result.summary();
        }
        if (data instanceof MultiArtifactBackedResult<?> result) {
            return result.summary();
        }
        return data;
    }
}
