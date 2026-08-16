package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceConfigurationResolverTest {

    private static final ProjectId PROJECT_ID = new ProjectId("algorithm-module-123");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldApplyCliProjectWorkspaceDefaultPriorityAndMergeObjects() throws Exception {
        WorkspaceLayout layout = layout();
        writeWorkspaceConfiguration(layout, """
                schemaVersion: "1.0"
                sourceLabel: workspace
                nested:
                  workspaceOnly: true
                  replaced: workspace
                values: [workspace]
                """);
        writeProjectConfiguration(layout, """
                schemaVersion: "1.0"
                sourceLabel: project
                nested:
                  projectOnly: true
                  replaced: project
                values: [project]
                """);
        ObjectNode cli = JsonNodeFactory.instance.objectNode();
        cli.put("offline", true);
        cli.withObject("nested").put("cliOnly", true);

        ObjectNode resolved = (ObjectNode) resolver().resolve(
                layout, "application", Optional.of(PROJECT_ID), cli);

        assertTrue(resolved.path("offline").booleanValue());
        assertEquals("project", resolved.path("sourceLabel").textValue());
        assertTrue(resolved.path("nested").path("defaultOnly").booleanValue());
        assertTrue(resolved.path("nested").path("workspaceOnly").booleanValue());
        assertTrue(resolved.path("nested").path("projectOnly").booleanValue());
        assertTrue(resolved.path("nested").path("cliOnly").booleanValue());
        assertEquals("project", resolved.path("nested").path("replaced").textValue());
        assertEquals(1, resolved.path("values").size());
        assertEquals("project", resolved.path("values").get(0).textValue());
        assertFalse(cli.has("schemaVersion"));
    }

    @Test
    void shouldUseBuiltInConfigurationWhenOptionalLayersAreAbsent() throws Exception {
        ObjectNode resolved = (ObjectNode) resolver().resolve(
                layout(), "application", Optional.empty(), JsonNodeFactory.instance.objectNode());

        assertEquals("1.0", resolved.path("schemaVersion").textValue());
        assertEquals("default", resolved.path("sourceLabel").textValue());
        assertFalse(resolved.path("offline").booleanValue());
    }

    @Test
    void shouldRejectUnknownDocumentAndCliSchemaOverride() throws Exception {
        WorkspaceLayout layout = layout();
        ObjectNode schemaOverride = JsonNodeFactory.instance.objectNode().put("schemaVersion", "1.0");

        WorkspaceException unknown = assertThrows(
                WorkspaceException.class,
                () -> resolver().resolve(layout, "../application", Optional.empty(), emptyOverrides()));
        WorkspaceException cliSchema = assertThrows(
                WorkspaceException.class,
                () -> resolver().resolve(layout, "application", Optional.empty(), schemaOverride));

        assertEquals("CONFIG_INVALID", unknown.code());
        assertEquals("CONFIG_INVALID", cliSchema.code());
    }

    @Test
    void shouldRejectSchemaMismatchAndMalformedYaml() throws Exception {
        WorkspaceLayout layout = layout();
        writeWorkspaceConfiguration(layout, "schemaVersion: \"2.0\"\noffline: true\n");

        WorkspaceException mismatch = assertThrows(
                WorkspaceException.class,
                () -> resolver().resolve(layout, "application", Optional.empty(), emptyOverrides()));
        assertEquals("CONFIG_INVALID", mismatch.code());

        writeWorkspaceConfiguration(layout, "schemaVersion: [\n");
        WorkspaceException malformed = assertThrows(
                WorkspaceException.class,
                () -> resolver().resolve(layout, "application", Optional.empty(), emptyOverrides()));
        assertEquals("CONFIG_INVALID", malformed.code());
        assertTrue(malformed.getCause() != null);
    }

    @Test
    void shouldRejectWorkspaceConfigurationAboveOneMebibyte() throws Exception {
        WorkspaceLayout layout = layout();
        byte[] oversized = new byte[BoundedDocumentMapper.MAX_DOCUMENT_BYTES + 1];
        Files.write(layout.configRoot().resolve("application.yaml"), oversized);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> resolver().resolve(layout, "application", Optional.empty(), emptyOverrides()));

        assertEquals("CONFIG_INVALID", failure.code());
        assertTrue(failure.getCause() != null);
    }

    private WorkspaceLayout layout() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("workspace"));
        Files.createDirectories(layout.configRoot().resolve("projects").resolve(PROJECT_ID.value()));
        return layout;
    }

    private void writeWorkspaceConfiguration(WorkspaceLayout layout, String yaml) throws Exception {
        Files.writeString(layout.configRoot().resolve("application.yaml"), yaml, StandardCharsets.UTF_8);
    }

    private void writeProjectConfiguration(WorkspaceLayout layout, String yaml) throws Exception {
        Files.writeString(
                layout.configRoot().resolve("projects").resolve(PROJECT_ID.value()).resolve("application.yaml"),
                yaml,
                StandardCharsets.UTF_8);
    }

    private static WorkspaceConfigurationResolver resolver() {
        WorkspaceTemplateProvider defaults = () -> Map.of(
                Path.of("application.yaml"),
                """
                        schemaVersion: "1.0"
                        offline: false
                        sourceLabel: default
                        nested:
                          defaultOnly: true
                          replaced: default
                        values: [default]
                        """.getBytes(StandardCharsets.UTF_8));
        return new WorkspaceConfigurationResolver(new BoundedDocumentMapper(), defaults);
    }

    private static ObjectNode emptyOverrides() {
        return JsonNodeFactory.instance.objectNode();
    }
}
