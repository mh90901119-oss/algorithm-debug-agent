package org.example.algorithmdebug.core.coordination;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.casecore.CaseWorkspaceAuditor;
import org.example.algorithmdebug.casecore.GanttArtifactInspector;
import org.example.algorithmdebug.contracts.AlgorithmInputCapture;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ArtifactTextExcerpt;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.CaseWorkspaceAudit;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.EvidenceQueryResult;
import org.example.algorithmdebug.contracts.GanttInspection;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.core.AlgorithmInputApplicationService;
import org.example.algorithmdebug.core.ArtifactBackedResult;
import org.example.algorithmdebug.core.CaseApplicationService;
import org.example.algorithmdebug.core.CodePathPlanSummary;
import org.example.algorithmdebug.core.CollectionApplicationService;
import org.example.algorithmdebug.core.InvestigationApplicationService;
import org.example.algorithmdebug.core.JdwpCollectionApplicationService;
import org.example.algorithmdebug.core.JdwpPlanSummary;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;
import org.example.algorithmdebug.core.RunApplicationService;
import org.example.algorithmdebug.core.StaticAnalysisApplicationService;
import org.example.algorithmdebug.core.StaticAnalysisSummary;

/** 把 17 个 typed Action 唯一绑定到 Policy、Handler 与统一结果元数据。 */
public final class CoreActionHandlers {
    private static final String MESSAGE_COMPLETED = "Action completed";

    private CoreActionHandlers() {
    }

    /**
     * 所有 Action 的 typed Handler 端口。组合根可以直接提供能力降级 Handler，而不注册空绑定。
     */
    public record ActionPorts(
            AnalysisActionHandler<CoreActionInputs.AnalysisBegin, CaseOpenResult> analysisBegin,
            AnalysisActionHandler<CoreActionInputs.NoInput, CaseDigest> caseInspect,
            AnalysisActionHandler<CoreActionInputs.NoInput,
                    ArtifactBackedResult<AlgorithmInputCapture>> algorithmInputCapture,
            AnalysisActionHandler<CoreActionInputs.NoInput, CaseWorkspaceAudit> caseAudit,
            AnalysisActionHandler<CoreActionInputs.GanttInspect, GanttInspection> ganttInspect,
            AnalysisActionHandler<CoreActionInputs.NoInput, RunOutcomeSummary> runTest,
            AnalysisActionHandler<CoreActionInputs.NoInput,
                    ArtifactBackedResult<StaticAnalysisSummary>> staticAnalyze,
            AnalysisActionHandler<CoreActionInputs.SourceQuery,
                    StaticAnalysisApplicationService.SourceQueryExecution> sourceQuery,
            AnalysisActionHandler<CoreActionInputs.InvestigationUpdate,
                    InvestigationState> investigationUpdate,
            AnalysisActionHandler<CoreActionInputs.CodePathPlanCreate,
                    ArtifactBackedResult<CodePathPlanSummary>> codePathPlanCreate,
            AnalysisActionHandler<CoreActionInputs.CodePathCollect,
                    MultiArtifactBackedResult<CollectionExecutionSummary>> codePathCollect,
            AnalysisActionHandler<CoreActionInputs.JdwpPlanCreate,
                    ArtifactBackedResult<JdwpPlanSummary>> jdwpPlanCreate,
            AnalysisActionHandler<CoreActionInputs.JdwpCollect,
                    MultiArtifactBackedResult<CollectionExecutionSummary>> jdwpCollect,
            AnalysisActionHandler<CoreActionInputs.ArtifactRead, ArtifactTextExcerpt> artifactRead,
            AnalysisActionHandler<CoreActionInputs.EvidenceQuery, EvidenceQueryResult> evidenceQuery,
            AnalysisActionHandler<CoreActionInputs.NoInput, AnalysisControlView> analysisStatus,
            AnalysisActionHandler<CoreActionInputs.AnalysisFinalize,
                    ConclusionDecision> analysisFinalize) {
        /** 拒绝任何缺失 Handler，确保 Registry 不存在半绑定动作。 */
        public ActionPorts {
            if (java.util.stream.Stream.of(
                    analysisBegin, caseInspect, algorithmInputCapture, caseAudit, ganttInspect,
                    runTest, staticAnalyze, sourceQuery, investigationUpdate,
                    codePathPlanCreate, codePathCollect, jdwpPlanCreate, jdwpCollect,
                    artifactRead, evidenceQuery, analysisStatus, analysisFinalize)
                    .anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("ActionPorts must not contain null handlers");
            }
        }
    }

