package org.example.algorithmdebug.contracts.coordination;

import java.util.Optional;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * Coordinator 的统一 typed 动作请求。
 *
 * @param <T> 具体 Action Handler 的不可变请求载荷类型
 */
public sealed interface AnalysisActionRequest<T> permits AnalysisActionRequest.Command {
    /** @return 请求 Schema 版本 */
    String schemaVersion();

    /** @return 动作类型 */
    AnalysisActionType actionType();

    /** @return 分析归属身份 */
    AnalysisIdentity identity();

    /** @return Workspace 和可选对象目标 */
    ActionTarget target();

    /** @return 有副作用动作的可选幂等操作 ID */
    Optional<OperationId> operationId();

    /** @return 具体动作的 typed 载荷 */
    T payload();

    /**
     * 默认不可绕过的请求实现；Action Registry 仍按 actionType 绑定唯一 Policy 与 Handler。
     *
     * @param schemaVersion 请求 Schema 版本
     * @param actionType 动作类型
     * @param identity 分析归属身份
     * @param target 动作目标
     * @param operationId 幂等操作 ID
     * @param payload typed 动作载荷
     * @param <T> 载荷类型
     */
    record Command<T>(
            String schemaVersion,
            AnalysisActionType actionType,
            AnalysisIdentity identity,
            ActionTarget target,
            Optional<OperationId> operationId,
            T payload) implements AnalysisActionRequest<T> {

        /** 校验版本、身份归属和 typed 载荷。 */
        public Command {
            CoordinationContractChecks.requireVersion(
                    schemaVersion, SchemaVersions.ANALYSIS_ACTION_REQUEST,
                    "AnalysisActionRequest");
            actionType = CoordinationContractChecks.requireNonNull(actionType, "actionType");
            identity = CoordinationContractChecks.requireNonNull(identity, "identity");
            target = CoordinationContractChecks.requireNonNull(target, "target");
            operationId = CoordinationContractChecks.optional(operationId, "operationId");
            payload = CoordinationContractChecks.requireNonNull(payload, "payload");
            if (!target.belongsTo(identity)) {
                throw new IllegalArgumentException(
                        "ActionTarget must belong to the request AnalysisIdentity");
            }
        }
    }
}
