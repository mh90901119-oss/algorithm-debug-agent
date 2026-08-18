package org.example.algorithmdebug.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.JdwpCollectionManifest;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpCollectionStage;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionEvidenceValidatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final PlanId PLAN_ID = new PlanId("plan-1");
    private static final CollectionId COLLECTION_ID = new CollectionId("collection-1");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "runs");
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;
    private int fixtureCounter;

    @Test
    void completeMatchingMethodPathCollectionIsValid() throws Exception {
        Fixture fixture = fixture(NormalizationStatus.COMPLETE, ComparisonOutcome.MATCHED);

        var validation = new CollectionEvidenceValidator().validateMethodPath(fixture.input());

        assertEquals(EvidenceValidationStatus.VALID, validation.status());
        assertEquals(java.util.Set.of(EvidenceDimension.VALIDATION, EvidenceDimension.METHOD_PATH),
                validation.coveredDimensions());
        assertTrue(validation.summaryArtifact().isPresent());
    }

    @Test
    void rawTamperIsInvalid() throws Exception {
        Fixture fixture = fixture(NormalizationStatus.COMPLETE, ComparisonOutcome.MATCHED);
        Files.writeString(fixture.rawPath(), "{\"eventId\":999}\n");

        var validation = new CollectionEvidenceValidator().validateMethodPath(fixture.input());

        assertEquals(EvidenceValidationStatus.INVALID, validation.status());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                "ARTIFACT_SIZE_MISMATCH".equals(finding.code())
                        || "ARTIFACT_HASH_MISMATCH".equals(finding.code())));
    }

    @Test
    void collectorManifestRawIdentityMismatchIsInvalid() throws Exception {
        Fixture fixture = fixture(
                NormalizationStatus.COMPLETE, ComparisonOutcome.MATCHED, true);

        var validation = new CollectionEvidenceValidator().validateMethodPath(fixture.input());

        assertEquals(EvidenceValidationStatus.INVALID, validation.status());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                "COLLECTOR_RAW_IDENTITY_MISMATCH".equals(finding.code())));
    }

    @Test
    void changedBaselineIsContradictedAndPartialSummaryIsInconclusive() throws Exception {
        var changed = new CollectionEvidenceValidator().validateMethodPath(
                fixture(NormalizationStatus.COMPLETE, ComparisonOutcome.CHANGED).input());
        assertEquals(EvidenceValidationStatus.CONTRADICTED, changed.status());
        assertTrue(changed.coveredDimensions().isEmpty());

        var partial = new CollectionEvidenceValidator().validateMethodPath(
                fixture(NormalizationStatus.PARTIAL, ComparisonOutcome.MATCHED).input());
        assertEquals(EvidenceValidationStatus.INCONCLUSIVE, partial.status());
        assertTrue(partial.coveredDimensions().isEmpty());
    }

    @Test
    void completeMatchingJdwpCollectionIsValid() throws Exception {
        var validation = new CollectionEvidenceValidator().validateJdwp(
                jdwpInput(NormalizationStatus.COMPLETE, false, List.of()));

        assertEquals(EvidenceValidationStatus.VALID, validation.status());
        assertEquals(java.util.Set.of(EvidenceDimension.VALIDATION,
                EvidenceDimension.RUNTIME_STATE), validation.coveredDimensions());
    }

    @Test
    void boundedPartialJdwpCollectionWithHitsRemainsUsable() throws Exception {
        var validation = new CollectionEvidenceValidator().validateJdwp(
                jdwpInput(NormalizationStatus.PARTIAL, true,
                        List.of("COLLECTOR_VALUE_LIMIT", "OUTPUT_BUDGET_EXCEEDED")));

        assertEquals(EvidenceValidationStatus.VALID, validation.status());
        assertEquals(java.util.Set.of(EvidenceDimension.VALIDATION,
                EvidenceDimension.RUNTIME_STATE), validation.coveredDimensions());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                "NORMALIZATION_PARTIAL".equals(finding.code())));
    }

    private JdwpValidationInput jdwpInput(
            NormalizationStatus normalizationStatus,
            boolean truncated,
            List<String> truncationReasons) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(
                "jdwp-" + fixtureCounter++));
        Path raw = Files.writeString(root.resolve("raw-trace.jsonl"),
                "{\"sequence\":1,\"eventType\":\"tracepoint_hit\"}\n");
        Path summaryPath = Files.writeString(root.resolve("summary.json"), "{}\n");
        Path planPath = Files.writeString(root.resolve("plan.json"), "{\"plan\":1}\n");
        ArtifactReference rawReference = reference(
                raw, "jdwp-raw", "JDWP_RAW_TRACE", "raw/raw-trace.jsonl");
        ArtifactReference summaryReference = reference(
                summaryPath, "jdwp-summary", "JDWP_SNAPSHOT_SUMMARY", "derived/summary.json");
        JdwpCollectionRecord collection = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                RUN_ID, PLAN_ID, COLLECTION_ID, TARGET, "JDWP", NOW);
        JdwpCollectionPlan plan = new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, PLAN_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, TARGET, List.of(new JdwpTracepointSpec(
                        "point-1", "fixture.Algorithm#solve()V",
                        new SourceAnchor("fixture.Algorithm", "solve", "()V",
                                "src/main/java/fixture/Algorithm.java", 10, 20, HASH),
                        12, 5, JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults(), "定位方法内部状态", NOW);
        JdwpCollectionManifest manifest = new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                RUN_ID, PLAN_ID, COLLECTION_ID, "jdwp-collector", "1.0",
                sha(planPath), JdwpCollectionCompletion.SUCCESS,
                JdwpCollectionStage.PROCESS_COMPLETED, true, true, 0, 0,
                false, false, 1, rawReference.sizeBytes(), Map.of("point-1", 1),
                Map.of("point-1", 1), Optional.of(rawReference.sha256()), Optional.empty(),
                "raw/raw-trace.jsonl", "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log", NOW, NOW);
        TraceProvenance provenance = new TraceProvenance(
                CASE_ID, CONTEXT_ID, RUN_ID, COLLECTION_ID, rawReference, 1,
                Optional.empty(), Optional.of(1L), "RAW_OBSERVATION");
        JdwpSnapshotSummary summary = new JdwpSnapshotSummary(
                SchemaVersions.JDWP_SNAPSHOT_SUMMARY, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID, rawReference,
                List.of(new JdwpSnapshotSummary.TracepointHit(
                        "point-1", 1, "main", "fixture.Algorithm#solve:12",
                        List.of(new JdwpSnapshotSummary.StackFrame(
                                0, "fixture.Algorithm", "solve", 12)),
                        List.of(), provenance)), List.of(), truncated, NOW);
        NormalizationManifest normalization = new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID, "JDWP",
                "jdwp-snapshot-normalizer", "1.0", normalizationStatus,
                rawReference, Optional.of(summaryReference), NormalizationBudget.defaults(),
                1, 1, truncationReasons, Optional.empty(), "", NOW);
        CollectionBaselineCheck baseline = new CollectionBaselineCheck(
                "1.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, RUN_ID, COLLECTION_ID,
                ComparisonOutcome.MATCHED, Optional.of(new RunId("baseline-run")),
                Optional.empty(), true, "baseline MATCHED", NOW);
        return new JdwpValidationInput(collection, plan, manifest, normalization,
                summary, baseline, rawReference, raw, summaryReference, summaryPath,
                planPath, NOW);
    }

    private Fixture fixture(
            NormalizationStatus normalizationStatus,
            ComparisonOutcome baselineOutcome) throws Exception {
        return fixture(normalizationStatus, baselineOutcome, false);
    }

    private Fixture fixture(
            NormalizationStatus normalizationStatus,
            ComparisonOutcome baselineOutcome,
            boolean corruptManifestRawIdentity) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(
                "fixture-" + fixtureCounter++));
        Path raw = Files.writeString(root.resolve("filtered.jsonl"), """
                {"eventId":1,"eventType":"METHOD_ENTER"}
                """);
        Path summaryPath = Files.writeString(root.resolve("summary.json"), "{}\n");
        Path planPath = Files.writeString(root.resolve("plan.json"), "{\"plan\":1}\n");
        ArtifactReference rawReference = reference(
                raw, "raw-1", "CODEPATH_FILTERED_TRACE", "raw/filtered.jsonl");
        ArtifactReference summaryReference = reference(
                summaryPath, "summary-1", "METHOD_PATH_SUMMARY", "derived/summary.json");
        MethodPathCollectionRecord collection = new MethodPathCollectionRecord(
                "1.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, RUN_ID, PLAN_ID,
                COLLECTION_ID, TARGET, "CODEPATH", NOW);
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, PLAN_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, TARGET,
                List.of(new MethodSelector(
                        "fixture.Algorithm#solve()V", "fixture.Algorithm", "solve", "()V")),
                CollectionBudget.defaults(),
                "定位方法路径", NOW);
        TraceProvenance provenance = new TraceProvenance(
                CASE_ID, CONTEXT_ID, RUN_ID, COLLECTION_ID, rawReference, 1,
                Optional.of(1L), Optional.empty(), "RAW_OBSERVATION");
        boolean partial = normalizationStatus == NormalizationStatus.PARTIAL;
        MethodPathSummary summary = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID, rawReference,
                List.of(new MethodPathSummary.MethodStatistic(
                        "fixture.Algorithm#solve()V", 1, 0, 1, 1, provenance, provenance)),
                List.of(), List.of(), partial, NOW);
        NormalizationManifest normalization = new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID, "CODEPATH",
                "method-path-normalizer", "1.0", normalizationStatus, rawReference,
                Optional.of(summaryReference), NormalizationBudget.defaults(), 1, 1,
                partial ? List.of("COLLECTOR_TRUNCATED") : List.of(), Optional.empty(), "", NOW);
        MethodPathManifest manifest = new MethodPathManifest(
                "2.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID,
                "code-path-tracer", "1.0", Optional.of(HASH), sha(planPath),
                partial ? CollectionCompletion.TRUNCATED : CollectionCompletion.SUCCESS,
                "COMPLETE", true, 0, false, "PASSED", 1, 1, 0, 0, 1,
                rawReference.sizeBytes() + (corruptManifestRawIdentity ? 1 : 0),
                Optional.of(rawReference.sha256()),
                partial ? List.of("COLLECTOR_TRUNCATED") : List.of(), Optional.empty(),
                "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log", NOW, NOW);
        CollectionBaselineCheck baseline = new CollectionBaselineCheck(
                "1.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, RUN_ID, COLLECTION_ID,
                baselineOutcome, Optional.of(new RunId("baseline-run")), Optional.empty(),
                baselineOutcome == ComparisonOutcome.MATCHED, "baseline " + baselineOutcome, NOW);
        MethodPathValidationInput input = new MethodPathValidationInput(
                collection, plan, manifest, normalization, summary, baseline,
                rawReference, raw, summaryReference, summaryPath, planPath,
                NOW);
        return new Fixture(input, raw);
    }

    private static ArtifactReference reference(
            Path path, String id, String type, String relativePath) throws Exception {
        return new ArtifactReference(
                id, type, relativePath, "application/json", sha(path), Files.size(path));
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record Fixture(MethodPathValidationInput input, Path rawPath) {}
}
