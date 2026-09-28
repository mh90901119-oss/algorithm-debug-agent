package org.example.algorithmdebug.mcp;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;
import org.example.algorithmdebug.mcp.input.AlgorithmInputCaptureInput;
import org.example.algorithmdebug.mcp.input.AnalysisBeginInput;
import org.example.algorithmdebug.mcp.input.AnalysisFinalizeInput;
import org.example.algorithmdebug.mcp.input.AnalysisScopedInput;
import org.example.algorithmdebug.mcp.input.AnalysisStatusInput;
import org.example.algorithmdebug.mcp.input.ArtifactReadInput;
import org.example.algorithmdebug.mcp.input.CaseAuditInput;
import org.example.algorithmdebug.mcp.input.CaseInspectInput;
import org.example.algorithmdebug.mcp.input.CodePathCollectInput;
import org.example.algorithmdebug.mcp.input.CodePathPlanCreateInput;
import org.example.algorithmdebug.mcp.input.EvidenceQueryInput;
import org.example.algorithmdebug.mcp.input.GanttInspectInput;
import org.example.algorithmdebug.mcp.input.InvestigationUpdateInput;
import org.example.algorithmdebug.mcp.input.JdwpCollectInput;
import org.example.algorithmdebug.mcp.input.JdwpPlanCreateInput;
import org.example.algorithmdebug.mcp.input.RunTestInput;
import org.example.algorithmdebug.mcp.input.SourceQueryInput;
import org.example.algorithmdebug.mcp.input.StaticAnalyzeInput;

/** 把 17 种 MCP typed 输入确定性映射为 Coordinator ActionRequest，不执行业务。 */
final class McpActionRequestFactory {
    private static final String CASE_PREFIX = "case-mcp-";
    private static final String ANALYSIS_PREFIX = "analysis-mcp-";
    private static final String PROBLEM_FRAME_PREFIX = "problem-frame-mcp-";
    private static final String OPERATION_PREFIX = "operation-mcp-";
    private static final Set<AnalysisActionType> READ_ONLY_ACTIONS = EnumSet.of(
            AnalysisActionType.CASE_INSPECT,
            AnalysisActionType.CASE_AUDIT,
            AnalysisActionType.GANTT_INSPECT,
            AnalysisActionType.ARTIFACT_READ,
            AnalysisActionType.EVIDENCE_QUERY,
            AnalysisActionType.ANALYSIS_STATUS);

    private final Clock clock;

    McpActionRequestFactory(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    AnalysisActionRequest<?> create(
            McpToolDescriptor descriptor, Object input, McpRequestContext context) {
        if (descriptor == null || input == null || context == null
                || !descriptor.inputType().isInstance(input)) {
            throw new IllegalArgumentException("MCP tool input binding is invalid");
        }
        if (input instanceof AnalysisBeginInput begin) {
            return begin(descriptor.actionType(), begin, context);
        }
        AnalysisScopedInput scoped = (AnalysisScopedInput) input;
        AnalysisIdentity identity = new AnalysisIdentity(
                context.projectId(), scoped.caseId(), scoped.analysisId());
        Optional<PlanId> planId = planId(input);
        return request(
                descriptor.actionType(), identity, context, planId,
                operation(descriptor.actionType(), context), payload(input));
    }

    private AnalysisActionRequest<?> begin(
            AnalysisActionType actionType,
            AnalysisBeginInput input,
            McpRequestContext context) {
        if (actionType != AnalysisActionType.ANALYSIS_BEGIN) {
            throw new IllegalArgumentException("Analysis begin input is bound to the wrong action");
        }
        CaseId caseId = input.existingCase().orElseGet(
                () -> new CaseId(CASE_PREFIX + context.invocationToken()));
        AnalysisId analysisId = new AnalysisId(ANALYSIS_PREFIX + context.invocationToken());
        AnalysisIdentity identity = new AnalysisIdentity(context.projectId(), caseId, analysisId);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME,
                new ProblemFrameId(PROBLEM_FRAME_PREFIX + context.invocationToken()),
                caseId,
                analysisId,
                input.symptom(),
                input.expectedBehavior(),
                input.actualBehavior(),
                input.targetTest(),
                input.scopeAnchors(),
                input.knownFactRefs(),
                input.initialUnknowns(),
                clock.instant());
        return request(
                actionType, identity, context, Optional.empty(),
                operation(actionType, context),
                new CoreActionInputs.AnalysisBegin(
                        frame, input.existingCase(), input.adapter()));
    }

