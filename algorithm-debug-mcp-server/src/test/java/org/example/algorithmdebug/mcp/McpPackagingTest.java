package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;

/** MCP 可执行包资源、入口类与脱敏边界的回归测试。 */
class McpPackagingTest {
    private static final String MAIN_CLASS =
            "org.example.algorithmdebug.mcp.AlgorithmDebugMcpMain";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String RESOURCE_ROOT = "agent-definition/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> AGENT_RESOURCES = List.of(
            RESOURCE_ROOT + "algorithm-debug-agent-v1.json",
            RESOURCE_ROOT + "capability-manifest-v1.json",
            RESOURCE_ROOT + "system-prompt-v1.md",
            RESOURCE_ROOT + "completion-contract-v1.schema.json",
            "schemas/agent/algorithm-debug-agent-v1.schema.json",
            "schemas/agent/capability-manifest-v1.schema.json",
            "schemas/config/mcp-agent-settings-v1.schema.json",
            "META-INF/THIRD_PARTY_NOTICES.md");
    private static final List<String> FORBIDDEN_TEXT = List.of(
            "c:\\users\\", "d:\\javacode\\", "api_key", "access_token",
            "-----begin private key-----");

    @Test
    void packagedJarContainsAllSchemasPromptAndServerMainClass() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(loader.getResource(
                MAIN_CLASS.replace('.', '/') + ".class"));
        for (String resource : allExpectedResources()) {
            assertNotNull(loader.getResource(resource), resource);
        }
        try (InputStream stream = loader.getResourceAsStream(
                RESOURCE_ROOT + "algorithm-debug-agent-v1.json")) {
            JsonNode definition = MAPPER.readTree(stream);
            assertEquals("algorithm-debug", definition.path("agentId").asText());
            List<String> declaredTools = new ArrayList<>();
            definition.path("allowedToolGroups").forEach(group ->
                    group.path("toolNames").forEach(tool -> declaredTools.add(tool.asText())));
            List<String> catalogTools = McpToolCatalog.load().descriptors().stream()
                    .map(McpToolDescriptor::name)
                    .toList();
            assertEquals(catalogTools, declaredTools);
            try (InputStream capabilities = loader.getResourceAsStream(
                    RESOURCE_ROOT + "capability-manifest-v1.json")) {
                JsonNode manifest = new ObjectMapper().readTree(capabilities);
                List<String> manifestTools = new ArrayList<>();
                manifest.path("tools").forEach(tool -> manifestTools.add(tool.asText()));
                assertEquals(catalogTools, manifestTools);

                AgentResourceProvider resourceProvider = new AgentResourceProvider(
                        McpToolCatalog.load(), new ProjectId("project-packaging"), Map.of(),
                        ignored -> Map.of(), ignored -> Map.of());
                List<String> catalogResources = Stream.concat(
                                resourceProvider.resources().stream()
                                        .map(specification -> specification.resource().uri()),
                                resourceProvider.resourceTemplates().stream()
                                        .map(specification -> specification.resourceTemplate()
                                                .uriTemplate()))
                        .toList();
                List<String> manifestResources = new ArrayList<>();
                manifest.path("resources").forEach(
                        resource -> manifestResources.add(resource.asText()));
                assertEquals(catalogResources, manifestResources);

                List<String> catalogPrompts = new AgentPromptProvider().prompts().stream()
                        .map(specification -> specification.prompt().name())
                        .toList();
                List<String> manifestPrompts = new ArrayList<>();
                manifest.path("prompts").forEach(prompt -> manifestPrompts.add(prompt.asText()));
                assertEquals(catalogPrompts, manifestPrompts);
            }
        }
        Path classes = Path.of(AlgorithmDebugMcpMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        try (InputStream stream = Files.newInputStream(classes.resolve("META-INF/MANIFEST.MF"))) {
            assertNotNull(stream);
            Manifest manifest = new Manifest(stream);
            assertEquals(MAIN_CLASS,
                    manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        }
    }

    @Test
    void packageContainsNoCredentialsOrAbsoluteDeveloperPaths() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String resource : allExpectedResources()) {
            try (InputStream stream = loader.getResourceAsStream(resource)) {
                assertNotNull(stream, resource);
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_TEXT) {
                    assertTrue(!text.contains(forbidden),
                            () -> resource + " contains forbidden package text: " + forbidden);
                }
            }
        }
    }

    @Test
    void canonicalSchemasValidatePackagedDocuments() throws Exception {
        var validator = McpJsonDefaults.getSchemaValidator();
        Map<String, Object> definitionSchema = resourceMap(
                "schemas/agent/algorithm-debug-agent-v1.schema.json");
        Map<String, Object> capabilitySchema = resourceMap(
                "schemas/agent/capability-manifest-v1.schema.json");
        Map<String, Object> completionSchema = resourceMap(
                RESOURCE_ROOT + "completion-contract-v1.schema.json");
        Map<String, Object> settingsSchema = resourceMap(
                "schemas/config/mcp-agent-settings-v1.schema.json");
        Map<String, Object> definition = resourceMap(
                RESOURCE_ROOT + "algorithm-debug-agent-v1.json");
        Map<String, Object> capabilities = resourceMap(
                RESOURCE_ROOT + "capability-manifest-v1.json");
        Map<String, Object> settings = MAPPER.readValue(
                locateRepositoryRoot().resolve("config/mcp-agent-settings.json").toFile(),
                MAP_TYPE);
        Map<String, Object> adapterSchema = MAPPER.readValue(
                locateRepositoryRoot().resolve(
                        "integrations/host-adapter-kit/schemas/host-adapter-manifest-v1.schema.json")
                        .toFile(), MAP_TYPE);
        Map<String, Object> adapterManifest = MAPPER.readValue(
                locateRepositoryRoot().resolve("integrations/qwen-cli/adapter-manifest.json")
                        .toFile(), MAP_TYPE);

        validator.assertConforms("Agent Definition schema", definitionSchema);
        validator.assertConforms("Capability Manifest schema", capabilitySchema);
        validator.assertConforms("Completion Contract schema", completionSchema);
        validator.assertConforms("MCP settings schema", settingsSchema);
        validator.assertConforms("Host Adapter schema", adapterSchema);
        assertTrue(validator.validate(definitionSchema, definition).valid());
        assertTrue(validator.validate(capabilitySchema, capabilities).valid());
        assertTrue(validator.validate(settingsSchema, settings).valid());
        assertTrue(validator.validate(adapterSchema, adapterManifest).valid());

        Map<String, Object> missingHash = new java.util.LinkedHashMap<>(definition);
        missingHash.remove("prompt");
        assertFalse(validator.validate(definitionSchema, missingHash).valid());

        ObjectNode unsafeDefinition = MAPPER.valueToTree(definition);
        ((ObjectNode) unsafeDefinition.path("prompt")).put("path", "../outside.md");
        assertFalse(validator.validate(
                definitionSchema, MAPPER.convertValue(unsafeDefinition, MAP_TYPE)).valid());
    }

    private static List<String> allExpectedResources() throws IOException {
        List<String> resources = new ArrayList<>(AGENT_RESOURCES);
        Path repository = locateRepositoryRoot();
        try (var schemas = Files.list(repository.resolve("schemas/mcp/tools"))) {
            schemas.filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted()
                    .map(path -> "schemas/mcp/tools/" + path.getFileName())
                    .forEach(resources::add);
        }
        return List.copyOf(resources);
    }

    private static Path locateRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("schemas/mcp/tools"))
                    && Files.isDirectory(candidate.resolve("algorithm-debug-mcp-server"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root could not be located");
    }

    private static Map<String, Object> resourceMap(String resource) throws IOException {
        try (InputStream stream = McpPackagingTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return MAPPER.readValue(stream, MAP_TYPE);
        }
    }
}
