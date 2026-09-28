package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;

/** 非目标执行的 Analysis 写动作策略。 */
public final class AnalysisActionPolicies {
    private AnalysisActionPolicies() {
    }

    /** 普通 Case 写动作：要求 Analysis 已完整初始化且结果非空。 */
    public static <I, O> AnalysisActionPolicy<I, O> initializedWrite(
            AnalysisActionType actionType,
            CoreActionPrerequisites prerequisites) {
        return initializedWrite(
                actionType, prerequisites, (result, identity) -> result != null);
    }

    /** 普通 Case 写动作，并校验 typed 结果仍属于请求 Analysis。 */
    public static <I, O> AnalysisActionPolicy<I, O> initializedWrite(
            AnalysisActionType actionType,
            CoreActionPrerequisites prerequisites,
            java.util.function.BiPredicate<O,
                    org.example.algorithmdebug.contracts.coordination.AnalysisIdentity>
                    outputIdentity) {
        require(actionType, prerequisites);
        if (outputIdentity == null) {
            throw new IllegalArgumentException("outputIdentity must not be null");
        }
        return ActionPolicySupport.policy(
                actionType,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> ActionPolicySupport.requireInitialized(
                        prerequisites, request),
                (before, request, result, after) -> result != null
                        && outputIdentity.test(result, request.identity())
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    /** Source Query 除初始化外必须绑定当前 Analysis 的 Method Catalog。 */
    public static AnalysisActionPolicy<
            CoreActionInputs.SourceQuery,
            org.example.algorithmdebug.core.StaticAnalysisApplicationService.SourceQueryExecution>
            sourceQuery(CoreActionPrerequisites prerequisites) {
        require(AnalysisActionType.SOURCE_QUERY, prerequisites);
        return ActionPolicySupport.policy(
                AnalysisActionType.SOURCE_QUERY,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> {
                    CoordinationErrorCode initialized = ActionPolicySupport.requireInitialized(
                            prerequisites, request);
                    if (initialized != null) {
                        return initialized;
                    }
                    return prerequisites.methodCatalogAvailable(request.identity())
                            ? null : CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING;
                },
                (before, request, result, after) -> result != null
                        && result.result().caseId().equals(request.identity().caseId())
                        && result.result().analysisId().equals(request.identity().analysisId())
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    /** Plan 创建必须有算法输入、Method Catalog 且 Plan ID 与 Target 一致。 */
    public static <I, O> AnalysisActionPolicy<I, O> planCreate(
            AnalysisActionType actionType,
            CoreActionPrerequisites prerequisites,
            java.util.function.Function<I, org.example.algorithmdebug.contracts.PlanId> planId,
            PlanResultVerifier<O> resultVerifier) {
        require(actionType, prerequisites);
        if (planId == null || resultVerifier == null) {
            throw new IllegalArgumentException("planId and resultVerifier must not be null");
        }
        return ActionPolicySupport.policy(
                actionType,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> {
                    CoordinationErrorCode initialized = ActionPolicySupport.requireInitialized(
                            prerequisites, request);
                    if (initialized != null) {
                        return initialized;
                    }
                    if (!prerequisites.algorithmInputCaptured(request.identity())
                            || !prerequisites.methodCatalogAvailable(request.identity())) {
                        return CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING;
                    }
                    var requestedPlan = planId.apply(request.payload());
                    return request.target().planId().isPresent()
                            && request.target().planId().orElseThrow().equals(requestedPlan)
                            ? null : CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
                },
                (before, request, result, after) -> result != null
                        && resultVerifier.matches(
                        result, request.identity(), planId.apply(request.payload()))
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    /** Conclusion candidate 必须属于当前 Action identity。 */
    public static AnalysisActionPolicy<
            CoreActionInputs.AnalysisFinalize,
            org.example.algorithmdebug.contracts.coordination.ConclusionDecision>
            analysisFinalize(CoreActionPrerequisites prerequisites) {
        require(AnalysisActionType.ANALYSIS_FINALIZE, prerequisites);
        return ActionPolicySupport.policy(
                AnalysisActionType.ANALYSIS_FINALIZE,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> {
                    CoordinationErrorCode initialized = ActionPolicySupport.requireInitialized(
                            prerequisites, request);
                    if (initialized != null) {
                        return initialized;
                    }
                    return request.payload().candidate().identity().equals(request.identity())
                            ? null : CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
                },
                (before, request, result, after) -> result != null
                        && result.identity().equals(request.identity())
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    private static void require(
            AnalysisActionType actionType, CoreActionPrerequisites prerequisites) {
        if (actionType == null || prerequisites == null) {
            throw new IllegalArgumentException("actionType and prerequisites must not be null");
        }
    }

    /** Plan Handler 结果的身份和 Plan ID 后置条件。 */
    @FunctionalInterface
    public interface PlanResultVerifier<O> {
        boolean matches(
                O result,
                org.example.algorithmdebug.contracts.coordination.AnalysisIdentity identity,
                org.example.algorithmdebug.contracts.PlanId planId);
    }
}
