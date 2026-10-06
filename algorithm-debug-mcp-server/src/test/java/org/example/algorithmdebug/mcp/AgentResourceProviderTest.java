package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.junit.jupiter.api.Test;

class AgentResourceProviderTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-resource");

    @Test
    void exposesTwoStaticResourcesAndTwoBoundedReadOnlyTemplates() throws Exception {
        AtomicReference<CaseId> digestCase = new AtomicReference<>();
        AtomicReference<AnalysisIdentity> statusIdentity = new AtomicReference<>();
        AgentResourceProvider provider = new AgentResourceProvider(
                McpToolCatalog.load(), PROJECT_ID, Map.of("jdwp", "AVAILABLE"),
                caseId -> {
                    digestCase.set(caseId);
                    return Map.of("caseId", caseId.value(), "kind", "digest");
                },
                identity -> {
                    statusIdentity.set(identity);
                    return Map.of("analysisId", identity.analysisId().value(), "kind", "status");
                });

        assertEquals(2, provider.resources().size());
        assertEquals(2, provider.resourceTemplates().size());
        assertEquals("ada://agent/manifest", provider.resources().getFirst().resource().uri());

        String manifest = text(provider.read("ada://agent/manifest"));
        JsonNode manifestJson = new ObjectMapper().readTree(manifest);
        assertEquals(McpToolCatalog.expectedBaselineActions().size(),
                manifestJson.path("tools").size());

        String digest = text(provider.read("ada://cases/case-resource/digest"));
        String status = text(provider.read(
                "ada://cases/case-resource/analyses/analysis-resource/status"));
        assertEquals(new CaseId("case-resource"), digestCase.get());
        assertEquals(new AnalysisIdentity(
                PROJECT_ID, new CaseId("case-resource"),
                new AnalysisId("analysis-resource")), statusIdentity.get());
        assertTrue(digest.contains("\"kind\":\"digest\""));
        assertTrue(status.contains("\"kind\":\"status\""));
        assertTrue(status.getBytes(StandardCharsets.UTF_8).length
                <= AgentResourceProvider.MAX_RESOURCE_BYTES);
    }

    @Test
    void rejectsUnknownTraversalAndOversizedResourcesWithoutLeakingReaderFailure() {
        AgentResourceProvider provider = new AgentResourceProvider(
                McpToolCatalog.load(), PROJECT_ID, Map.of(),
                ignored -> Map.of("data", "x".repeat(AgentResourceProvider.MAX_RESOURCE_BYTES)),
                ignored -> { throw new IllegalStateException("D:\\secret\\status"); });

        assertThrows(McpError.class,
                () -> provider.read("ada://cases/../digest"));
        assertThrows(McpError.class,
                () -> provider.read("ada://cases/case-resource/digest"));
        McpError failure = assertThrows(McpError.class, () -> provider.read(
                "ada://cases/case-resource/analyses/analysis-resource/status"));
        assertTrue(!failure.toString().contains("secret"));
    }

    @Test
    void staticManifestAndCapabilitiesDoNotRequireProjectRegistration() {
        AgentResourceProvider provider = new AgentResourceProvider(
                McpToolCatalog.load(),
                () -> { throw new IllegalStateException("project is not registered"); },
                Map.of("codepath", "UNAVAILABLE"),
                ignored -> { throw new AssertionError("case reader must stay lazy"); },
                ignored -> { throw new AssertionError("status reader must stay lazy"); });

        assertTrue(text(provider.read("ada://agent/manifest")).contains("analysis_begin"));
        assertTrue(text(provider.read("ada://agent/capabilities")).contains("UNAVAILABLE"));
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.ReadResourceResult result) {
        return ((io.modelcontextprotocol.spec.McpSchema.TextResourceContents)
                result.contents().getFirst()).text();
    }
}
