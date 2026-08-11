package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResponseTest {

    @Test
    void successShouldDefensivelyCopyCollections() {
        List<String> actions = new ArrayList<>(List.of("NORMALIZE_TRACE"));

        ToolResponse<String> response = ToolResponse.success(
                "trace-ready", List.of(), actions);
        actions.add("DELETE_HISTORY");

        assertTrue(response.success());
        assertEquals("trace-ready", response.data());
        assertEquals(List.of("NORMALIZE_TRACE"), response.nextAllowedActions());
        assertThrows(UnsupportedOperationException.class,
                () -> response.nextAllowedActions().add("MUTATE"));
    }

    @Test
    void failureShouldNotContainData() {
        ToolResponse<String> response = ToolResponse.failure(
                "COLLECTION_TIMEOUT", "采集超时", List.of(), List.of("RETRY_WITH_SMALLER_PLAN"));

        assertTrue(!response.success());
        assertNull(response.data());
        assertEquals("COLLECTION_TIMEOUT", response.code());
    }

    @Test
    void constructorShouldRejectContradictoryState() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE,
                true,
                "OK",
                "ok",
                null,
                List.of(),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE,
                false,
                "FAILED",
                "failed",
                "unexpected-data",
                List.of(),
                List.of()));
    }
}

