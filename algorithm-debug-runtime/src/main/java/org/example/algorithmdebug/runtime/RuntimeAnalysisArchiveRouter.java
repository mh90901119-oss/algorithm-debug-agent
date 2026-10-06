package org.example.algorithmdebug.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.casecore.AnalysisArchiveReader;
import org.example.algorithmdebug.casecore.AnalysisArchiveSnapshot;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ConclusionDecisionArchive;
import org.example.algorithmdebug.casecore.CoordinationDecisionArchive;
import org.example.algorithmdebug.casecore.OperationJournal;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;
import org.example.algorithmdebug.core.InvestigationApplicationService;
import org.example.algorithmdebug.core.coordination.AnalysisCoordinator;
import org.example.algorithmdebug.core.coordination.AnalysisStateProjector;
import org.example.algorithmdebug.core.coordination.ConclusionGate;
import org.example.algorithmdebug.core.coordination.CoreActionHandlers;
import org.example.algorithmdebug.core.coordination.CoreActionPrerequisites;
import org.example.algorithmdebug.core.coordination.InvestigationStateProjector;
import org.example.algorithmdebug.core.coordination.OperationIdempotencyService;
import org.example.algorithmdebug.evidence.CausalChainEligibilityEvaluator;

/**
 * 把共享 Workspace 内的控制读写按 `AnalysisIdentity.projectId` 路由到唯一 Project Archive。
 *
 * <p>该类只读取控制文档和注册 metadata；Artifact 快照由 case-management 的有界 Reader 生成。</p>
 */
