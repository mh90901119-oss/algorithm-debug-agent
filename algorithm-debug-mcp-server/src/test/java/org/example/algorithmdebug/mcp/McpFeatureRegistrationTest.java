package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpFeatureRegistrationTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void configuredServerRegistersCanonicalToolResourceTemplateAndPromptCounts()
            throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        ProjectId projectId = new ProjectId("project-features");
        new ProjectRegistrationRepository(new BoundedDocumentMapper(),
                new AtomicDocumentWriter()).create(
                WorkspaceLayout.of(workspace), new ProjectRegistration(
                        SchemaVersions.PROJECT_REGISTRATION, projectId, "Feature Project",
                        project.toString(), project.toString(), project.toString(), "pom.xml",
                        "MAVEN", CLOCK.instant()));
        McpToolCatalog catalog = McpToolCatalog.load();
        McpRequestContextResolver contexts = new McpRequestContextResolver(
                workspace, project, "workspace-features");
        AgentResourceProvider resources = new AgentResourceProvider(
                catalog, projectId, Map.of(), ignored -> Map.of(), ignored -> Map.of());
        AgentPromptProvider prompts = new AgentPromptProvider();

        try (AlgorithmDebugMcpServer server = AlgorithmDebugMcpServer.open(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                () -> { }, workspace, project,
                lifecycle -> {
                    McpToolDispatcher dispatcher = new McpToolDispatcher(
                            catalog, contexts, lifecycle,
                            (request, cancellation) -> {
                                throw new AssertionError("list operations must not execute tools");
                            },
                            new McpResultMapper(), CLOCK);
                    return new AlgorithmDebugMcpServer.FeatureSet(
                            dispatcher.specifications(), resources.resources(),
                            resources.resourceTemplates(), prompts.prompts());
                })) {
            assertEquals(McpToolCatalog.expectedBaselineActions().size(),
                    server.sdkServer().listTools().size());
            assertEquals(2, server.sdkServer().listResources().size());
            assertEquals(2, server.sdkServer().listResourceTemplates().size());
            assertEquals(3, server.sdkServer().listPrompts().size());
        }
    }
}
