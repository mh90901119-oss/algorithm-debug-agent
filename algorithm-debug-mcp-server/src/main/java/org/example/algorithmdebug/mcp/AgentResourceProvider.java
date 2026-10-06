package org.example.algorithmdebug.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;

/** 提供有界、只读的 Agent 清单、能力、Case 摘要和 Analysis 状态资源。 */
public final class AgentResourceProvider {
    public static final int MAX_RESOURCE_BYTES = 256 * 1024;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String MANIFEST_URI = "ada://agent/manifest";
    private static final String CAPABILITIES_URI = "ada://agent/capabilities";
    private static final String DIGEST_TEMPLATE = "ada://cases/{caseId}/digest";
    private static final String STATUS_TEMPLATE =
            "ada://cases/{caseId}/analyses/{analysisId}/status";
    private static final String SAFE_ID = "([A-Za-z0-9][A-Za-z0-9._-]{0,127})";
    private static final Pattern DIGEST_URI = Pattern.compile(
            "^ada://cases/" + SAFE_ID + "/digest$");
    private static final Pattern STATUS_URI = Pattern.compile(
            "^ada://cases/" + SAFE_ID + "/analyses/" + SAFE_ID + "/status$");
    private static final Set<String> RESOURCE_URIS = Set.of(
            MANIFEST_URI, CAPABILITIES_URI, DIGEST_TEMPLATE, STATUS_TEMPLATE);

    private final McpToolCatalog catalog;
    private final Supplier<ProjectId> projectIds;
    private final Map<String, ?> capabilities;
    private final CaseDigestReader caseDigests;
    private final AnalysisStatusReader analysisStatuses;
    private final ObjectMapper mapper = McpJsonSupport.strictMapper();

    /** 创建仅依赖两个只读查询端口的资源提供器。 */
    public AgentResourceProvider(
            McpToolCatalog catalog,
            ProjectId projectId,
            Map<String, ?> capabilities,
            CaseDigestReader caseDigests,
            AnalysisStatusReader analysisStatuses) {
        this(catalog, () -> projectId, capabilities, caseDigests, analysisStatuses);
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
    }

    AgentResourceProvider(
            McpToolCatalog catalog,
            Supplier<ProjectId> projectIds,
            Map<String, ?> capabilities,
            CaseDigestReader caseDigests,
            AnalysisStatusReader analysisStatuses) {
        if (catalog == null || projectIds == null || capabilities == null
                || capabilities.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)
                || caseDigests == null || analysisStatuses == null) {
            throw new IllegalArgumentException("Resource provider dependencies are invalid");
        }
        this.catalog = catalog;
        this.projectIds = projectIds;
        this.capabilities = Map.copyOf(capabilities);
        this.caseDigests = caseDigests;
        this.analysisStatuses = analysisStatuses;
    }

    /** @return 两个稳定静态资源声明 */
    List<SyncResourceSpecification> resources() {
        return List.of(
                specification(MANIFEST_URI, "Algorithm Debug Agent manifest"),
                specification(CAPABILITIES_URI, "Frozen runtime capabilities"));
    }

    /** @return Case 摘要和 Analysis 状态两个 URI 模板 */
    List<SyncResourceTemplateSpecification> resourceTemplates() {
        return List.of(
                template(DIGEST_TEMPLATE, "Case digest"),
                template(STATUS_TEMPLATE, "Analysis control status"));
    }

    /** 按稳定 URI 执行一次只读、有界查询。 */
    ReadResourceResult read(String uri) {
        if (uri == null) {
            throw invalidResource();
        }
        try {
            Object value;
            if (MANIFEST_URI.equals(uri)) {
                value = manifest();
            } else if (CAPABILITIES_URI.equals(uri)) {
                value = capabilities;
            } else {
                Matcher digest = DIGEST_URI.matcher(uri);
                Matcher status = STATUS_URI.matcher(uri);
                if (digest.matches()) {
                    value = caseDigests.read(new CaseId(digest.group(1)));
                } else if (status.matches()) {
                    value = analysisStatuses.read(new AnalysisIdentity(
                            requireProjectId(), new CaseId(status.group(1)),
                            new AnalysisId(status.group(2))));
                } else {
                    throw invalidResource();
                }
            }
            String json = mapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESOURCE_BYTES) {
                throw protocolError(INVALID_PARAMS, "Resource exceeds its inline budget");
            }
            return new ReadResourceResult(List.of(
                    new TextResourceContents(uri, JSON_MEDIA_TYPE, json)));
        } catch (McpError failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalidResource();
        } catch (JsonProcessingException failure) {
            throw protocolError(INTERNAL_ERROR, "Resource serialization failed");
        } catch (RuntimeException failure) {
            throw protocolError(INTERNAL_ERROR, "Resource read failed");
        }
    }

    private Map<String, Object> manifest() {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("serverName", AlgorithmDebugMcpServer.SERVER_NAME);
        manifest.put("serverVersion", AlgorithmDebugMcpServer.SERVER_VERSION);
        manifest.put("tools", catalog.descriptors().stream()
                .map(descriptor -> Map.of(
                        "name", descriptor.name(),
                        "actionType", descriptor.actionType().name(),
                        "description", descriptor.description()))
                .toList());
        manifest.put("resources", RESOURCE_URIS.stream().sorted().toList());
        manifest.put("prompts", AgentPromptProvider.PROMPT_NAMES.stream().sorted().toList());
        return java.util.Collections.unmodifiableMap(manifest);
    }

    private ProjectId requireProjectId() {
        ProjectId projectId = projectIds.get();
        if (projectId == null) {
            throw new IllegalStateException("Project registration is unavailable");
        }
        return projectId;
    }

    private SyncResourceSpecification specification(String uri, String description) {
        Resource resource = Resource.builder(uri, uri)
                .description(description)
                .mimeType(JSON_MEDIA_TYPE)
                .build();
        return new SyncResourceSpecification(
                resource, (exchange, request) -> read(request.uri()));
    }

    private SyncResourceTemplateSpecification template(String uri, String description) {
        ResourceTemplate resource = ResourceTemplate.builder(uri, uri)
                .description(description)
                .mimeType(JSON_MEDIA_TYPE)
                .build();
        return new SyncResourceTemplateSpecification(
                resource, (exchange, request) -> read(request.uri()));
    }

    private static McpError invalidResource() {
        return protocolError(INVALID_PARAMS, "Invalid resource URI");
    }

    private static McpError protocolError(int code, String message) {
        return McpError.builder(code).message(message).build();
    }

    /** Case 摘要只读端口。 */
    @FunctionalInterface
    public interface CaseDigestReader {
        Object read(CaseId caseId);
    }

    /** Analysis 控制状态只读端口。 */
    @FunctionalInterface
    public interface AnalysisStatusReader {
        Object read(AnalysisIdentity identity);
    }
}
