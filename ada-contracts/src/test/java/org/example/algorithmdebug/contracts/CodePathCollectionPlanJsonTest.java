package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CodePathCollectionPlanJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final String HASH = "a".repeat(64);

    @Test
    void roundTripsPlanAndKeepsTopLevelSchemaFieldsAligned() throws Exception {
        CodePathCollectionPlan plan = plan(CollectionBudget.defaults(), 100_000);

        byte[] json = MAPPER.writeValueAsBytes(plan);
        assertEquals(plan, MAPPER.readValue(json, CodePathCollectionPlan.class));

        JsonNode schema = schema();
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertEquals(Set.of(
                "schemaVersion", "planId", "caseId", "contextId", "analysisId",
                "targetTest", "sourceFingerprintSha256", "selectors", "packagePrefixes",
                "captureScope", "budget", "estimatedPackageEvents", "rationale", "createdAt"),
                required);
    }

    @Test
    void rejectsCodePathBudgetsBeyondDesignedHardLimits() {
        assertThrows(IllegalArgumentException.class, () ->
                new CollectionBudget(1, 50L * 1024 * 1024 + 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new CollectionBudget(1, 1, 20 * 60_000L + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> plan(
                CollectionBudget.defaults(), 1_000_001));
    }

    @Test
    void schemaUsesTheSameCodePathByteAndWallClockHardLimits() throws Exception {
        JsonNode budget = schema().path("$defs").path("budget").path("properties");

        assertEquals(50L * 1024 * 1024, budget.path("maxBytes").path("maximum").longValue());
        assertEquals(20 * 60_000L, budget.path("timeoutMillis").path("maximum").longValue());
    }

    @Test
    void planSchemaValidatesJvmDescriptorInstances() throws Exception {
        JsonNode selector = schema().path("$defs").path("selector");
        String expression = selector.path("properties")
                .path("descriptor").path("pattern").asText();
        Pattern descriptor = Pattern.compile(expression);

        assertFalse(expression.isBlank());
        assertEquals(true, descriptor.matcher("([[J)Ljava/lang/Object;").matches());
        assertFalse(descriptor.matcher("(long)V").matches());
        assertFalse(descriptor.matcher("(V)V").matches());
        assertEquals(true, schemaAllowsDescriptor(selector, "<init>", "()V"));
        assertFalse(schemaAllowsDescriptor(selector, "<init>", "()I"));
        assertEquals(true, schemaAllowsDescriptor(selector, "method", "()I"));
    }

    @Test
    void planSchemaAppliesDtoEquivalentTargetAndSelectorConstraintsToInstances() throws Exception {
        JsonNode definitions = schema().path("$defs");
        ObjectNode validTarget = MAPPER.valueToTree(
                new TargetTest("fixture.TargetTest", "caseUnderTest"));
        ObjectNode invalidTargetClass = validTarget.deepCopy();
        invalidTargetClass.put("className", "fixture..TargetTest");
        ObjectNode invalidTargetMethod = validTarget.deepCopy();
        invalidTargetMethod.put("methodName", "<init>");

        ObjectNode validSelector = MAPPER.valueToTree(new MethodSelector(
                "fixture.TargetTest#caseUnderTest()V", "fixture.TargetTest",
                "caseUnderTest", "()V", HASH));
        ObjectNode invalidMethodKey = validSelector.deepCopy();
        invalidMethodKey.put("methodKey", "not-a-method-key");
        ObjectNode invalidSelectorClass = validSelector.deepCopy();
        invalidSelectorClass.put("className", "TargetTest");
        ObjectNode invalidSelectorMethod = validSelector.deepCopy();
        invalidSelectorMethod.put("methodName", "bad-name");

        assertTrue(instanceMatches(definitions.path("targetTest"), validTarget));
        assertFalse(instanceMatches(definitions.path("targetTest"), invalidTargetClass));
        assertFalse(instanceMatches(definitions.path("targetTest"), invalidTargetMethod));
        assertTrue(instanceMatches(definitions.path("selector"), validSelector));
        assertFalse(instanceMatches(definitions.path("selector"), invalidMethodKey));
        assertFalse(instanceMatches(definitions.path("selector"), invalidSelectorClass));
        assertFalse(instanceMatches(definitions.path("selector"), invalidSelectorMethod));
    }

    @Test
    void planSchemaRejectsNonCanonicalPackagePrefixesAndRationaleInstances() throws Exception {
        JsonNode properties = schema().path("properties");
        JsonNode packagePrefix = properties.path("packagePrefixes").path("items");
        JsonNode rationale = properties.path("rationale");

        assertTrue(instanceMatches(packagePrefix, MAPPER.valueToTree("fixture.internal")));
        assertFalse(instanceMatches(packagePrefix, MAPPER.valueToTree("")));
        assertFalse(instanceMatches(packagePrefix, MAPPER.valueToTree("fixture..internal")));
        assertTrue(instanceMatches(rationale, MAPPER.valueToTree("valid internal rationale")));
        assertFalse(instanceMatches(rationale, MAPPER.valueToTree("   ")));
        assertFalse(instanceMatches(rationale, MAPPER.valueToTree(" leading whitespace")));
        assertFalse(instanceMatches(rationale, MAPPER.valueToTree("trailing whitespace ")));
    }

    private static boolean schemaAllowsDescriptor(
            JsonNode objectSchema, String methodName, String descriptor) {
        String generalExpression = objectSchema.path("properties")
                .path("descriptor").path("pattern").asText();
        if (generalExpression.isBlank()
                || !Pattern.compile(generalExpression).matcher(descriptor).matches()) {
            return false;
        }
        JsonNode constructorRule = objectSchema.path("allOf").path(0);
        String constructorName = constructorRule.path("if").path("properties")
                .path("methodName").path("const").asText();
        if (!methodName.equals(constructorName)) {
            return true;
        }
        String constructorDescriptorExpression = constructorRule.path("then").path("properties")
                .path("descriptor").path("pattern").asText();
        return !constructorDescriptorExpression.isBlank()
                && Pattern.compile(constructorDescriptorExpression).matcher(descriptor).matches();
    }

    private static boolean instanceMatches(JsonNode schema, JsonNode instance) {
        if (schema.has("const") && !schema.path("const").equals(instance)) {
            return false;
        }
        if ("string".equals(schema.path("type").asText())) {
            if (!instance.isTextual()) {
                return false;
            }
            String value = instance.asText();
            if (schema.has("minLength") && value.length() < schema.path("minLength").asInt()) {
                return false;
            }
            if (schema.has("maxLength") && value.length() > schema.path("maxLength").asInt()) {
                return false;
            }
            if (schema.has("pattern")
                    && !Pattern.compile(schema.path("pattern").asText()).matcher(value).find()) {
                return false;
            }
        }
        if ("object".equals(schema.path("type").asText()) || schema.has("properties")) {
            if (!instance.isObject()) {
                return false;
            }
            for (JsonNode required : schema.path("required")) {
                if (!instance.has(required.asText())) {
                    return false;
                }
            }
            var fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (instance.has(field.getKey())
                        && !instanceMatches(field.getValue(), instance.path(field.getKey()))) {
                    return false;
                }
            }
        }
        for (JsonNode rule : schema.path("allOf")) {
            if (rule.has("if")) {
                if (instanceMatches(rule.path("if"), instance)
                        && !instanceMatches(rule.path("then"), instance)) {
                    return false;
                }
            } else if (!instanceMatches(rule, instance)) {
                return false;
            }
        }
        return true;
    }

    private static CodePathCollectionPlan plan(CollectionBudget budget, long estimatedEvents) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN,
                new PlanId("plan-1"), new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.TargetTest", "caseUnderTest"),
                HASH,
                List.of(new MethodSelector(
                        "fixture.TargetTest#caseUnderTest()V", "fixture.TargetTest",
                        "caseUnderTest", "()V", HASH)),
                List.of("fixture"), "PACKAGE_SUPERSET", budget, estimatedEvents,
                "定位调用链", Instant.parse("2026-08-18T00:00:00Z"));
    }

    private static JsonNode schema() throws Exception {
        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return MAPPER.readTree(Path.of(
                root, "schemas", "collection", "codepath-plan-v1.schema.json").toFile());
    }
}
