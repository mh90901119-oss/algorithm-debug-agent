package org.example.algorithmdebug.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;

/** 把历史 CLI 命令确定性映射为 Coordinator typed 请求，不执行 Policy 或业务服务。 */
final class CliActionRequestMapper {
    private static final String CASE_ID_PREFIX = "case-";
    private static final String ANALYSIS_ID_PREFIX = "analysis-";
    private static final String PROBLEM_FRAME_ID_PREFIX = "problem-frame-";
    private static final String OPERATION_ID_PREFIX = "operation-";
    private static final int MAX_TOKEN_LENGTH = 100;

    private final String workspaceId;
    private final Path workspaceRoot;
    private final AnalysisContextResolver contexts;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;
    private final ObjectMapper mapper;

    /**
     * @param workspaceId 不泄漏路径的稳定 Workspace ID
     * @param workspaceRoot 本次 Runtime 已绑定的 Workspace
     * @param contexts 只读 Analysis/Plan 归属解析端口
     * @param clock 可替换时钟
     * @param tokenSupplier 服务端不透明身份 token
     */
    CliActionRequestMapper(
            String workspaceId,
            Path workspaceRoot,
            AnalysisContextResolver contexts,
            Clock clock,
            Supplier<String> tokenSupplier) {
        if (workspaceId == null || workspaceId.isBlank()
                || !workspaceId.equals(workspaceId.strip())
                || workspaceRoot == null || contexts == null || clock == null
                || tokenSupplier == null) {
            throw new IllegalArgumentException("CLI Action mapper dependencies are invalid");
        }
        this.workspaceId = workspaceId;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.contexts = contexts;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
        this.mapper = CliCommandExecutor.strictRequestMapper();
    }

    /** @return 与命令一一对应的 typed ActionRequest */
    AnalysisActionRequest<?> map(CliCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        requireWorkspace(command.workspaceRoot());
        if (command instanceof CliCommand.CaseOpen value) {
            return analysisBegin(value);
        }
        if (command instanceof CliCommand.CaseInspect value) {
            return noInput(
                    AnalysisActionType.CASE_INSPECT,
                    latestIdentity(value.projectId(), value.caseId()),
                    Optional.empty());
        }
        if (command instanceof CliCommand.AlgorithmInputCapture value) {
            return noInput(
                    AnalysisActionType.ALGORITHM_INPUT_CAPTURE,
                    identity(value.projectId(), value.caseId(), value.analysisId()),
                    operation());
        }
        if (command instanceof CliCommand.CaseAudit value) {
            return noInput(
                    AnalysisActionType.CASE_AUDIT,
                    latestIdentity(value.projectId(), value.caseId()),
                    Optional.empty());
        }
        if (command instanceof CliCommand.GanttInspect value) {
            AnalysisIdentity identity = latestIdentity(value.projectId(), value.caseId());
            return request(
                    AnalysisActionType.GANTT_INSPECT, identity,
                    target(identity, Optional.empty()), Optional.empty(),
                    new CoreActionInputs.GanttInspect(
                            value.artifactId(), value.operation(), value.jsonPointer(),
                            value.offset(), value.limit()));
        }
        if (command instanceof CliCommand.RunExecute value) {
            return noInput(
                    AnalysisActionType.RUN_TEST,
                    identity(value.projectId(), value.caseId(), value.analysisId()),
                    operation());
        }
        if (command instanceof CliCommand.StaticAnalyze value) {
            return noInput(
                    AnalysisActionType.STATIC_ANALYZE,
                    identity(value.projectId(), value.caseId(), value.analysisId()),
                    operation());
        }
        if (command instanceof CliCommand.CodePathPlanCreate value) {
            var plan = CliCommandExecutor.readPlanRequest(value.requestFile());
            AnalysisIdentity identity = identity(
                    value.projectId(), value.caseId(), value.analysisId());
            return request(
                    AnalysisActionType.CODEPATH_PLAN_CREATE, identity,
                    target(identity, Optional.of(plan.planId())), operation(),
                    new CoreActionInputs.CodePathPlanCreate(plan));
        }
        if (command instanceof CliCommand.CodePathCollectionExecute value) {
            AnalysisIdentity identity = identity(
                    value.projectId(), value.caseId(),
                    contexts.codePathPlanAnalysis(
                            value.projectId(), value.caseId(), value.planId()));
            return request(
                    AnalysisActionType.CODEPATH_COLLECT, identity,
                    target(identity, Optional.of(value.planId())), operation(),
                    new CoreActionInputs.CodePathCollect(value.planId()));
        }
        if (command instanceof CliCommand.JdwpPlanCreate value) {
            var plan = CliCommandExecutor.readJdwpPlanRequest(value.requestFile());
            AnalysisIdentity identity = identity(
                    value.projectId(), value.caseId(), value.analysisId());
            return request(
                    AnalysisActionType.JDWP_PLAN_CREATE, identity,
                    target(identity, Optional.of(plan.planId())), operation(),
                    new CoreActionInputs.JdwpPlanCreate(plan));
        }
        if (command instanceof CliCommand.JdwpCollectionExecute value) {
            AnalysisIdentity identity = identity(
                    value.projectId(), value.caseId(),
                    contexts.jdwpPlanAnalysis(
                            value.projectId(), value.caseId(), value.planId()));
            return request(
                    AnalysisActionType.JDWP_COLLECT, identity,
                    target(identity, Optional.of(value.planId())), operation(),
                    new CoreActionInputs.JdwpCollect(value.planId()));
        }
        if (command instanceof CliCommand.ArtifactRead value) {
            AnalysisIdentity identity = latestIdentity(value.projectId(), value.caseId());
            return request(
                    AnalysisActionType.ARTIFACT_READ, identity,
                    target(identity, Optional.empty()), Optional.empty(),
                    new CoreActionInputs.ArtifactRead(
                            value.artifactId(), value.offsetBytes(), value.maxBytes()));
        }
        if (command instanceof CliCommand.EvidenceQuery value) {
            AnalysisIdentity identity = latestIdentity(value.projectId(), value.caseId());
            return request(
                    AnalysisActionType.EVIDENCE_QUERY, identity,
                    target(identity, Optional.empty()), Optional.empty(),
                    new CoreActionInputs.EvidenceQuery(
                            value.artifactId(),
                            CliCommandExecutor.readEvidenceQueryRequest(value.requestFile())));
        }
        throw new IllegalArgumentException("Management command cannot be mapped to an Analysis action");
    }