    private static Object payload(Object input) {
        if (input instanceof CaseInspectInput
                || input instanceof AlgorithmInputCaptureInput
                || input instanceof CaseAuditInput
                || input instanceof RunTestInput
                || input instanceof StaticAnalyzeInput
                || input instanceof AnalysisStatusInput) {
            return CoreActionInputs.NoInput.INSTANCE;
        }
        if (input instanceof GanttInspectInput value) {
            return new CoreActionInputs.GanttInspect(
                    value.artifactId(), value.operation(), value.jsonPointer(),
                    value.offset(), value.limit());
        }
        if (input instanceof SourceQueryInput value) {
            return new CoreActionInputs.SourceQuery(
                    value.mode(), value.optionalMethodKey(), value.optionalTargetMethodKey(),
                    value.optionalSourceAnchor(), value.optionalSymbol(), value.budget());
        }
        if (input instanceof InvestigationUpdateInput value) {
            return new CoreActionInputs.InvestigationUpdate(value.command());
        }
        if (input instanceof CodePathPlanCreateInput value) {
            return new CoreActionInputs.CodePathPlanCreate(value.request());
        }
        if (input instanceof CodePathCollectInput value) {
            return new CoreActionInputs.CodePathCollect(value.planId());
        }
        if (input instanceof JdwpPlanCreateInput value) {
            return new CoreActionInputs.JdwpPlanCreate(value.request());
        }
        if (input instanceof JdwpCollectInput value) {
            return new CoreActionInputs.JdwpCollect(value.planId());
        }
        if (input instanceof ArtifactReadInput value) {
            return new CoreActionInputs.ArtifactRead(
                    value.artifactId(), value.offsetBytes(), value.maxBytes());
        }
        if (input instanceof EvidenceQueryInput value) {
            return new CoreActionInputs.EvidenceQuery(value.artifactId(), value.request());
        }
        if (input instanceof AnalysisFinalizeInput value) {
            return new CoreActionInputs.AnalysisFinalize(value.candidate());
        }
        throw new IllegalArgumentException("MCP tool input type is not mapped");
    }

    private static Optional<PlanId> planId(Object input) {
        if (input instanceof CodePathPlanCreateInput value) {
            return Optional.of(value.request().planId());
        }
        if (input instanceof CodePathCollectInput value) {
            return Optional.of(value.planId());
        }
        if (input instanceof JdwpPlanCreateInput value) {
            return Optional.of(value.request().planId());
        }
        if (input instanceof JdwpCollectInput value) {
            return Optional.of(value.planId());
        }
        return Optional.empty();
    }

    private static Optional<OperationId> operation(
            AnalysisActionType actionType, McpRequestContext context) {
        return READ_ONLY_ACTIONS.contains(actionType)
                ? Optional.empty()
                : Optional.of(new OperationId(OPERATION_PREFIX + context.invocationToken()));
    }

    private static <T> AnalysisActionRequest<T> request(
            AnalysisActionType actionType,
            AnalysisIdentity identity,
            McpRequestContext context,
            Optional<PlanId> planId,
            Optional<OperationId> operationId,
            T payload) {
        ActionTarget target = new ActionTarget(
                context.workspaceId(), context.projectId(), identity.caseId(),
                identity.analysisId(), Optional.empty(), planId, Optional.empty());
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                identity,
                target,
                operationId,
                payload);
    }
}
