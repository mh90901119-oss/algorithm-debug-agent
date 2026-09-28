package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpSdkJacksonCompatibilityTest {
    private static final Map<String, Object> TOOL_SCHEMA = Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "type", "object",
            "properties", Map.of("symbol", Map.of("type", "string")),
            "required", List.of("symbol"),
            "additionalProperties", false);

    @Test
    void sdkJacksonBaselineValidatesToolSchemasAndStructuredContent() {
        JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();

        validator.assertConforms("compatibility tool", TOOL_SCHEMA);
        assertTrue(validator.validate(TOOL_SCHEMA, Map.of("symbol", "Scheduler"))
                .valid());
        assertFalse(validator.validate(TOOL_SCHEMA, Map.of("unexpected", true))
                .valid());
        assertThrows(IllegalArgumentException.class, () -> validator.assertConforms(
                "invalid compatibility tool", Map.of("type", 17)));
    }
}
