package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResponseTest {

    @Test
    void successShouldUseVersionTwoWithoutFixedActionStateMachine() {
        ToolResponse<String> response = ToolResponse.success("trace-ready", List.of());

        assertTrue(response.success());
        assertEquals("trace-ready", response.data());
        assertEquals("2.0", response.schemaVersion());
    }

    @Test
    void failureShouldNotContainData() {
        ToolResponse<String> response = ToolResponse.failure(
                "COLLECTION_TIMEOUT", "collection timed out", List.of());

        assertTrue(!response.success());
        assertNull(response.data());
        assertEquals("COLLECTION_TIMEOUT", response.code());
    }

    @Test
    void constructorShouldRejectContradictoryState() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE, true, "OK", "ok", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE, false, "FAILED", "failed", "unexpected-data", List.of()));
    }
}
