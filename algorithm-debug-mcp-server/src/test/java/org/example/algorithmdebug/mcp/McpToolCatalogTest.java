package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.coordination.ActionInputLimits;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.SourceQueryLimits;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {

    @Test
    void catalogActionsEqualAllAnalysisActionTypes() {
        McpToolCatalog catalog = McpToolCatalog.load();

        assertEquals(EnumSet.allOf(AnalysisActionType.class), catalog.actionTypes());
        assertEquals(catalog.actionTypes(), McpToolCatalog.expectedBaselineActions());
        assertEquals(catalog.descriptors().size(),
                new HashSet<>(catalog.descriptors().stream()
                        .map(McpToolDescriptor::name).toList()).size());
    }

    @Test
    void toolNamesSchemasAndBindingsAreUniqueAndComplete() {
        McpToolCatalog catalog = McpToolCatalog.load();
        Set<Class<?>> inputTypes = new HashSet<>();

        for (McpToolDescriptor descriptor : catalog.descriptors()) {
            assertTrue(inputTypes.add(descriptor.inputType()));
            assertFalse(descriptor.description().isBlank());
            McpJsonDefaults.getSchemaValidator().assertConforms(
                    descriptor.name(), descriptor.inputSchema());
            assertThrows(UnsupportedOperationException.class,
                    () -> descriptor.inputSchema().put("unexpected", true));
        }
    }

    @Test
    void sourceQueryAndInvestigationUpdateExposeTypedSchemas() {
        McpToolCatalog catalog = McpToolCatalog.load();
        Map<String, Object> source = catalog.require("source_query").inputSchema();
        Map<String, Object> investigation =
                catalog.require("investigation_update").inputSchema();

        assertTrue(properties(source).containsKey("mode"));
        assertTrue(properties(source).containsKey("budget"));
        assertTrue(properties(investigation).containsKey("command"));
        assertFalse(properties(investigation).containsKey("action"));
    }

    @Test
    void analysisBeginRequiresProblemFrameAndSchemaLimitsMatchJavaConstants() {
        McpToolCatalog catalog = McpToolCatalog.load();
        Map<String, Object> begin = catalog.require("analysis_begin").inputSchema();
        Map<String, Object> source = catalog.require("source_query").inputSchema();

        assertTrue(required(begin).containsAll(Set.of(
                "symptom", "expectedBehavior", "actualBehavior", "targetTest",
                "scopeAnchors", "initialUnknowns")));
        assertEquals(InvestigationLimits.MAX_TEXT_LENGTH,
                number(properties(begin).get("symptom"), "maxLength"));
        Map<String, Object> budget = properties(properties(source).get("budget"));
        assertEquals(SourceQueryLimits.MAX_METHODS,
                number(budget.get("maxMethods"), "maximum"));
        assertEquals(SourceQueryLimits.MAX_RESPONSE_BYTES,
                number(budget.get("maxResponseBytes"), "maximum"));
        Map<String, Object> gantt = catalog.require("gantt_inspect").inputSchema();
        Map<String, Object> artifact = catalog.require("artifact_read").inputSchema();
        assertEquals(ActionInputLimits.MAX_GANTT_ROWS,
                number(properties(gantt).get("limit"), "maximum"));
        assertEquals(ActionInputLimits.MAX_ARTIFACT_ID_LENGTH,
                number(properties(gantt).get("artifactId"), "maxLength"));
        assertEquals(ActionInputLimits.MAX_GANTT_OPERATION_LENGTH,
                number(properties(gantt).get("operation"), "maxLength"));
        assertEquals(ActionInputLimits.MAX_JSON_POINTER_LENGTH,
                number(properties(gantt).get("jsonPointer"), "maxLength"));
        assertEquals(ActionInputLimits.MAX_ARTIFACT_READ_BYTES,
                number(properties(artifact).get("maxBytes"), "maximum"));
        assertEquals(ActionInputLimits.MAX_ADAPTER_ID_LENGTH,
                number(properties(begin).get("adapterId"), "maxLength"));
    }

    @Test
    void unknownFieldsOversizedPayloadsAndIllegalPathsAreRejected() {
        McpToolCatalog catalog = McpToolCatalog.load();
        var validator = McpJsonDefaults.getSchemaValidator();

        assertFalse(validator.validate(
                catalog.require("analysis_status").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "unexpected", true)).valid());
        assertFalse(validator.validate(
                catalog.require("source_query").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "mode", "REACHABLE_PATH")).valid());
        assertFalse(validator.validate(
                catalog.require("artifact_read").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "artifactId", "artifact-1", "offsetBytes", 0,
                        "maxBytes", ActionInputLimits.MAX_ARTIFACT_READ_BYTES + 1)).valid());
        assertFalse(validator.validate(
                catalog.require("jdwp_plan_create").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "request", Map.of("unexpected", true))).valid());
        assertFalse(validator.validate(
                catalog.require("evidence_query").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "artifactId", "artifact-1",
                        "request", Map.of("unexpected", true))).valid());
        assertFalse(validator.validate(
                catalog.require("analysis_finalize").inputSchema(),
                Map.of("caseId", "case-1", "analysisId", "analysis-1",
                        "candidate", Map.of("unexpected", true))).valid());
        Map<String, Object> traversalAnchor = Map.of(
                "className", "example.Algorithm", "methodName", "run",
                "descriptor", "()V", "sourceRelativePath", "../Secret.java",
                "startLine", 1, "endLine", 2);
        assertFalse(validator.validate(
                catalog.require("analysis_begin").inputSchema(),
                Map.of("symptom", "failed", "expectedBehavior", "pass",
                        "actualBehavior", "fail",
                        "targetTest", Map.of(
                                "className", "example.AlgorithmTest",
                                "methodName", "fails"),
                        "scopeAnchors", java.util.List.of(traversalAnchor),
                        "knownFactRefs", java.util.List.of(),
                        "initialUnknowns", java.util.List.of("root cause"))).valid());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Object schema) {
        return (Map<String, Object>) ((Map<String, Object>) schema).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> required(Map<String, Object> schema) {
        return Set.copyOf((java.util.List<String>) schema.get("required"));
    }

    @SuppressWarnings("unchecked")
    private static int number(Object schema, String field) {
        return ((Number) ((Map<String, Object>) schema).get(field)).intValue();
    }
}
