package org.example.algorithmdebug.contracts.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

class CoordinationSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    @Test
    void everyContractRoundTripsThroughItsSchema() throws Exception {
        Fixtures fixtures = fixtures();
        Map<Path, Object> values = new LinkedHashMap<>();
        values.put(schemaPath("coordination", "analysis-action-request-v1.schema.json"), fixtures.request());
        values.put(schemaPath("coordination", "analysis-control-view-v1.schema.json"), fixtures.control());
        values.put(schemaPath("coordination", "action-decision-v1.schema.json"), fixtures.decision());
        values.put(schemaPath("coordination", "evidence-obligation-v1.schema.json"), fixtures.obligation());
        values.put(schemaPath("tool", "coordinated-tool-result-v1.schema.json"), fixtures.result());
        values.put(schemaPath("coordination", "operation-receipt-v1.schema.json"), fixtures.receipt());
        values.put(schemaPath("coordination", "conclusion-candidate-v1.schema.json"), fixtures.candidate());
        values.put(schemaPath("coordination", "conclusion-decision-v1.schema.json"), fixtures.conclusionDecision());

        for (Map.Entry<Path, Object> fixture : values.entrySet()) {
            assertValid(fixture.getKey(), MAPPER.writeValueAsString(fixture.getValue()));
        }

        assertEquals(fixtures.request(), MAPPER.readValue(
                MAPPER.writeValueAsBytes(fixtures.request()), AnalysisActionRequest.Command.class));
        assertEquals(fixtures.control(), roundTrip(fixtures.control(), AnalysisControlView.class));
        assertEquals(fixtures.decision(), roundTrip(fixtures.decision(), ActionDecision.class));
        assertEquals(fixtures.obligation(), roundTrip(fixtures.obligation(), EvidenceObligation.class));
        assertEquals(fixtures.result(), MAPPER.readValue(
                MAPPER.writeValueAsBytes(fixtures.result()),
                new TypeReference<CoordinatedToolResult<Map<String, String>>>() { }));
        assertEquals(fixtures.receipt(), roundTrip(fixtures.receipt(), OperationReceipt.class));
        assertEquals(fixtures.candidate(), roundTrip(fixtures.candidate(), ConclusionCandidate.class));
        assertEquals(fixtures.conclusionDecision(), roundTrip(
                fixtures.conclusionDecision(), ConclusionDecision.class));
    }

    @Test
    void schemasRejectUnknownFieldsAndOversizedText() throws Exception {
        Fixtures fixtures = fixtures();
        Map<Path, Object> values = Map.of(
                schemaPath("coordination", "analysis-action-request-v1.schema.json"), fixtures.request(),
                schemaPath("coordination", "analysis-control-view-v1.schema.json"), fixtures.control(),
                schemaPath("coordination", "action-decision-v1.schema.json"), fixtures.decision(),
                schemaPath("coordination", "evidence-obligation-v1.schema.json"), fixtures.obligation(),
                schemaPath("tool", "coordinated-tool-result-v1.schema.json"), fixtures.result(),
                schemaPath("coordination", "operation-receipt-v1.schema.json"), fixtures.receipt(),
                schemaPath("coordination", "conclusion-candidate-v1.schema.json"), fixtures.candidate(),
                schemaPath("coordination", "conclusion-decision-v1.schema.json"), fixtures.conclusionDecision());

        for (Map.Entry<Path, Object> fixture : values.entrySet()) {
            ObjectNode json = (ObjectNode) MAPPER.valueToTree(fixture.getValue());
            json.put("unexpected", true);
            assertInvalid(fixture.getKey(), MAPPER.writeValueAsString(json));
        }

        ObjectNode oversized = (ObjectNode) MAPPER.valueToTree(fixtures.result());
        oversized.put("message", "x".repeat(CoordinationLimits.MAX_MESSAGE_LENGTH + 1));
        assertInvalid(schemaPath("tool", "coordinated-tool-result-v1.schema.json"),
                MAPPER.writeValueAsString(oversized));
    }

    @Test
    void schemasRejectContradictoryOutcomeSemantics() throws Exception {
        Fixtures fixtures = fixtures();

        ObjectNode decision = (ObjectNode) MAPPER.valueToTree(fixtures.decision());
        decision.withArray("reasonCodes").add(
                CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED.name());
        assertInvalid(schemaPath("coordination", "action-decision-v1.schema.json"),
                MAPPER.writeValueAsString(decision));

        ObjectNode obligation = (ObjectNode) MAPPER.valueToTree(fixtures.obligation());
        obligation.withArray("reasonCodes").add(
                CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING.name());
        assertInvalid(schemaPath("coordination", "evidence-obligation-v1.schema.json"),
                MAPPER.writeValueAsString(obligation));

        ObjectNode result = (ObjectNode) MAPPER.valueToTree(fixtures.result());
        result.put("outcome", ActionOutcome.REJECTED.name());
        assertInvalid(schemaPath("tool", "coordinated-tool-result-v1.schema.json"),
                MAPPER.writeValueAsString(result));

        ObjectNode receipt = (ObjectNode) MAPPER.valueToTree(fixtures.receipt());
        receipt.put("errorCode", CoordinationErrorCode.OPERATION_UNCERTAIN.name());
        assertInvalid(schemaPath("coordination", "operation-receipt-v1.schema.json"),
                MAPPER.writeValueAsString(receipt));

        ObjectNode conclusion = (ObjectNode) MAPPER.valueToTree(fixtures.conclusionDecision());
        conclusion.put("decision", ActionDecisionCode.ALLOWED.name());
        assertInvalid(schemaPath("coordination", "conclusion-decision-v1.schema.json"),
                MAPPER.writeValueAsString(conclusion));

        ObjectNode candidate = (ObjectNode) MAPPER.valueToTree(fixtures.candidate());
        ObjectNode claim = (ObjectNode) candidate.withArray("claims").get(0);
        claim.withArray("evidenceIds").removeAll();
        claim.withArray("artifactReferences").removeAll();
        claim.withArray("sourceReferenceIds").removeAll();
        assertInvalid(schemaPath("coordination", "conclusion-candidate-v1.schema.json"),
                MAPPER.writeValueAsString(candidate));
    }

    @Test
    void everySchemaIsStrictAndUsesThePublishedVersion() throws Exception {
        Map<Path, String> versions = Map.of(
                schemaPath("coordination", "analysis-action-request-v1.schema.json"),
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                schemaPath("coordination", "analysis-control-view-v1.schema.json"),
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                schemaPath("coordination", "action-decision-v1.schema.json"),
                SchemaVersions.ACTION_DECISION,
                schemaPath("coordination", "evidence-obligation-v1.schema.json"),
                SchemaVersions.EVIDENCE_OBLIGATION,
                schemaPath("tool", "coordinated-tool-result-v1.schema.json"),
                SchemaVersions.COORDINATED_TOOL_RESULT,
                schemaPath("coordination", "operation-receipt-v1.schema.json"),
                SchemaVersions.OPERATION_RECEIPT,
                schemaPath("coordination", "conclusion-candidate-v1.schema.json"),
                SchemaVersions.CONCLUSION_CANDIDATE,
                schemaPath("coordination", "conclusion-decision-v1.schema.json"),
                SchemaVersions.CONCLUSION_DECISION);
        for (Map.Entry<Path, String> entry : versions.entrySet()) {
            JsonNode json = MAPPER.readTree(entry.getKey().toFile());
            assertFalse(json.path("additionalProperties").asBoolean(true),
                    entry.getKey().toString());
            assertEquals(entry.getValue(), json.path("properties")
                    .path("schemaVersion").path("const").asText());
        }

        JsonNode request = MAPPER.readTree(schemaPath(
                "coordination", "analysis-action-request-v1.schema.json").toFile());
        assertEquals(actionTypes(), enumValues(
                request.path("$defs").path("actionType").path("enum")));
        Map<Path, String> errorCodeEnums = Map.of(
                schemaPath("coordination", "analysis-control-view-v1.schema.json"),
                "/$defs/errorCodes/items/enum",
                schemaPath("coordination", "action-decision-v1.schema.json"),
                "/$defs/errorCodes/items/enum",
                schemaPath("coordination", "evidence-obligation-v1.schema.json"),
                "/$defs/errorCodes/items/enum",
                schemaPath("coordination", "conclusion-decision-v1.schema.json"),
                "/$defs/errorCodes/items/enum",
                schemaPath("coordination", "operation-receipt-v1.schema.json"),
                "/$defs/errorCode/enum",
                schemaPath("tool", "coordinated-tool-result-v1.schema.json"),
                "/$defs/errorCodes/items/enum");
        for (Map.Entry<Path, String> entry : errorCodeEnums.entrySet()) {
            JsonNode schema = MAPPER.readTree(entry.getKey().toFile());
            assertEquals(coordinationErrorCodes(), enumValues(schema.at(entry.getValue())),
                    entry.getKey().toString());
        }
        assertEquals(CoordinationLimits.MAX_ID_LENGTH,
                request.path("$defs").path("opaqueId").path("maxLength").asInt());
        assertEquals(CoordinationLimits.MAX_WORKSPACE_ID_LENGTH,
                request.path("$defs").path("target").path("properties")
                        .path("workspaceId").path("maxLength").asInt());

        JsonNode result = MAPPER.readTree(schemaPath(
                "tool", "coordinated-tool-result-v1.schema.json").toFile());
        assertEquals(CoordinationLimits.MAX_CODE_LENGTH,
                result.path("properties").path("code").path("maxLength").asInt());
        assertEquals(CoordinationLimits.MAX_MESSAGE_LENGTH,
                result.path("properties").path("message").path("maxLength").asInt());
        assertEquals(CoordinationLimits.MAX_ARTIFACTS,
                result.path("properties").path("artifacts").path("maxItems").asInt());

        JsonNode candidate = MAPPER.readTree(schemaPath(
                "coordination", "conclusion-candidate-v1.schema.json").toFile());
        assertEquals(CoordinationLimits.MAX_CLAIMS,
                candidate.path("properties").path("claims").path("maxItems").asInt());
        assertEquals(CoordinationLimits.MAX_CLAIM_TEXT_LENGTH,
                candidate.path("$defs").path("claim").path("properties")
                        .path("statement").path("maxLength").asInt());
    }

    private static Fixtures fixtures() {
        AnalysisIdentity identity = new AnalysisIdentity(
                new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
        ActionTarget target = new ActionTarget(
                "workspace-1", identity.projectId(), identity.caseId(), identity.analysisId(),
                Optional.empty(), Optional.empty(), Optional.empty());
        AnalysisActionRequest.Command<Map<String, String>> request = new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST, AnalysisActionType.CASE_INSPECT,
                identity, target, Optional.empty(), Map.of("view", "summary"));
        ActionDecision decision = new ActionDecision(
                SchemaVersions.ACTION_DECISION, CoordinationPolicyVersions.CURRENT, identity, 4,
                AnalysisActionType.CASE_INSPECT, ActionDecisionCode.ALLOWED,
                ActionSideEffect.READ_ONLY, List.of());
        EvidenceObligation obligation = new EvidenceObligation(
                SchemaVersions.EVIDENCE_OBLIGATION, "obligation-1", identity,
                EvidenceObligationKind.SYSTEM_PREREQUISITE, ObligationStatus.SATISFIED,
                "Analysis identity has been archived.", List.of("analysis-1"), List.of());
        AnalysisControlView control = new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT, identity, 4,
                AnalysisActionType.CASE_INSPECT, ActionDecisionCode.ALLOWED, List.of(),
                List.of("obligation-1"), List.of(), List.of(), List.of("gap-1"),
                List.of("hypothesis-1"), List.of(), List.of("predicate-1"),
                List.of(AnalysisActionType.SOURCE_QUERY, AnalysisActionType.ANALYSIS_FINALIZE),
                ConclusionStatus.BOUNDED_HYPOTHESIS);
        CoordinatedToolResult<Map<String, String>> result = new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT, ActionOutcome.SUCCEEDED,
                "CASE_INSPECT_COMPLETED", "Case inspection completed",
                Map.of("caseId", "case-1"), List.of(), control);
        OperationReceipt receipt = new OperationReceipt(
                SchemaVersions.OPERATION_RECEIPT, new OperationId("operation-1"), identity,
                AnalysisActionType.CASE_INSPECT, ActionOutcome.SUCCEEDED,
                "b".repeat(CoordinationLimits.SHA256_HEX_LENGTH), false,
                Optional.of("artifact-1"), Optional.empty());
        ConclusionClaim claim = new ConclusionClaim(
                "claim-1", "The runtime evidence supports the selected branch.",
                ClaimClassification.VALIDATOR_CONCLUSION,
                List.of(new EvidenceId("evidence-1")), List.of(), List.of());
        ConclusionCandidate candidate = new ConclusionCandidate(
                SchemaVersions.CONCLUSION_CANDIDATE, "conclusion-1", identity, 4,
                ConclusionStatus.BOUNDED_HYPOTHESIS, List.of(claim),
                List.of("chain-1"), List.of("gap-1"));
        ConclusionDecision conclusionDecision = new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION, CoordinationPolicyVersions.CURRENT,
                "conclusion-1", identity, 4,
                ActionDecisionCode.REJECTED, ConclusionStatus.BOUNDED_HYPOTHESIS,
                List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE),
                List.of("gap-1"));
        return new Fixtures(request, control, decision, obligation, result, receipt,
                candidate, conclusionDecision);
    }

    private static <T> T roundTrip(T value, Class<T> type) throws Exception {
        return MAPPER.readValue(MAPPER.writeValueAsBytes(value), type);
    }

    private static void assertValid(Path schemaPath, String json) throws Exception {
        assertTrue(validationErrors(schemaPath, json).isEmpty(),
                () -> "Schema validation failed for " + schemaPath + ": "
                        + validationErrorsUnchecked(schemaPath, json));
    }

    private static void assertInvalid(Path schemaPath, String json) throws Exception {
        assertFalse(validationErrors(schemaPath, json).isEmpty(),
                () -> "Schema unexpectedly accepted JSON for " + schemaPath);
    }

    private static java.util.List<com.networknt.schema.Error> validationErrors(
            Path schemaPath, String json) throws Exception {
        String schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaJson, InputFormat.JSON);
        return schema.validate(json, InputFormat.JSON);
    }

    private static Object validationErrorsUnchecked(Path schemaPath, String json) {
        try {
            return validationErrors(schemaPath, json);
        } catch (Exception failure) {
            return failure;
        }
    }

    private static Path schemaPath(String directory, String fileName) {
        String reactorRoot = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return Path.of(reactorRoot, "schemas", directory, fileName);
    }

    private static Set<String> enumValues(JsonNode values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> actionTypes() {
        return java.util.Arrays.stream(AnalysisActionType.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> coordinationErrorCodes() {
        return java.util.Arrays.stream(CoordinationErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Fixtures(
            AnalysisActionRequest.Command<Map<String, String>> request,
            AnalysisControlView control,
            ActionDecision decision,
            EvidenceObligation obligation,
            CoordinatedToolResult<Map<String, String>> result,
            OperationReceipt receipt,
            ConclusionCandidate candidate,
            ConclusionDecision conclusionDecision) {
    }
}
