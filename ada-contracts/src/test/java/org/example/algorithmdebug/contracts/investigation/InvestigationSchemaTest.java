package org.example.algorithmdebug.contracts.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class InvestigationSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void everyPublishedContractRoundTripsThroughItsSchema() throws Exception {
        Fixtures fixture = fixtures();
        Map<Path, Object> values = new LinkedHashMap<>();
        values.put(schema("investigation", "problem-frame-v1.schema.json"), fixture.frame());
        values.put(schema("investigation", "hypothesis-record-v1.schema.json"), fixture.hypothesis());
        values.put(schema("investigation", "evidence-gap-v1.schema.json"), fixture.gap());
        values.put(schema("investigation", "observation-predicate-v1.schema.json"), fixture.predicate());
        values.put(schema("investigation", "observation-evaluation-v1.schema.json"), fixture.evaluation());
        values.put(schema("investigation", "investigation-binding-v1.schema.json"), fixture.binding());
        values.put(schema("investigation", "investigation-state-v1.schema.json"), fixture.state());
        values.put(schema("investigation", "causal-chain-v1.schema.json"), fixture.chain());
        values.put(schema("source-query", "source-query-request-v1.schema.json"), fixture.sourceRequest());
        values.put(schema("source-query", "source-query-result-v1.schema.json"), fixture.sourceResult());

        for (Map.Entry<Path, Object> entry : values.entrySet()) {
            String json = MAPPER.writeValueAsString(entry.getValue());
            assertValid(entry.getKey(), json);
            ObjectNode unknown = (ObjectNode) MAPPER.readTree(json);
            unknown.put("unexpected", true);
            assertInvalid(entry.getKey(), MAPPER.writeValueAsString(unknown));
        }

        assertEquals(fixture.frame(), roundTrip(fixture.frame(), ProblemFrame.class));
        assertEquals(fixture.hypothesis(), roundTrip(fixture.hypothesis(), HypothesisRecord.class));
        assertEquals(fixture.gap(), roundTrip(fixture.gap(), EvidenceGap.class));
        assertEquals(fixture.predicate(), roundTrip(fixture.predicate(), ObservationPredicate.class));
        assertEquals(fixture.evaluation(), roundTrip(
                fixture.evaluation(), ObservationEvaluation.class));
        assertEquals(fixture.binding(), roundTrip(fixture.binding(), InvestigationBinding.class));
        assertEquals(fixture.state(), roundTrip(fixture.state(), InvestigationState.class));
        assertEquals(fixture.chain(), roundTrip(fixture.chain(), CausalChain.class));
        assertEquals(fixture.sourceRequest(), roundTrip(
                fixture.sourceRequest(), SourceQueryRequest.class));
        assertEquals(fixture.sourceResult(), roundTrip(
                fixture.sourceResult(), SourceQueryResult.class));
    }

    @Test
    void limitsMatchJsonSchemaMaximums() throws Exception {
        JsonNode binding = MAPPER.readTree(schema(
                "investigation", "investigation-binding-v1.schema.json").toFile());
        assertEquals(InvestigationLimits.MAX_PREDICATES_PER_BINDING,
                binding.path("properties").path("predicateIds").path("maxItems").asInt());
        JsonNode sourceRequest = MAPPER.readTree(schema(
                "source-query", "source-query-request-v1.schema.json").toFile());
        JsonNode budget = sourceRequest.path("$defs").path("budget").path("properties");
        assertEquals(SourceQueryLimits.MAX_METHODS,
                budget.path("maxMethods").path("maximum").asInt());
        assertEquals(SourceQueryLimits.MAX_EDGES,
                budget.path("maxEdges").path("maximum").asInt());
        assertEquals(SourceQueryLimits.MAX_DEPTH,
                budget.path("maxDepth").path("maximum").asInt());
        assertEquals(SourceQueryLimits.MAX_PATHS,
                budget.path("maxPaths").path("maximum").asInt());
        assertEquals(SourceQueryLimits.MAX_SOURCE_LINES,
                budget.path("maxSourceLines").path("maximum").asInt());
        assertEquals(SourceQueryLimits.MAX_RESPONSE_BYTES,
                budget.path("maxResponseBytes").path("maximum").asInt());

        JsonNode frame = MAPPER.readTree(schema(
                "investigation", "problem-frame-v1.schema.json").toFile());
        assertEquals(InvestigationLimits.MAX_TEXT_LENGTH,
                frame.path("$defs").path("text").path("maxLength").asInt());
        assertEquals(InvestigationLimits.MAX_SCOPE_ANCHORS,
                frame.path("properties").path("scopeAnchors").path("maxItems").asInt());
        JsonNode state = MAPPER.readTree(schema(
                "investigation", "investigation-state-v1.schema.json").toFile());
        assertEquals(InvestigationLimits.MAX_HYPOTHESES,
                state.path("properties").path("hypotheses").path("maxItems").asInt());
        assertEquals(InvestigationLimits.MAX_GAPS,
                state.path("properties").path("gaps").path("maxItems").asInt());
        assertEquals(InvestigationLimits.MAX_PREDICATES,
                state.path("properties").path("predicates").path("maxItems").asInt());
        assertEquals(InvestigationLimits.MAX_EVALUATIONS,
                state.path("properties").path("evaluations").path("maxItems").asInt());
        JsonNode causal = MAPPER.readTree(schema(
                "investigation", "causal-chain-v1.schema.json").toFile());
        assertEquals(InvestigationLimits.MAX_CAUSAL_NODES,
                causal.path("properties").path("nodes").path("maxItems").asInt());
        assertEquals(InvestigationLimits.MAX_CAUSAL_EDGES,
                causal.path("properties").path("edges").path("maxItems").asInt());

        Map<Path, String> versions = Map.ofEntries(
                Map.entry(schema("investigation", "problem-frame-v1.schema.json"), SchemaVersions.PROBLEM_FRAME),
                Map.entry(schema("investigation", "hypothesis-record-v1.schema.json"), SchemaVersions.HYPOTHESIS_RECORD),
                Map.entry(schema("investigation", "evidence-gap-v1.schema.json"), SchemaVersions.EVIDENCE_GAP),
                Map.entry(schema("investigation", "observation-predicate-v1.schema.json"), SchemaVersions.OBSERVATION_PREDICATE),
                Map.entry(schema("investigation", "observation-evaluation-v1.schema.json"), SchemaVersions.OBSERVATION_EVALUATION),
                Map.entry(schema("investigation", "investigation-binding-v1.schema.json"), SchemaVersions.INVESTIGATION_BINDING),
                Map.entry(schema("investigation", "investigation-state-v1.schema.json"), SchemaVersions.INVESTIGATION_STATE),
                Map.entry(schema("investigation", "causal-chain-v1.schema.json"), SchemaVersions.CAUSAL_CHAIN),
                Map.entry(schema("source-query", "source-query-request-v1.schema.json"), SchemaVersions.SOURCE_QUERY_REQUEST),
                Map.entry(schema("source-query", "source-query-result-v1.schema.json"), SchemaVersions.SOURCE_QUERY_RESULT));
        for (Map.Entry<Path, String> version : versions.entrySet()) {
            JsonNode schemaJson = MAPPER.readTree(version.getKey().toFile());
            assertFalse(schemaJson.path("additionalProperties").asBoolean(true));
            assertEquals(version.getValue(), schemaJson.path("properties")
                    .path("schemaVersion").path("const").asText());
        }
    }

    @Test
    void sourceWindowSchemaAcceptsCodeLayoutAndRejectsUnsafeControls() throws Exception {
        Fixtures fixture = fixtures();
        SourceQueryResult base = fixture.sourceResult();
        SourceQueryResult withWindow = new SourceQueryResult(
                base.schemaVersion(), base.queryId(), base.caseId(), base.analysisId(),
                base.mode(), base.completeness(), base.methodCatalogArtifact(),
                base.methods(), base.edges(), base.paths(),
                List.of(new SourceQueryResult.SourceWindow(
                        new SourceAnchor("a.b.C", "m", "()V",
                                "src/main/java/a/b/C.java", 1, 2),
                        1, 2, "a".repeat(64), "class C {\n\tvoid m() {}\n}")),
                base.limitations(), base.truncated(), base.completedAt());
        Path resultSchema = schema("source-query", "source-query-result-v1.schema.json");
        ObjectNode valid = (ObjectNode) MAPPER.valueToTree(withWindow);

        assertValid(resultSchema, MAPPER.writeValueAsString(valid));
        ((ObjectNode) valid.path("sourceWindows").get(0)).put("text", "unsafe\u0000code");
        assertInvalid(resultSchema, MAPPER.writeValueAsString(valid));
    }

    @Test
    void allInvestigationEventsRoundTripAndRejectUnknownFields() throws Exception {
        Fixtures fixture = fixtures();
        List<InvestigationEvent> events = List.of(
                new InvestigationEvent.ProblemFrameDefined(
                        SchemaVersions.INVESTIGATION_EVENT, "event-1", CASE_ID, ANALYSIS_ID,
                        1, NOW, fixture.frame()),
                new InvestigationEvent.HypothesisAdded(
                        SchemaVersions.INVESTIGATION_EVENT, "event-2", CASE_ID, ANALYSIS_ID,
                        2, NOW, fixture.hypothesis()),
                new InvestigationEvent.EvidenceGapAdded(
                        SchemaVersions.INVESTIGATION_EVENT, "event-3", CASE_ID, ANALYSIS_ID,
                        3, NOW, fixture.gap()),
                new InvestigationEvent.PredicateRegistered(
                        SchemaVersions.INVESTIGATION_EVENT, "event-4", CASE_ID, ANALYSIS_ID,
                        4, NOW, fixture.predicate()),
                new InvestigationEvent.PlanBound(
                        SchemaVersions.INVESTIGATION_EVENT, "event-5", CASE_ID, ANALYSIS_ID,
                        5, NOW, new PlanId("plan-1"), fixture.binding()),
                new InvestigationEvent.ObservationEvaluated(
                        SchemaVersions.INVESTIGATION_EVENT, "event-6", CASE_ID, ANALYSIS_ID,
                        6, NOW, fixture.evaluation()),
                new InvestigationEvent.GapStatusChanged(
                        SchemaVersions.INVESTIGATION_EVENT, "event-7", CASE_ID, ANALYSIS_ID,
                        7, NOW, fixture.gap().gapId(), EvidenceGapStatus.OPEN,
                        EvidenceGapStatus.PLANNED),
                new InvestigationEvent.HypothesisStatusChanged(
                        SchemaVersions.INVESTIGATION_EVENT, "event-8", CASE_ID, ANALYSIS_ID,
                        8, NOW, fixture.hypothesis().hypothesisId(), HypothesisStatus.OPEN,
                        HypothesisStatus.INCONCLUSIVE, fixture.hypothesisEvaluation()));
        Path eventSchema = schema("investigation", "investigation-event-v1.schema.json");
        for (InvestigationEvent event : events) {
            String json = MAPPER.writeValueAsString(event);
            assertValid(eventSchema, json);
            assertEquals(event, MAPPER.readValue(json, InvestigationEvent.class));
            ObjectNode unknown = (ObjectNode) MAPPER.readTree(json);
            unknown.put("unexpected", true);
            assertInvalid(eventSchema, MAPPER.writeValueAsString(unknown));
        }
    }

    @Test
    void schemasRejectCrossFieldContradictions() throws Exception {
        Fixtures fixture = fixtures();

        ObjectNode predicate = (ObjectNode) MAPPER.valueToTree(fixture.predicate());
        predicate.put("operator", ObservationOperator.COUNT_COMPARE.name());
        assertInvalid(schema("investigation", "observation-predicate-v1.schema.json"),
                MAPPER.writeValueAsString(predicate));

        ObjectNode sourceRequest = (ObjectNode) MAPPER.valueToTree(fixture.sourceRequest());
        sourceRequest.put("symbol", "Scheduler");
        assertInvalid(schema("source-query", "source-query-request-v1.schema.json"),
                MAPPER.writeValueAsString(sourceRequest));

        ObjectNode evaluation = (ObjectNode) MAPPER.valueToTree(fixture.evaluation());
        evaluation.put("effectApplied", false);
        assertInvalid(schema("investigation", "observation-evaluation-v1.schema.json"),
                MAPPER.writeValueAsString(evaluation));

        ObjectNode chain = (ObjectNode) MAPPER.valueToTree(fixture.chain());
        ObjectNode edge = (ObjectNode) chain.withArray("edges").get(0);
        edge.withArray("evidenceIds").removeAll();
        edge.withArray("sourceQueryIds").removeAll();
        assertInvalid(schema("investigation", "causal-chain-v1.schema.json"),
                MAPPER.writeValueAsString(chain));
    }

    private static Fixtures fixtures() {
        SourceAnchor anchor = new SourceAnchor(
                "a.b.C", "m", "()V", "src/main/java/a/b/C.java", 10, 20);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"), CASE_ID, ANALYSIS_ID,
                "Unexpected order", "A before B", "B before A",
                new TargetTest("a.b.ScheduleTest", "case1"), List.of(anchor),
                List.of("run-1"), List.of("Which branch was selected?"), NOW);
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, new HypothesisId("hypothesis-1"),
                CASE_ID, ANALYSIS_ID, "Branch X selected B first", HypothesisStatus.OPEN,
                List.of(anchor), List.of(), List.of(), List.of(new EvidenceGapId("gap-1")), NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, new EvidenceGapId("gap-1"), CASE_ID, ANALYSIS_ID,
                "Which branch was selected?", EvidenceGapStatus.OPEN,
                List.of(hypothesis.hypothesisId()), List.of(new ObservationPredicateId("predicate-1")), NOW);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId("predicate-1"), CASE_ID, ANALYSIS_ID,
                hypothesis.hypothesisId(), gap.gapId(), ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("a.b.C#m()V"), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
        ObservationEvaluation evaluation = new ObservationEvaluation(
                SchemaVersions.OBSERVATION_EVALUATION,
                new ObservationEvaluationId("evaluation-1"), CASE_ID, ANALYSIS_ID,
                predicate.predicateId(), ObservationTruth.TRUE,
                List.of(new EvidenceId("evidence-1")), EvidenceSourceCoverage.COMPLETE,
                EvidenceDisposition.CONFIRMATION_ELIGIBLE, true, HypothesisEffect.SUPPORT,
                List.of(), "a".repeat(InvestigationLimits.SHA256_HEX_LENGTH), NOW,
                InvestigationVersions.OBSERVATION_EVALUATOR);
        InvestigationBinding binding = InvestigationBinding.bind(
                gap, List.of(hypothesis), List.of(predicate), List.of(new EvidenceId("evidence-1")));
        HypothesisEvaluation hypothesisEvaluation = new HypothesisEvaluation(
                hypothesis.hypothesisId(), HypothesisStatus.OPEN, HypothesisStatus.INCONCLUSIVE,
                List.of(evaluation.evaluationId()), List.of(), List.of("More evidence required"));
        InvestigationState state = new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, CASE_ID, ANALYSIS_ID, frame,
                List.of(hypothesis), List.of(gap), List.of(predicate), List.of(evaluation),
                8, List.of());
        CausalNode symptom = new CausalNode(
                "node-1", CausalNodeType.SYMPTOM, "Unexpected order",
                List.of(new EvidenceId("evidence-1")), List.of());
        CausalNode mechanism = new CausalNode(
                "node-2", CausalNodeType.SOURCE_MECHANISM, "Branch X chooses B",
                List.of(), List.of(new SourceQueryId("query-1")));
        CausalEdge edge = new CausalEdge(
                "edge-1", symptom.nodeId(), mechanism.nodeId(), "explained-by",
                ClaimClassification.VALIDATOR_CONCLUSION, true,
                List.of(new EvidenceId("evidence-1")), List.of(new SourceQueryId("query-1")));
        CausalChain chain = new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-1"), CASE_ID, ANALYSIS_ID,
                List.of(symptom, mechanism), List.of(edge), NOW);
        ArtifactReference catalog = new ArtifactReference(
                "catalog-1", SourceQueryLimits.METHOD_CATALOG_ARTIFACT_TYPE,
                "analyses/analysis-1/static/method-catalog.json",
                "application/json", "b".repeat(InvestigationLimits.SHA256_HEX_LENGTH), 100);
        SourceQueryRequest sourceRequest = new SourceQueryRequest(
                SchemaVersions.SOURCE_QUERY_REQUEST, new SourceQueryId("query-1"),
                CASE_ID, ANALYSIS_ID, catalog, SourceQueryMode.METHOD,
                Optional.of("a.b.C#m()V"), Optional.empty(), Optional.empty(), Optional.empty(),
                SourceQueryBudget.defaults(), NOW);
        SourceQueryResult sourceResult = new SourceQueryResult(
                SchemaVersions.SOURCE_QUERY_RESULT, sourceRequest.queryId(), CASE_ID, ANALYSIS_ID,
                SourceQueryMode.METHOD, SourceQueryCompleteness.COMPLETE, catalog,
                List.of(), List.of(), List.of(), List.of(), List.of(), false, NOW);
        return new Fixtures(frame, hypothesis, gap, predicate, evaluation, binding,
                hypothesisEvaluation, state, chain, sourceRequest, sourceResult);
    }

    private static void assertValid(Path schema, String instance) throws Exception {
        assertTrue(errors(schema, instance).isEmpty(), () -> "Schema rejected fixture: " + schema);
    }

    private static <T> T roundTrip(T value, Class<T> type) throws Exception {
        return MAPPER.readValue(MAPPER.writeValueAsBytes(value), type);
    }

    private static void assertInvalid(Path schema, String instance) throws Exception {
        assertFalse(errors(schema, instance).isEmpty(), () -> "Schema accepted invalid fixture: " + schema);
    }

    private static java.util.List<com.networknt.schema.Error> errors(
            Path schema, String instance) throws Exception {
        String schemaJson = Files.readString(schema, StandardCharsets.UTF_8);
        var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaJson, InputFormat.JSON);
        return validator.validate(instance, InputFormat.JSON);
    }

    private static Path schema(String directory, String fileName) {
        return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."),
                "schemas", directory, fileName);
    }

    private record Fixtures(
            ProblemFrame frame,
            HypothesisRecord hypothesis,
            EvidenceGap gap,
            ObservationPredicate predicate,
            ObservationEvaluation evaluation,
            InvestigationBinding binding,
            HypothesisEvaluation hypothesisEvaluation,
            InvestigationState state,
            CausalChain chain,
            SourceQueryRequest sourceRequest,
            SourceQueryResult sourceResult) {
    }
}
