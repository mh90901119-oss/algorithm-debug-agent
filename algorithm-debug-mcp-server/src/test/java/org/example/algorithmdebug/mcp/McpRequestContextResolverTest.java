package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.modelcontextprotocol.spec.McpSchema.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpRequestContextResolverTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-context");
    private static final String WORKSPACE_ID = "workspace-context";

    @TempDir
    Path directory;

    private Path workspace;
    private Path project;
    private McpRequestContextResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(directory.resolve("workspace"));
        project = Files.createDirectory(directory.resolve("算法-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var repository = new ProjectRegistrationRepository(
                new BoundedDocumentMapper(), new AtomicDocumentWriter());
        repository.create(WorkspaceLayout.of(workspace), new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "Context Project",
                project.toString(), project.toString(), project.toString(), "pom.xml",
                "MAVEN", Instant.parse("2026-09-28T00:00:00Z")));
        resolver = new McpRequestContextResolver(workspace, project, WORKSPACE_ID);
    }

    @Test
    void rootsAndFrozenProjectResolveToTheSameCanonicalPath() {
        McpRequestContext context = resolver.resolve(
                Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY, "request-1"),
                List.of(new Root(project.toUri().toString(), "target")));

        assertEquals(PROJECT_ID, context.projectId());
        assertEquals(WORKSPACE_ID, context.workspaceId());
        assertEquals(McpRequestId.fromWire("request-1"), context.requestId());
        assertEquals(project.toAbsolutePath().normalize(), context.projectRoot());
    }

    @Test
    void mismatchedNonFileAndTraversalRootsAreRejected() {
        Map<String, Object> metadata =
                Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY, 7);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                metadata,
                List.of(new Root(directory.resolve("other").toUri().toString(), "other"))));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                metadata, List.of(new Root("https://example.invalid/project", "remote"))));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                metadata, List.of(new Root(
                        project.resolve("..").toUri().toString(), "traversal"))));
    }

    @Test
    void missingOrInvalidRequestCorrelationIsRejected() {
        List<Root> roots = List.of(new Root(project.toUri().toString(), "target"));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(Map.of(), roots));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY, true), roots));
    }

    @Test
    void additionalClientRootsDoNotBroadenTheFrozenProjectAuthority() {
        Path unrelated = directory;
        McpRequestContext context = resolver.resolve(
                Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY, "request-multi-root"),
                List.of(
                        new Root(unrelated.toUri().toString(), "unrelated"),
                        new Root(project.toUri().toString(), "target")));

        assertEquals(PROJECT_ID, context.projectId());
        assertEquals(project.toAbsolutePath().normalize(), context.projectRoot());
    }
}
