package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;

/** 会启动目标测试 JVM 的动作门禁。 */
public final class TargetExecutionPolicies {
    private TargetExecutionPolicies() {
    }

    /** 普通 UT 需要已捕获算法输入和 operationId。 */
    public static AnalysisActionPolicy<CoreActionInputs.NoInput, RunOutcomeSummary> runTest(
            CoreActionPrerequisites prerequisites) {
        require(prerequisites);
        return ActionPolicySupport.policy(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                (before, request) -> {
                    CoordinationErrorCode common = targetPrerequisite(
                            prerequisites, request, true);
                    return common;
                },
                (before, request, result, after) -> result != null
                        && result.caseId().equals(request.identity().caseId())
                        && result.analysisId().equals(request.identity().analysisId())
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    /** CodePath 采集只接受当前 Analysis 的 CodePath Plan。 */
    public static AnalysisActionPolicy<
            CoreActionInputs.CodePathCollect,
            MultiArtifactBackedResult<CollectionExecutionSummary>>
            codePathCollect(CoreActionPrerequisites prerequisites) {
        return collection(
                AnalysisActionType.CODEPATH_COLLECT,
                CoreActionPrerequisites.PlanKind.CODEPATH,
                prerequisites,
                CoreActionInputs.CodePathCollect::planId);
    }

    /** JDWP 采集只接受当前 Analysis 的 JDWP Plan。 */
    public static AnalysisActionPolicy<
            CoreActionInputs.JdwpCollect,
            MultiArtifactBackedResult<CollectionExecutionSummary>>
            jdwpCollect(CoreActionPrerequisites prerequisites) {
        return collection(
                AnalysisActionType.JDWP_COLLECT,
                CoreActionPrerequisites.PlanKind.JDWP,
                prerequisites,
                CoreActionInputs.JdwpCollect::planId);
    }

    private static <I> AnalysisActionPolicy<
            I, MultiArtifactBackedResult<CollectionExecutionSummary>> collection(
            AnalysisActionType actionType,
            CoreActionPrerequisites.PlanKind planKind,
            CoreActionPrerequisites prerequisites,
            java.util.function.Function<I, org.example.algorithmdebug.contracts.PlanId> planId) {
        require(prerequisites);
        return ActionPolicySupport.policy(
                actionType,
                ActionSideEffect.TARGET_EXECUTION,
                (before, request) -> {
                    CoordinationErrorCode common = targetPrerequisite(
                            prerequisites, request, false);
                    if (common != null) {
                        return common;
                    }
                    var requestedPlan = planId.apply(request.payload());
                    if (request.target().planId().isEmpty()
                            || !request.target().planId().orElseThrow().equals(requestedPlan)) {
                        return CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
                    }
                    return prerequisites.planAvailable(
                            request.identity(), requestedPlan, planKind)
                            ? null : CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING;
                },
                (before, request, result, after) -> result != null
                        && result.summary().caseId().equals(request.identity().caseId())
                        && result.summary().analysisId().equals(request.identity().analysisId())
                        && result.summary().planId().equals(planId.apply(request.payload()))
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    private static CoordinationErrorCode targetPrerequisite(
            CoreActionPrerequisites prerequisites,
            org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest<?> request,
            boolean requireInput) {
        CoordinationErrorCode initialized = ActionPolicySupport.requireInitialized(
                prerequisites, request);
        if (initialized != null) {
            return initialized;
        }
        if (request.operationId().isEmpty()) {
            return CoordinationErrorCode.ACTION_OPERATION_ID_REQUIRED;
        }
        if (requireInput && !prerequisites.algorithmInputCaptured(request.identity())) {
            return CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING;
        }
        return null;
    }

    private static void require(CoreActionPrerequisites prerequisites) {
        if (prerequisites == null) {
            throw new IllegalArgumentException("prerequisites must not be null");
        }
    }
}