public final class RuntimeAnalysisArchiveRouter implements
        AnalysisCoordinator.StateSource,
        AnalysisCoordinator.DecisionSink,
        OperationIdempotencyService.JournalRouter,
        CoreActionPrerequisites,
        CoreActionHandlers.InvestigationAction,
        CoreActionHandlers.ConclusionAction {

    private static final String ALGORITHM_INPUT = "ALGORITHM_INPUT";
    private static final String METHOD_CATALOG = "METHOD_CATALOG";
    private static final String CODEPATH_PLAN = "CODEPATH_PLAN";
    private static final String JDWP_PLAN = "JDWP_PLAN";

    private final WorkspaceLayout workspace;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final ProjectRegistrationRepository projects;
    private final Clock clock;
    private final Map<String, RuntimeCapabilityStatus> capabilities;

    /** 创建无状态 Project 路由；不在 bootstrap 时创建任何 Workspace 文件。 */
    public RuntimeAnalysisArchiveRouter(
            Path workspaceRoot,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            Clock clock,
            Map<String, RuntimeCapabilityStatus> capabilities) {
        if (workspaceRoot == null || mapper == null || writer == null || clock == null
                || capabilities == null) {
            throw new IllegalArgumentException("Runtime archive router dependencies are invalid");
        }
        this.workspace = WorkspaceLayout.of(workspaceRoot);
        this.mapper = mapper;
        this.writer = writer;
        this.projects = new ProjectRegistrationRepository(mapper, writer);
        this.clock = clock;
        this.capabilities = Map.copyOf(capabilities);
    }

    @Override
    public AnalysisControlView project(AnalysisIdentity identity) {
        AnalysisArchiveSnapshot snapshot = snapshot(identity);
        return switch (snapshot.initialization()) {
            case ABSENT -> bootstrapView(identity);
            case INVALID -> invalidView(identity);
            case INITIALIZED -> new AnalysisStateProjector(
                    snapshot.artifactIndex(), ignored -> snapshot.journal(),
                    new InvestigationStateProjector()).project(identity);
        };
    }

    @Override
    public AnalysisInitialization analysisInitialization(AnalysisIdentity identity) {
        return switch (snapshot(identity).initialization()) {
            case ABSENT -> AnalysisInitialization.ABSENT;
            case INITIALIZED -> AnalysisInitialization.INITIALIZED;
            case INVALID -> AnalysisInitialization.INVALID;
        };
    }

    @Override
    public boolean algorithmInputCaptured(AnalysisIdentity identity) {
        return hasArtifact(identity, ALGORITHM_INPUT);
    }

    @Override
    public boolean methodCatalogAvailable(AnalysisIdentity identity) {
        return hasArtifact(identity, METHOD_CATALOG);
    }

    @Override
    public boolean planAvailable(
            AnalysisIdentity identity, PlanId planId, PlanKind planKind) {
        String type = planKind == PlanKind.CODEPATH ? CODEPATH_PLAN : JDWP_PLAN;
        return snapshot(identity).artifactIndex().forAnalysis(identity).stream()
                .anyMatch(value -> value.planId().filter(planId::equals).isPresent()
                        && type.equals(value.artifact().artifactType()));
    }

    @Override
    public boolean actionAvailable(AnalysisActionType actionType) {
        return switch (actionType) {
            case RUN_TEST -> available("maven");
            case CODEPATH_COLLECT -> available(CodePathRuntimeFactory.CAPABILITY);
            case JDWP_COLLECT -> available(JdwpRuntimeFactory.CAPABILITY);
            default -> true;
        };
    }

    @Override
    public OperationJournal journal(OperationIdempotencyService.Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new OperationJournal(
                controlCasesRoot(context.identity(), context.actionType()), mapper, writer);
    }

    @Override
    public void append(
            String decisionId,
            String inputSha256,
            List<String> provenance,
            Instant decidedAt,
            ActionDecision decision) {
        new CoordinationDecisionArchive(
                controlCasesRoot(decision.identity(), decision.requestedAction()), mapper, writer)
                .appendDecision(
                decisionId, inputSha256, provenance, decidedAt, decision);
    }

    @Override
    public InvestigationState update(
            org.example.algorithmdebug.contracts.coordination.ActionTarget target,
            InvestigationUpdateCommand command) {
        if (target == null || command == null
                || !target.caseId().equals(command.caseId())
                || !target.analysisId().equals(command.analysisId())) {
            throw new IllegalArgumentException(
                    "Investigation command must match its ActionTarget");
        }
        return new InvestigationApplicationService(
                workspace.projectCases(target.projectId()), mapper, writer, clock)
                .update(command);
    }

    @Override
    public ConclusionDecision evaluate(ConclusionCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        AnalysisArchiveSnapshot snapshot = snapshot(candidate.identity());
        if (snapshot.initialization() != AnalysisArchiveSnapshot.Initialization.INITIALIZED) {
            throw new IllegalStateException("Conclusion requires an initialized Analysis");
        }
        InvestigationState investigation = new InvestigationStateProjector()
                .project(snapshot.journal().events());
        AnalysisControlView projected = project(candidate.identity());
        AnalysisControlView finalizeControl = forFinalize(projected);
        CausalChainEligibilityEvaluator.ReferenceCatalog catalog =
                new CausalChainEligibilityEvaluator.ReferenceCatalog(
                        candidate.identity(), snapshot.evidenceEligibility(),
                        snapshot.sourceQueryIds(), snapshot.artifactIds());
        return new ConclusionGate(
                catalog,
                new ConclusionDecisionArchive(
                        casesRoot(candidate.identity()), mapper, writer),
                clock).evaluate(candidate, finalizeControl, investigation);
    }

    /** @return 指定 Analysis 的完整有界快照，供 Runtime Resource 使用 */
    public AnalysisArchiveSnapshot snapshot(AnalysisIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        return new AnalysisArchiveReader(casesRoot(identity), mapper).read(identity);
    }

    private boolean hasArtifact(AnalysisIdentity identity, String type) {
        return snapshot(identity).artifactIndex().forAnalysis(identity).stream()
                .anyMatch(value -> type.equals(value.artifact().artifactType()));
    }

    private boolean available(String capability) {
        RuntimeCapabilityStatus status = capabilities.get(capability);
        return status != null && status.available();
    }

    private Path casesRoot(AnalysisIdentity identity) {
        requireRegisteredProject(identity);
        return workspace.projectCases(identity.projectId());
    }

    private Path controlCasesRoot(
            AnalysisIdentity identity, AnalysisActionType actionType) {
        if (identity == null || actionType == null) {
            throw new IllegalArgumentException("identity and actionType must not be null");
        }
        return actionType == AnalysisActionType.ANALYSIS_BEGIN
                ? ensureAnalysisBeginControlCases(identity)
                : casesRoot(identity);
    }

    private Path ensureAnalysisBeginControlCases(AnalysisIdentity identity) {
        Path projectRoot = requireRegisteredProject(identity);
        Path casesRoot = workspace.projectAnalysisBeginControlCases(identity.projectId());
        try {
            createUnlinkedDirectories(projectRoot, casesRoot);
            return casesRoot;
        } catch (IOException failure) {
            throw new WorkspaceException(
                    "COORDINATION_BOOTSTRAP_ARCHIVE_FAILED",
                    "Analysis begin control archive could not be initialized", failure);
        }
    }

    private Path requireRegisteredProject(AnalysisIdentity identity) {
        Path projectRoot = workspace.projectWorkspace(identity.projectId());
        if (projects.findById(workspace, identity.projectId()).isEmpty()) {
            throw new WorkspaceException(
                    "PROJECT_NOT_REGISTERED",
                    "Runtime archive routing requires a registered Project");
        }
        requireUnlinkedPath(workspace.root(), projectRoot);
        return projectRoot;
    }

    private static void createUnlinkedDirectories(Path projectRoot, Path target)
            throws IOException {
        if (!target.startsWith(projectRoot) || target.equals(projectRoot)) {
            throw new WorkspaceException(
                    "COORDINATION_BOOTSTRAP_ARCHIVE_INVALID",
                    "Analysis begin control archive escaped the Project root");
        }
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(target)) {
            current = current.resolve(segment);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException concurrentCreation) {
                    // 并发创建由下方的 NOFOLLOW_LINKS 校验统一裁决。
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current)) {
                throw new WorkspaceException(
                        "COORDINATION_BOOTSTRAP_ARCHIVE_INVALID",
                        "Analysis begin control archive contains an invalid directory");
            }
        }
    }

    private static void requireUnlinkedPath(Path projectRoot, Path target) {
        Path current = target;
        while (true) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current)) {
                throw new WorkspaceException(
                        "COORDINATION_BOOTSTRAP_ARCHIVE_INVALID",
                        "Analysis begin control archive contains an invalid directory");
            }
            if (current.equals(projectRoot)) {
                return;
            }
            current = current.getParent();
            if (current == null || !current.startsWith(projectRoot)) {
                throw new WorkspaceException(
                        "COORDINATION_BOOTSTRAP_ARCHIVE_INVALID",
                        "Analysis begin control archive escaped the Project root");
            }
        }
    }

    private static AnalysisControlView bootstrapView(AnalysisIdentity identity) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                identity, 0, AnalysisActionType.ANALYSIS_BEGIN,
                ActionDecisionCode.ALLOWED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(AnalysisActionType.ANALYSIS_BEGIN),
                ConclusionStatus.MISSING_EVIDENCE);
    }

    private static AnalysisControlView invalidView(AnalysisIdentity identity) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                identity, 0, AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.REJECTED,
                List.of(CoordinationErrorCode.COORDINATION_STATE_INVALID),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(AnalysisActionType.CASE_INSPECT, AnalysisActionType.ANALYSIS_STATUS),
                ConclusionStatus.MISSING_EVIDENCE);
    }

    private static AnalysisControlView forFinalize(AnalysisControlView source) {
        boolean allowed = source.decision() == ActionDecisionCode.ALLOWED
                && source.allowedActions().contains(AnalysisActionType.ANALYSIS_FINALIZE);
        return new AnalysisControlView(
                source.schemaVersion(), source.policyVersion(), source.identity(),
                source.revision(), AnalysisActionType.ANALYSIS_FINALIZE,
                allowed ? ActionDecisionCode.ALLOWED : ActionDecisionCode.REJECTED,
                allowed ? List.of() : List.of(
                        CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED),
                source.satisfiedObligationIds(), source.remainingObligationIds(),
                source.contradictionIds(), source.openGapIds(),
                source.supportedHypothesisIds(), source.refutedHypothesisIds(),
                source.unevaluatedPredicateIds(), source.allowedActions(),
                source.terminalEligibility());
    }
}
