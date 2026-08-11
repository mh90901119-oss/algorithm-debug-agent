package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpaqueIdentifierJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void idShouldSerializeAsOpaqueStringAndRoundTrip() throws Exception {
        RunId runId = new RunId("RUN:case-01.20260810");

        String json = objectMapper.writeValueAsString(runId);

        assertEquals("\"RUN:case-01.20260810\"", json);
        assertEquals(runId, objectMapper.readValue(json, RunId.class));
    }

    @Test
    void idShouldRejectBlankSurroundingWhitespaceAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new CaseId(" "));
        assertThrows(IllegalArgumentException.class, () -> new CaseId(" CASE-1"));
        assertThrows(IllegalArgumentException.class, () -> new CaseId("CASE\n1"));
        assertThrows(IllegalArgumentException.class, () -> new CaseId("x".repeat(129)));
    }
}

