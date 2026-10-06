package org.example.algorithmdebug.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.core.coordination.OperationIdempotencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeAnalysisArchiveRouterTest {
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final String INPUT_HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void routesControlStateAndOperationJournalByProjectIdentity() throws Exception {
        RuntimeAnalysisArchiveRouter router = router();
        AnalysisIdentity first = identity("project-1", "case-1", "analysis-1");
        AnalysisIdentity second = identity("project-2", "case-2", "analysis-2");
        register(first.projectId());
        register(second.projectId());
        Path invalidAnalysis = WorkspaceLayout.of(temporaryDirectory)
                .projectCases(first.projectId())
                .resolve("case-1/analyses/analysis-1/analysis-request.json");
        Files.createDirectories(invalidAnalysis.getParent());
        Files.writeString(invalidAnalysis, "{}");
        Files.createDirectories(WorkspaceLayout.of(temporaryDirectory)
                .projectCases(second.projectId()));

        assertEquals(ActionDecisionCode.REJECTED, router.project(first).decision());
        assertEquals(
                CoordinationErrorCode.COORDINATION_STATE_INVALID,
                router.project(first).reasonCodes().getFirst());
        assertEquals(ActionDecisionCode.ALLOWED, router.project(second).decision());

        router.journal(context(first, "operation-1", AnalysisActionType.RUN_TEST)).start(
                first, new OperationId("operation-1"), AnalysisActionType.RUN_TEST,
                INPUT_HASH, NOW);
        router.journal(context(second, "operation-2", AnalysisActionType.RUN_TEST)).start(
                second, new OperationId("operation-2"), AnalysisActionType.RUN_TEST,
                INPUT_HASH, NOW);

        assertTrue(Files.isRegularFile(WorkspaceLayout.of(temporaryDirectory)
                .projectCases(first.projectId())
                .resolve("case-1/analyses/analysis-1/operations/operation-1/started.json")));
        assertTrue(Files.isRegularFile(WorkspaceLayout.of(temporaryDirectory)
                .projectCases(second.projectId())
                .resolve("case-2/analyses/analysis-2/operations/operation-2/started.json")));
    }

    @Test
    void analysisBeginControlRecordsDoNotPrecreateFormalCaseOrAnalysis() throws Exception {
        RuntimeAnalysisArchiveRouter router = router();
        AnalysisIdentity identity = identity("project-1", "case-new", "analysis-new");
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory);
        register(identity.projectId());
        OperationIdempotencyService.Context context = context(
                identity, "operation-begin", AnalysisActionType.ANALYSIS_BEGIN);

        router.journal(context).start(
                identity, context.operationId(), context.actionType(), INPUT_HASH, NOW);
        router.append(
                "decision-begin", INPUT_HASH, java.util.List.of("analysis-revision-0"), NOW,
                new ActionDecision(
                        SchemaVersions.ACTION_DECISION,
                        CoordinationPolicyVersions.CURRENT,
                        identity,
                        0,
                        AnalysisActionType.ANALYSIS_BEGIN,
                        ActionDecisionCode.ALLOWED,
                        ActionSideEffect.CASE_WRITE,
                        java.util.List.of()));

        Path controlCases = layout.projectAnalysisBeginControlCases(identity.projectId());
        assertTrue(Files.isRegularFile(controlCases.resolve(
                "case-new/analyses/analysis-new/operations/operation-begin/started.json")));
        assertTrue(Files.isRegularFile(controlCases.resolve(
                "case-new/analyses/analysis-new/coordination/decision-begin.json")));
        assertTrue(Files.notExists(layout.projectCases(identity.projectId())
                .resolve(identity.caseId().value())));
    }

    @Test
    void analysisBeginRejectsAnUnregisteredProjectDirectory() throws Exception {
        RuntimeAnalysisArchiveRouter router = router();
        AnalysisIdentity identity = identity("project-unregistered", "case-new", "analysis-new");
        Files.createDirectories(WorkspaceLayout.of(temporaryDirectory)
                .projectWorkspace(identity.projectId()));
        OperationIdempotencyService.Context context = context(
                identity, "operation-begin", AnalysisActionType.ANALYSIS_BEGIN);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> router.journal(context));

        assertEquals("PROJECT_NOT_REGISTERED", failure.code());
    }

    private RuntimeAnalysisArchiveRouter router() {
        return new RuntimeAnalysisArchiveRouter(
                temporaryDirectory,
                new BoundedDocumentMapper(),
                new AtomicDocumentWriter(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Map.of(
                        "maven", RuntimeCapabilityStatus.available("maven", "MAVEN_OK"),
                        CodePathRuntimeFactory.CAPABILITY,
                        RuntimeCapabilityStatus.unavailable(
                                CodePathRuntimeFactory.CAPABILITY,
                                "CODEPATH_MISSING", "CodePath is unavailable"),
                        JdwpRuntimeFactory.CAPABILITY,
                        RuntimeCapabilityStatus.unavailable(
                                JdwpRuntimeFactory.CAPABILITY,
                                "JDWP_MISSING", "JDWP is unavailable")));
    }

    private void register(ProjectId projectId) {
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        new ProjectRegistrationRepository(mapper, new AtomicDocumentWriter()).create(
                WorkspaceLayout.of(temporaryDirectory),
                new ProjectRegistration(
                        SchemaVersions.PROJECT_REGISTRATION,
                        projectId,
                        projectId.value(),
                        temporaryDirectory.toString(),
                        temporaryDirectory.toString(),
                        temporaryDirectory.toString(),
                        "pom.xml",
                        "MAVEN",
                        NOW));
    }

    private static AnalysisIdentity identity(
            String projectId, String caseId, String analysisId) {
        return new AnalysisIdentity(
                new ProjectId(projectId), new CaseId(caseId), new AnalysisId(analysisId));
    }

    private static OperationIdempotencyService.Context context(
            AnalysisIdentity identity, String operationId, AnalysisActionType actionType) {
        return new OperationIdempotencyService.Context(
                new OperationId(operationId), identity, actionType, INPUT_HASH);
    }
}