    /** 构造完整不可变 Registry；枚举新增但未绑定时测试与 Catalog 审计会立即失败。 */
    public static AnalysisActionRegistry createRegistry(
            CoreActionPrerequisites prerequisites, ActionPorts ports) {
        if (prerequisites == null || ports == null) {
            throw new IllegalArgumentException("prerequisites and ports must not be null");
        }
        return AnalysisActionRegistry.of(List.of(
                binding(
                        AnalysisActionType.ANALYSIS_BEGIN,
                        CoreActionInputs.AnalysisBegin.class,
                        ActionSideEffect.CASE_WRITE,
                        LifecycleActionPolicies.analysisBegin(prerequisites),
                        ports.analysisBegin(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.CASE_INSPECT,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.CASE_INSPECT, prerequisites),
                        ports.caseInspect(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.ALGORITHM_INPUT_CAPTURE,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.initializedWrite(
                                AnalysisActionType.ALGORITHM_INPUT_CAPTURE, prerequisites,
                                (result, identity) ->
                                        result.summary().caseId().equals(identity.caseId())
                                                && result.summary().analysisId().equals(
                                                identity.analysisId())),
                        ports.algorithmInputCapture(), CoreActionHandlers::singleArtifact),
                binding(
                        AnalysisActionType.CASE_AUDIT,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.CASE_AUDIT, prerequisites),
                        ports.caseAudit(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.GANTT_INSPECT,
                        CoreActionInputs.GanttInspect.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.GANTT_INSPECT, prerequisites),
                        ports.ganttInspect(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.RUN_TEST,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.TARGET_EXECUTION,
                        TargetExecutionPolicies.runTest(prerequisites),
                        ports.runTest(), CoreActionHandlers::runArtifacts),
                binding(
                        AnalysisActionType.STATIC_ANALYZE,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.initializedWrite(
                                AnalysisActionType.STATIC_ANALYZE, prerequisites,
                                (result, identity) ->
                                        result.summary().caseId().equals(identity.caseId())
                                                && result.summary().analysisId().equals(
                                                identity.analysisId())),
                        ports.staticAnalyze(), CoreActionHandlers::singleArtifact),
                binding(
                        AnalysisActionType.SOURCE_QUERY,
                        CoreActionInputs.SourceQuery.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.sourceQuery(prerequisites),
                        ports.sourceQuery(), CoreActionHandlers::sourceQueryArtifacts),
                binding(
                        AnalysisActionType.INVESTIGATION_UPDATE,
                        CoreActionInputs.InvestigationUpdate.class,
                        ActionSideEffect.CASE_WRITE,
                        new InvestigationUpdatePolicy(prerequisites),
                        ports.investigationUpdate(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.CODEPATH_PLAN_CREATE,
                        CoreActionInputs.CodePathPlanCreate.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.planCreate(
                                AnalysisActionType.CODEPATH_PLAN_CREATE, prerequisites,
                                value -> value.request().planId(),
                                (result, identity, planId) ->
                                        result.summary().caseId().equals(identity.caseId())
                                                && result.summary().analysisId().equals(
                                                identity.analysisId())
                                                && result.summary().planId().equals(planId)),
                        ports.codePathPlanCreate(), CoreActionHandlers::singleArtifact),
                binding(
                        AnalysisActionType.CODEPATH_COLLECT,
                        CoreActionInputs.CodePathCollect.class,
                        ActionSideEffect.TARGET_EXECUTION,
                        TargetExecutionPolicies.codePathCollect(prerequisites),
                        ports.codePathCollect(), CoreActionHandlers::multipleArtifacts),
                binding(
                        AnalysisActionType.JDWP_PLAN_CREATE,
                        CoreActionInputs.JdwpPlanCreate.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.planCreate(
                                AnalysisActionType.JDWP_PLAN_CREATE, prerequisites,
                                value -> value.request().planId(),
                                (result, identity, planId) ->
                                        result.summary().caseId().equals(identity.caseId())
                                                && result.summary().analysisId().equals(
                                                identity.analysisId())
                                                && result.summary().planId().equals(planId)),
                        ports.jdwpPlanCreate(), CoreActionHandlers::singleArtifact),
                binding(
                        AnalysisActionType.JDWP_COLLECT,
                        CoreActionInputs.JdwpCollect.class,
                        ActionSideEffect.TARGET_EXECUTION,
                        TargetExecutionPolicies.jdwpCollect(prerequisites),
                        ports.jdwpCollect(), CoreActionHandlers::multipleArtifacts),
                binding(
                        AnalysisActionType.ARTIFACT_READ,
                        CoreActionInputs.ArtifactRead.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.ARTIFACT_READ, prerequisites),
                        ports.artifactRead(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.EVIDENCE_QUERY,
                        CoreActionInputs.EvidenceQuery.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.EVIDENCE_QUERY, prerequisites),
                        ports.evidenceQuery(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.ANALYSIS_STATUS,
                        CoreActionInputs.NoInput.class,
                        ActionSideEffect.READ_ONLY,
                        ReadActionPolicies.initialized(
                                AnalysisActionType.ANALYSIS_STATUS, prerequisites),
                        ports.analysisStatus(), CoreActionHandlers::noArtifacts),
                binding(
                        AnalysisActionType.ANALYSIS_FINALIZE,
                        CoreActionInputs.AnalysisFinalize.class,
                        ActionSideEffect.CASE_WRITE,
                        AnalysisActionPolicies.analysisFinalize(prerequisites),
                        ports.analysisFinalize(), CoreActionHandlers::noArtifacts)));
    }

    /** 把现有 ApplicationService 组合为 typed Handler 端口。 */
    public static ActionPorts fromServices(ServiceBindings services) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        return new ActionPorts(
                (target, input, cancellation) -> services.cases().begin(
                        services.workspace(target), target.projectId(), input.problemFrame(),
                        input.existingCaseId(), input.adapterId()),
                (target, input, cancellation) -> services.cases().inspect(
                        services.workspace(target), target.projectId(), target.caseId()),
                (target, input, cancellation) -> services.algorithmInputs().capture(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId()),
                (target, input, cancellation) -> new CaseWorkspaceAuditor().audit(
                        services.workspace(target), target.projectId(), target.caseId()),
                (target, input, cancellation) -> new GanttArtifactInspector().inspect(
                        services.workspace(target), target.projectId(), target.caseId(),
                        input.artifactId(), input.operation(), input.jsonPointer(),
                        input.offset(), input.limit()),
                (target, input, cancellation) -> services.runs().execute(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId()),
                (target, input, cancellation) -> services.staticAnalysis().analyze(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId()),
                (target, input, cancellation) -> services.staticAnalysis().querySource(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId(), input.mode(), input.methodKey(),
                        input.targetMethodKey(), input.sourceAnchor(), input.symbol(),
                        input.budget()),
                (target, input, cancellation) -> services.investigations().update(
                        input.command()),
                (target, input, cancellation) -> services.staticAnalysis().createCodePathPlan(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId(), input.request()),
                (target, input, cancellation) -> services.collections().executeCodePath(
                        services.workspace(target), target.projectId(), target.caseId(),
                        input.planId()),
                (target, input, cancellation) -> services.staticAnalysis().createJdwpPlan(
                        services.workspace(target), target.projectId(), target.caseId(),
                        target.analysisId(), input.request()),
                (target, input, cancellation) -> services.jdwpCollections().execute(
                        services.workspace(target), target.projectId(), target.caseId(),
                        input.planId()),
                (target, input, cancellation) -> services.cases().readArtifact(
                        services.workspace(target), target.projectId(), target.caseId(),
                        input.artifactId(), input.offsetBytes(), input.maxBytes()),
                (target, input, cancellation) -> services.cases().queryEvidence(
                        services.workspace(target), target.projectId(), target.caseId(),
                        input.artifactId(), input.request()),
                (target, input, cancellation) -> services.stateSource().project(
                        new AnalysisIdentity(
                                target.projectId(), target.caseId(), target.analysisId())),
                (target, input, cancellation) -> services.conclusionAction().evaluate(
                        input.candidate()));
    }

    /** 现有服务到 Handler 的不可变组合参数。 */
    public record ServiceBindings(
            String workspaceId,
            Path workspaceRoot,
            CaseApplicationService cases,
            AlgorithmInputApplicationService algorithmInputs,
            RunApplicationService runs,
            StaticAnalysisApplicationService staticAnalysis,
            CollectionApplicationService collections,
            JdwpCollectionApplicationService jdwpCollections,
            InvestigationApplicationService investigations,
            AnalysisCoordinator.StateSource stateSource,
            ConclusionAction conclusionAction) {
        public ServiceBindings {
            if (workspaceId == null || workspaceId.isBlank()
                    || !workspaceId.equals(workspaceId.strip()) || workspaceRoot == null
                    || java.util.stream.Stream.of(
                            cases, algorithmInputs, runs, staticAnalysis, collections,
                            jdwpCollections, investigations, stateSource, conclusionAction)
                            .anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("ServiceBindings fields must be valid");
            }
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        }

        Path workspace(org.example.algorithmdebug.contracts.coordination.ActionTarget target) {
            if (!workspaceId.equals(target.workspaceId())) {
                throw new IllegalArgumentException("ActionTarget workspaceId is not configured");
            }
            return workspaceRoot;
        }
    }

    /** 由 Runtime 组合按 Candidate identity 路由 Reference Catalog 和 Conclusion archive。 */
    @FunctionalInterface
    public interface ConclusionAction {
        ConclusionDecision evaluate(ConclusionCandidate candidate);
    }

    private static <I, O> AnalysisActionBinding<I, O> binding(
            AnalysisActionType actionType,
            Class<I> inputType,
            ActionSideEffect sideEffect,
            AnalysisActionPolicy<I, O> policy,
            AnalysisActionHandler<I, O> handler,
            AnalysisActionBinding.ResultAdapter<O> resultAdapter) {
        return new AnalysisActionBinding<>(
                actionType, inputType, sideEffect, policy, handler, resultAdapter);
    }

    private static AnalysisActionBinding.ResultMetadata noArtifacts(Object ignored) {
        return AnalysisActionBinding.ResultMetadata.withoutArtifacts(
                "ACTION_COMPLETED", MESSAGE_COMPLETED);
    }

    private static AnalysisActionBinding.ResultMetadata singleArtifact(
            ArtifactBackedResult<?> result) {
        return artifacts("ACTION_COMPLETED", List.of(result.artifact()), result.artifact());
    }

    private static AnalysisActionBinding.ResultMetadata multipleArtifacts(
            MultiArtifactBackedResult<?> result) {
        Optional<ArtifactReference> primary = result.artifacts().stream().findFirst();
        return artifacts("ACTION_COMPLETED", result.artifacts(), primary.orElse(null));
    }

    private static AnalysisActionBinding.ResultMetadata runArtifacts(RunOutcomeSummary result) {
        Optional<ArtifactReference> primary = result.artifacts().stream().findFirst();
        return artifacts("RUN_TEST_COMPLETED", result.artifacts(), primary.orElse(null));
    }

    private static AnalysisActionBinding.ResultMetadata sourceQueryArtifacts(
            StaticAnalysisApplicationService.SourceQueryExecution result) {
        return artifacts(
                "SOURCE_QUERY_COMPLETED",
                List.of(result.requestArtifact(), result.resultArtifact()),
                result.resultArtifact());
    }

    private static AnalysisActionBinding.ResultMetadata artifacts(
            String code, List<ArtifactReference> artifacts, ArtifactReference primary) {
        return new AnalysisActionBinding.ResultMetadata(
                code, MESSAGE_COMPLETED, artifacts,
                primary == null ? Optional.empty() : Optional.of(primary.artifactId()));
    }
}
