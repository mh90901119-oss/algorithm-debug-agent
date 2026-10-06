package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPromptProviderTest {

    @Test
    void exposesThreeStablePromptsThatRequireEvidenceBoundedReasoning() {
        AgentPromptProvider provider = new AgentPromptProvider();

        assertEquals(3, provider.prompts().size());
        String start = text(provider.get("algorithm-debug/start", Map.of(
                "problem", "scheduler assertion failed")));
        String continuation = text(provider.get("algorithm-debug/continue", Map.of(
                "caseId", "case-prompt", "analysisId", "analysis-prompt")));
        String explain = text(provider.get("algorithm-debug/explain-evidence", Map.of(
                "caseId", "case-prompt", "analysisId", "analysis-prompt")));

        assertTrue(start.contains("analysis_begin"));
        assertTrue(start.contains("MISSING_EVIDENCE"));
        assertTrue(continuation.contains("analysis_status"));
        assertTrue(continuation.contains("allowedActions"));
        assertTrue(explain.contains("CONFIRMED_FACT"));
        assertTrue(explain.contains("LLM_HYPOTHESIS"));
    }

    @Test
    void rejectsUnknownArgumentsAndOversizedPromptValues() {
        AgentPromptProvider provider = new AgentPromptProvider();

        assertThrows(McpError.class, () -> provider.get(
                "algorithm-debug/start", Map.of("unexpected", "value")));
        assertThrows(McpError.class, () -> provider.get(
                "algorithm-debug/start",
                Map.of("problem", "x".repeat(AgentPromptProvider.MAX_ARGUMENT_CHARS + 1))));
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.GetPromptResult result) {
        return ((TextContent) result.messages().getFirst().content()).text();
    }
}
