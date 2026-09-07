package org.example.algorithmdebug.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CodePathMethodSelection;
import org.example.algorithmdebug.contracts.CodePathProjection;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.InvestigationIntent;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregateMethodPathNormalizerTest {
    @TempDir Path directory;

    @Test
    void convertsBoundedAggregateIntoMethodPathSummaryWithoutInvocationFile() throws Exception {
        Path rawPath = Files.writeString(directory.resolve("codepath-aggregate.json"), """
                {"schemaVersion":"1.0","captureMode":"AGGREGATE","depthSemantics":"SELECTED_METHOD_DEPTH",
                 "acceptedEvents":6,
                 "methods":[{"methodKey":"fixture.A#run(Ljava/lang/Object;)V","enterCount":2,"normalExitCount":1,
                   "exceptionalExitCount":1,"minSelectedDepth":0,"maxSelectedDepth":0,
                   "firstEventId":1,"lastEventId":6}],
                 "observedPaths":[],
                 "projectionDistributions":[{"methodKey":"fixture.A#run(Ljava/lang/Object;)V","projectionName":"entity",
                   "path":"arg[0].id","observedCount":2,"otherCount":1,"distinctLimitReached":true,
                   "trackedValueCounts":[{"status":"VALUE","scalarType":"STRING","value":"E-1",
                     "failureCode":null,"count":1}]}],
                 "scope":{"enabled":false,"methodKey":null,"observedInvocations":0,"matchedInvocations":0,
                   "capturedInvocations":0,"skippedByWindowInvocations":0,
                   "unavailableConditionInvocations":0,"incompleteCapturedInvocations":0},
                 "limitations":["VALUE_CARDINALITY_LIMIT_REACHED","TRACKED_VALUES_ARE_NOT_GLOBAL_TOP_VALUES"]}
                """);
        CodePathNormalizationInput input = input(rawPath);

        var result = new AggregateMethodPathNormalizer().normalize(input);

        assertEquals(org.example.algorithmdebug.contracts.NormalizationStatus.PARTIAL,
                result.status(), result.failureDetail());
        var summary = result.summary().orElseThrow();
        assertEquals(CodePathCaptureMode.AGGREGATE, summary.captureMode());
        assertEquals(2, summary.methods().getFirst().enterCount());
        assertEquals(1, summary.projectionDistributions().getFirst().otherCount());
        assertFalse(Files.exists(input.invocationOutputPath()));
    }

    @Test
    void marksUnbalancedMethodEntriesAsIncompleteEvidence() throws Exception {
        Path rawPath = Files.writeString(directory.resolve("unbalanced-aggregate.json"), """
                {"schemaVersion":"1.0","captureMode":"AGGREGATE","depthSemantics":"SELECTED_METHOD_DEPTH",
                 "acceptedEvents":1,
                 "methods":[{"methodKey":"fixture.A#run(Ljava/lang/Object;)V","enterCount":1,
                   "normalExitCount":0,"exceptionalExitCount":0,"minSelectedDepth":0,"maxSelectedDepth":0,
                   "firstEventId":1,"lastEventId":1}],
                 "observedPaths":[],"projectionDistributions":[],
                 "scope":{"enabled":false,"methodKey":null,"observedInvocations":0,"matchedInvocations":0,
                   "capturedInvocations":0,"skippedByWindowInvocations":0,
                   "unavailableConditionInvocations":0,"incompleteCapturedInvocations":0},
                 "limitations":[]}
                """);

        var result = new AggregateMethodPathNormalizer().normalize(input(rawPath));

        assertEquals(org.example.algorithmdebug.contracts.NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.summary().orElseThrow().limitations().contains("TRACE_STRUCTURE_INCOMPLETE"));
    }

    private CodePathNormalizationInput input(Path rawPath) throws Exception {
        CaseId caseId = new CaseId("case-1");
        AnalysisId analysisId = new AnalysisId("analysis-1");
        RunId runId = new RunId("run-1");
        PlanId planId = new PlanId("plan-1");
        CollectionId collectionId = new CollectionId("collection-1");
        TargetTest target = new TargetTest("fixture.Test", "case1");
        String methodKey = "fixture.A#run(Ljava/lang/Object;)V";
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, planId, caseId, analysisId, target,
                List.of(new CodePathMethodSelection(
                        new MethodSelector(methodKey, "fixture.A", "run", "(Ljava/lang/Object;)V"),
                        List.of(new CodePathProjection(
                                "entity", CodePathProjectionSource.ARGUMENT,
                                Optional.of(0), List.of("id"), true)))),
                Optional.empty(), List.of(), CodePathCaptureMode.AGGREGATE, 1, 10_000,
                CollectionBudget.defaults(), "aggregate path",
                new InvestigationIntent("Which path?", "A ran", List.of(), List.of("A count")),
                Instant.EPOCH);
        MethodPathCollectionRecord collection = new MethodPathCollectionRecord(
                "1.0", caseId, analysisId, runId, planId, collectionId,
                target, "CODEPATH", Instant.EPOCH);
        ArtifactReference raw = new ArtifactReference(
                "raw-1", "CODEPATH_RAW_AGGREGATE", "raw/codepath-aggregate.json",
                "application/json", "a".repeat(64), Files.size(rawPath));
        return new CodePathNormalizationInput(
                collection, plan, raw, rawPath, directory.resolve("invocations.jsonl"),
                new EvidenceId("evidence-1"), NormalizationBudget.defaults(), false, Instant.EPOCH);
    }
}
