package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.BuildSnapshot;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.InputSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshotStatus;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceSnapshot;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.example.algorithmdebug.contracts.ValidationFinding;

class EvidenceBundleBuilderTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final CollectionId COLLECTION_ID = new CollectionId("collection-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "runs");

    @org.junit.jupiter.api.Test
    void buildsStaticAndValidDynamicCoverageWithoutInterpretingBusinessValues() {
        ArtifactReference gantt = artifact("gantt", "GANTT", "runs/run-1/gantt.json");
        ArtifactReference outcomeArtifact = artifact(
                "outcome", "RUN_OUTCOME_SUMMARY", "runs/run-1/outcome.json");
        ArtifactReference contextArtifact = artifact(
                "context", "CONTEXT_SNAPSHOT", "contexts/context-1/context.json");
        ArtifactReference fingerprintArtifact = artifact(
                "fingerprint", "RUN_RESULT_FINGERPRINT", "runs/run-1/fingerprint.json");
        ArtifactReference summaryArtifact = artifact(
                "method-summary", "METHOD_PATH_SUMMARY",
                "collections/collection-1/derived/evidence-1/summary.json");
        ArtifactReference validationArtifact = artifact(
                "validation", "COLLECTION_VALIDATION",
                "collections/collection-1/derived/evidence-1/validation.json");
        EvidenceBuildRequest request = request(List.of(COLLECTION_ID), List.of(), Set.of(
                EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.INPUT,
                EvidenceDimension.SOURCE, EvidenceDimension.SCHEDULE_RESULT,
                EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION));
        EvidenceBuildSources sources = new EvidenceBuildSources(
                outcome(gantt), outcomeArtifact, context(), contextArtifact,
                Optional.of(new RunResultFingerprint(
                        SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, RUN_ID,
                        Optional.of(gantt.sha256()), Optional.of(HASH), Optional.empty())),
                Optional.of(fingerprintArtifact), List.of(new ValidatedCollectionSource(
                        validation(EvidenceValidationStatus.VALID, summaryArtifact),
                        validationArtifact)));

        var bundle = new EvidenceBundleBuilder().build(request, sources);

        assertEquals(request.requiredDimensions(), bundle.coveredDimensions());
        assertTrue(bundle.facts().stream().allMatch(fact ->
                fact.classification() == ClaimClassification.CONFIRMED_FACT
                        || fact.classification() == ClaimClassification.VALIDATOR_CONCLUSION));
        assertTrue(bundle.artifacts().contains(gantt));
        assertTrue(bundle.artifacts().contains(summaryArtifact));
    }

    @org.junit.jupiter.api.Test
    void keepsInvalidCollectionDiagnosticWithoutDynamicOrValidationCoverage() {
        ArtifactReference summary = artifact(
                "method-summary", "METHOD_PATH_SUMMARY",
                "collections/collection-1/derived/evidence-1/summary.json");
        EvidenceBuildRequest request = request(List.of(COLLECTION_ID), List.of(), Set.of(
                EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION));
        EvidenceBuildSources sources = sources(List.of(new ValidatedCollectionSource(
                validation(EvidenceValidationStatus.INVALID, summary),
                artifact("validation", "COLLECTION_VALIDATION",
                        "collections/collection-1/derived/evidence-1/validation.json"))));

        var bundle = new EvidenceBundleBuilder().build(request, sources);

        assertTrue(!bundle.coveredDimensions().contains(EvidenceDimension.METHOD_PATH));
        assertTrue(!bundle.coveredDimensions().contains(EvidenceDimension.VALIDATION));
        assertTrue(bundle.facts().stream().anyMatch(fact ->
                "COLLECTION_INVALID".equals(fact.code())));
        assertTrue(bundle.facts().stream().anyMatch(fact ->
                "ARTIFACT_HASH_MISMATCH".equals(fact.code())));
    }

    @org.junit.jupiter.api.Test
    void comparisonCollectionNeverCoversCurrentDynamicDimension() {
        CollectionId oldCollection = new CollectionId("collection-old");
        ArtifactReference summary = artifact(
                "old-summary", "METHOD_PATH_SUMMARY",
                "collections/collection-old/derived/evidence-old/summary.json");
        CollectionValidation oldValidation = validation(
                EvidenceValidationStatus.VALID, summary,
                new ContextId("context-old"), oldCollection);
        EvidenceBuildRequest request = request(List.of(), List.of(oldCollection), Set.of(
                EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION));

        var bundle = new EvidenceBundleBuilder().build(request,
                sources(List.of(new ValidatedCollectionSource(oldValidation,
                        artifact("old-validation", "COLLECTION_VALIDATION",
                                "collections/collection-old/derived/evidence-old/validation.json")))));

        assertTrue(!bundle.coveredDimensions().contains(EvidenceDimension.METHOD_PATH));
        assertEquals(1, bundle.comparisonFacts().size());
    }

    @org.junit.jupiter.api.Test
    void targetExceptionWithoutGanttCoversOutcomeButNotScheduleResult() {
        RunOutcomeSummary failed = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, RUN_ID, ProcessOutcome.FAILED,
                TestOutcome.ERROR, GanttOutcome.ABSENT,
                Optional.of(new TargetFailureDiagnostic(
                        FailureCategory.TEST_ERROR, "java.lang.IllegalStateException",
                        "no feasible result", "", "fixture.Algorithm.solve")),
                Optional.empty(), ComparisonOutcome.NOT_COMPARED, "not compared", List.of());
        EvidenceBuildRequest request = request(List.of(), List.of(), Set.of(
                EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.SCHEDULE_RESULT,
                EvidenceDimension.VALIDATION));
        EvidenceBuildSources sources = new EvidenceBuildSources(
                failed, artifact("outcome", "RUN_OUTCOME_SUMMARY", "runs/run-1/outcome.json"),
                context(), artifact("context", "CONTEXT_SNAPSHOT", "contexts/context-1/context.json"),
                Optional.of(new RunResultFingerprint(
                        SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, RUN_ID,
                        Optional.empty(), Optional.empty(), Optional.of(HASH))),
                Optional.of(artifact("fingerprint", "RUN_RESULT_FINGERPRINT",
                        "runs/run-1/fingerprint.json")), List.of());

        var bundle = new EvidenceBundleBuilder().build(request, sources);

        assertTrue(bundle.coveredDimensions().contains(EvidenceDimension.TARGET_OUTCOME));
        assertTrue(!bundle.coveredDimensions().contains(EvidenceDimension.SCHEDULE_RESULT));
    }

    @org.junit.jupiter.api.Test
    void agentFailureDoesNotMasqueradeAsTargetOutcome() {
        RunOutcomeSummary agentFailed = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, RUN_ID, ProcessOutcome.NOT_STARTED,
                TestOutcome.NOT_EXECUTED, GanttOutcome.ABSENT, Optional.empty(),
                Optional.of(new AgentFailureDiagnostic("ARCHIVE_FAILED", "archive failed")),
                ComparisonOutcome.NOT_COMPARED, "not compared", List.of());
        EvidenceBuildSources sources = new EvidenceBuildSources(
                agentFailed, artifact("outcome", "RUN_OUTCOME_SUMMARY", "runs/run-1/outcome.json"),
                context(), artifact("context", "CONTEXT_SNAPSHOT", "contexts/context-1/context.json"),
                Optional.empty(), Optional.empty(), List.of());

        var bundle = new EvidenceBundleBuilder().build(
                request(List.of(), List.of(), Set.of(
                        EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION)),
                sources);

        assertTrue(!bundle.coveredDimensions().contains(EvidenceDimension.TARGET_OUTCOME));
        assertTrue(bundle.facts().stream().anyMatch(fact ->
                fact.classification() == ClaimClassification.MISSING_EVIDENCE
                        && "TARGET_OUTCOME_UNAVAILABLE".equals(fact.code())));
    }

    @org.junit.jupiter.api.Test
    void dropsOnlyComparisonDetailsBeforeRejectingBundleBudget() {
        CollectionId oldCollection = new CollectionId("collection-old");
        String longPath = "collections/" + "history/".repeat(120) + "summary.json";
        ArtifactReference oldSummary = artifact(
                "old-summary", "METHOD_PATH_SUMMARY", longPath);
        CollectionValidation oldValidation = validation(
                EvidenceValidationStatus.VALID, oldSummary,
                new ContextId("context-old"), oldCollection);
        EvidenceBuildRequest request = request(
                List.of(), List.of(oldCollection),
                Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION),
                7_000);

        var bundle = new EvidenceBundleBuilder().build(request,
                sources(List.of(new ValidatedCollectionSource(oldValidation,
                        artifact("old-validation", "COLLECTION_VALIDATION",
                                "collections/" + "history/".repeat(120) + "validation.json")))));

        assertTrue(bundle.truncated());
        assertTrue(bundle.comparisonFacts().isEmpty());
        assertTrue(!bundle.artifacts().contains(oldSummary));
    }

    private static EvidenceBuildRequest request(
            List<CollectionId> current,
            List<CollectionId> comparison,
            Set<EvidenceDimension> dimensions) {
        return request(current, comparison, dimensions, 256 * 1024);
    }

    private static EvidenceBuildRequest request(
            List<CollectionId> current,
            List<CollectionId> comparison,
            Set<EvidenceDimension> dimensions,
            long maxBundleBytes) {
        return new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, current, comparison, dimensions, 64 * 1024, maxBundleBytes, NOW);
    }

    private static EvidenceBuildSources sources(List<ValidatedCollectionSource> collections) {
        ArtifactReference gantt = artifact("gantt", "GANTT", "runs/run-1/gantt.json");
        return new EvidenceBuildSources(
                outcome(gantt), artifact("outcome", "RUN_OUTCOME_SUMMARY", "runs/run-1/outcome.json"),
                context(), artifact("context", "CONTEXT_SNAPSHOT", "contexts/context-1/context.json"),
                Optional.of(new RunResultFingerprint(
                        SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, RUN_ID,
                        Optional.of(gantt.sha256()), Optional.of(HASH), Optional.empty())),
                Optional.of(artifact("fingerprint", "RUN_RESULT_FINGERPRINT",
                        "runs/run-1/fingerprint.json")), collections);
    }

    private static RunOutcomeSummary outcome(ArtifactReference gantt) {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, RUN_ID, ProcessOutcome.SUCCEEDED,
                TestOutcome.PASSED, GanttOutcome.PRESENT, Optional.empty(), Optional.empty(),
                ComparisonOutcome.NOT_COMPARED, "not compared", List.of(gantt));
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID,
                new ProjectId("project-1"), TARGET, "revision",
                new SourceSnapshot(HASH, 1, 10, SnapshotCompleteness.COMPLETE),
                new InputSnapshot(InputSnapshotStatus.PRESENT, "input/data.json", HASH, 10, ""),
                new BuildSnapshot(HASH, "21", "adapter", "1.0"),
                SnapshotCompleteness.COMPLETE, HASH, List.of(), NOW);
    }

    private static CollectionValidation validation(
            EvidenceValidationStatus status, ArtifactReference summary) {
        return validation(status, summary, CONTEXT_ID, COLLECTION_ID);
    }

    private static CollectionValidation validation(
            EvidenceValidationStatus status,
            ArtifactReference summary,
            ContextId contextId,
            CollectionId collectionId) {
        return new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION, EVIDENCE_ID, CASE_ID, contextId,
                ANALYSIS_ID, RUN_ID, new org.example.algorithmdebug.contracts.PlanId("plan-1"),
                collectionId, "CODEPATH", status,
                status == EvidenceValidationStatus.VALID ? List.of() : List.of(
                        new ValidationFinding(
                                "ARTIFACT_HASH_MISMATCH", status,
                                "Raw artifact hash mismatch", List.of(summary), Optional.empty())),
                status == EvidenceValidationStatus.VALID
                        ? Set.of(EvidenceDimension.VALIDATION, EvidenceDimension.METHOD_PATH)
                        : Set.of(), Optional.of(summary), NOW);
    }

    private static ArtifactReference artifact(String id, String type, String path) {
        return new ArtifactReference(id, type, path, "application/json", HASH, 10);
    }
}
