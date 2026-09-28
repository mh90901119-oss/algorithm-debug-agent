package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.junit.jupiter.api.Test;

class CodePathCollectionPlanJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTripsExactV7PlanWithoutLegacyIntentAndAlignsSchema() throws Exception {
        CodePathCollectionPlan plan = plan();
        JsonNode json = MAPPER.valueToTree(plan);

        assertEquals(plan, MAPPER.treeToValue(json, CodePathCollectionPlan.class));
        assertEquals("Which path ran?", json.path("questionToAnswer").asText());
        assertEquals("STRUCTURED", json.path("investigationStatus").asText());
        assertEquals("predicate-1",
                json.path("investigationBinding").path("predicateIds").path(0).asText());
        assertFalse(json.has("intent"));
        for (String old : List.of(
                "sourceFingerprintSha256", "packagePrefixes", "captureScope",
                "estimatedPackageEvents")) {
            assertFalse(json.has(old));
        }
        assertFalse(json.path("budget").has("maxCallDepth"));

        JsonNode schema = schema();
        assertEquals(Set.of(
                "schemaVersion", "planId", "caseId", "analysisId", "targetTest",
                "methodSelections", "scopeConditions", "captureMode", "scopeStartOrdinal",
                "maxMatchedScopes", "budget", "rationale", "questionToAnswer",
                "investigationStatus", "investigationBinding", "createdAt"),
                fields(schema.path("required")));
        JsonSchemaTestSupport.assertValid(schemaPath(), MAPPER.writeValueAsString(plan));
    }

    @Test
    void readsV6IntentOnlyAsLegacyUnstructured() throws Exception {
        ObjectNode json = (ObjectNode) MAPPER.valueToTree(plan());
        json.put("schemaVersion", SchemaVersions.CODEPATH_COLLECTION_PLAN_LEGACY);
        json.remove(List.of("questionToAnswer", "investigationStatus", "investigationBinding"));
        json.set("intent", MAPPER.valueToTree(new InvestigationIntent(
                "Which path ran?", "old model hypothesis", List.of(),
                List.of("old expected observation"))));

        CodePathCollectionPlan legacy = MAPPER.treeToValue(json, CodePathCollectionPlan.class);

        assertEquals(InvestigationBindingStatus.LEGACY_UNSTRUCTURED,
                legacy.investigationStatus());
        assertEquals("Which path ran?", legacy.questionToAnswer());
        assertEquals(Optional.empty(), legacy.investigationBinding());
    }

    @Test
    void validatesCaptureModeScopeWindowAndBindingIdentity() {
        CodePathCollectionPlan value = plan();
        CodePathCollectionPlan aggregate = new CodePathCollectionPlan(
                value.schemaVersion(), value.planId(), value.caseId(), value.analysisId(),
                value.targetTest(), value.methodSelections(), value.scopeMethodKey(),
                value.scopeConditions(), CodePathCaptureMode.AGGREGATE, 100, 20,
                value.budget(), value.rationale(), value.questionToAnswer(),
                value.investigationStatus(), value.investigationBinding(), value.createdAt());
        assertEquals(CodePathCaptureMode.AGGREGATE, aggregate.captureMode());
        assertEquals(100, aggregate.scopeStartOrdinal());
        assertEquals(20, aggregate.maxMatchedScopes());
        assertThrows(IllegalArgumentException.class, () -> new CodePathCollectionPlan(
                value.schemaVersion(), value.planId(), value.caseId(), value.analysisId(),
                value.targetTest(), value.methodSelections(), Optional.empty(), List.of(),
                CodePathCaptureMode.TRACE, 2, 1, value.budget(), value.rationale(),
                value.questionToAnswer(), value.investigationStatus(),
                value.investigationBinding(), value.createdAt()));
        assertThrows(IllegalArgumentException.class, () -> new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, value.planId(), value.caseId(),
                new AnalysisId("analysis-other"), value.targetTest(), value.methodSelections(),
                value.scopeMethodKey(), value.scopeConditions(), value.captureMode(), 1, 10_000,
                value.budget(), value.rationale(), value.questionToAnswer(),
                value.investigationStatus(), value.investigationBinding(), value.createdAt()));
    }

    @Test
    void enforcesBudgetsAndExactSelector() {
        assertThrows(IllegalArgumentException.class,
                () -> new CollectionBudget(1, 50L * 1024 * 1024 + 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CollectionBudget(1, 1, 20 * 60_000L + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodSelector("x#run()", "x.Y", "run", "()"));
    }

    private static CodePathCollectionPlan plan() {
        CaseId caseId = new CaseId("case-1");
        AnalysisId analysisId = new AnalysisId("analysis-1");
        InvestigationBinding binding = new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, caseId, analysisId,
                new EvidenceGapId("gap-1"), List.of(new HypothesisId("hypothesis-1")),
                List.of(new ObservationPredicateId("predicate-1")), List.of());
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"), caseId,
                analysisId, new TargetTest("fixture.TargetTest", "runs"),
                List.of(new CodePathMethodSelection(
                        new MethodSelector(
                                "fixture.TargetTest#runs()V", "fixture.TargetTest",
                                "runs", "()V"),
                        List.of(new CodePathProjection(
                                "waferId", CodePathProjectionSource.ARGUMENT,
                                Optional.of(0), List.of("id"), true)))),
                Optional.of("fixture.TargetTest#runs()V"),
                List.of(new CodePathScopeCondition(
                        "waferId", CodePathScalarType.STRING, "W-17")),
                CodePathCaptureMode.TRACE, 1, 10_000, CollectionBudget.defaults(),
                "Locate the key invocation", "Which path ran?",
                InvestigationBindingStatus.STRUCTURED, Optional.of(binding), Instant.EPOCH);
    }

    private static JsonNode schema() throws Exception {
        return MAPPER.readTree(schemaPath().toFile());
    }

    private static Path schemaPath() {
        return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."),
                "schemas", "collection", "codepath-plan-v7.schema.json");
    }

    private static Set<String> fields(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