    private AnalysisActionRequest<?> analysisBegin(CliCommand.CaseOpen command) {
        ProblemFrameInput input = readProblemFrameInput(command.questionFile());
        CaseId caseId = command.caseId().orElseGet(this::newCaseId);
        AnalysisId analysisId = new AnalysisId(id(ANALYSIS_ID_PREFIX));
        AnalysisIdentity identity = identity(command.projectId(), caseId, analysisId);
        ProblemFrameId problemFrameId = new ProblemFrameId(id(PROBLEM_FRAME_ID_PREFIX));
        java.time.Instant createdAt = clock.instant();
        final ProblemFrame problemFrame;
        try {
            problemFrame = new ProblemFrame(
                    SchemaVersions.PROBLEM_FRAME,
                    problemFrameId,
                    caseId,
                    analysisId,
                    input.symptom(),
                    input.expectedBehavior(),
                    input.actualBehavior(),
                    command.targetTest(),
                    input.scopeAnchors(),
                    input.knownFactRefs(),
                    input.initialUnknowns(),
                    createdAt);
        } catch (IllegalArgumentException | NullPointerException invalidInput) {
            throw new CliInputException(
                    "question-file contains an invalid Problem Frame", invalidInput);
        }
        return request(
                AnalysisActionType.ANALYSIS_BEGIN, identity,
                target(identity, Optional.empty()), operation(),
                new CoreActionInputs.AnalysisBegin(
                        problemFrame, command.caseId(), command.adapterId()));
    }

    private AnalysisActionRequest<?> noInput(
            AnalysisActionType actionType,
            AnalysisIdentity identity,
            Optional<OperationId> operationId) {
        return request(
                actionType, identity, target(identity, Optional.empty()), operationId,
                CoreActionInputs.NoInput.INSTANCE);
    }

    private <T> AnalysisActionRequest<T> request(
            AnalysisActionType actionType,
            AnalysisIdentity identity,
            ActionTarget target,
            Optional<OperationId> operationId,
            T payload) {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                identity,
                target,
                operationId,
                payload);
    }

    private ActionTarget target(
            AnalysisIdentity identity, Optional<PlanId> planId) {
        return new ActionTarget(
                workspaceId,
                identity.projectId(),
                identity.caseId(),
                identity.analysisId(),
                Optional.empty(),
                planId,
                Optional.empty());
    }

    private AnalysisIdentity latestIdentity(ProjectId projectId, CaseId caseId) {
        return identity(projectId, caseId, contexts.latestAnalysis(projectId, caseId));
    }

    private static AnalysisIdentity identity(
            ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
        return new AnalysisIdentity(projectId, caseId, analysisId);
    }

    private CaseId newCaseId() {
        return new CaseId(id(CASE_ID_PREFIX));
    }

    private Optional<OperationId> operation() {
        return Optional.of(new OperationId(id(OPERATION_ID_PREFIX)));
    }

    private String id(String prefix) {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank() || !token.equals(token.strip())
                || token.length() > MAX_TOKEN_LENGTH
                || token.contains("/") || token.contains("\\") || token.contains(":")) {
            throw new IllegalStateException("CLI identity token is unsafe");
        }
        return prefix + token;
    }

    private ProblemFrameInput readProblemFrameInput(Path path) {
        byte[] bytes = CliCommandExecutor.readBoundedFile(path, "question-file");
        String json = CliCommandExecutor.decodeUtf8(bytes, "question-file");
        try {
            return mapper.readValue(json, ProblemFrameInput.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException(
                    "question-file must be structured Problem Frame JSON", failure);
        }
    }

    private void requireWorkspace(Path commandWorkspace) {
        if (commandWorkspace == null
                || !workspaceRoot.equals(commandWorkspace.toAbsolutePath().normalize())) {
            throw new CliInputException("command Workspace does not match the active Runtime");
        }
    }

    /** 只读解析旧 CLI 缺失的控制身份。 */
    interface AnalysisContextResolver {
        AnalysisId latestAnalysis(ProjectId projectId, CaseId caseId);

        AnalysisId codePathPlanAnalysis(ProjectId projectId, CaseId caseId, PlanId planId);

        AnalysisId jdwpPlanAnalysis(ProjectId projectId, CaseId caseId, PlanId planId);
    }

    private record ProblemFrameInput(
            String symptom,
            String expectedBehavior,
            String actualBehavior,
            List<SourceAnchor> scopeAnchors,
            List<String> knownFactRefs,
            List<String> initialUnknowns) {
    }
}
