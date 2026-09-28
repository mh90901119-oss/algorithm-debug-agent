package org.example.algorithmdebug.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.mcp.input.AlgorithmInputCaptureInput;
import org.example.algorithmdebug.mcp.input.AnalysisBeginInput;
import org.example.algorithmdebug.mcp.input.AnalysisFinalizeInput;
import org.example.algorithmdebug.mcp.input.AnalysisStatusInput;
import org.example.algorithmdebug.mcp.input.ArtifactReadInput;
import org.example.algorithmdebug.mcp.input.CaseAuditInput;
import org.example.algorithmdebug.mcp.input.CaseInspectInput;
import org.example.algorithmdebug.mcp.input.CodePathCollectInput;
import org.example.algorithmdebug.mcp.input.CodePathPlanCreateInput;
import org.example.algorithmdebug.mcp.input.EvidenceQueryInput;
import org.example.algorithmdebug.mcp.input.GanttInspectInput;
import org.example.algorithmdebug.mcp.input.InvestigationUpdateInput;
import org.example.algorithmdebug.mcp.input.JdwpCollectInput;
import org.example.algorithmdebug.mcp.input.JdwpPlanCreateInput;
import org.example.algorithmdebug.mcp.input.RunTestInput;
import org.example.algorithmdebug.mcp.input.SourceQueryInput;
import org.example.algorithmdebug.mcp.input.StaticAnalyzeInput;

/** 17 个模型动作的唯一工具目录；名称、typed 输入和 Schema 不在 Handler 中重复维护。 */
public final class McpToolCatalog {
    private static final String TOOL_SCHEMA_PREFIX = "schemas/mcp/tools/";
    private static final String TOOL_SCHEMA_SUFFIX = "-v1.schema.json";
    private static final String OUTPUT_SCHEMA =
            "schemas/tool/coordinated-tool-result-v1.schema.json";
    private static final String EVIDENCE_QUERY_SCHEMA =
            "schemas/tool/evidence-query-result-v2.schema.json";
    private static final String CONCLUSION_CANDIDATE_SCHEMA =
            "schemas/coordination/conclusion-candidate-v2.schema.json";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final List<McpToolDescriptor> descriptors;
    private final Map<String, McpToolDescriptor> byName;
    private final Set<AnalysisActionType> actionTypes;

    private McpToolCatalog(List<McpToolDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        LinkedHashMap<String, McpToolDescriptor> names = new LinkedHashMap<>();
        EnumSet<AnalysisActionType> actions = EnumSet.noneOf(AnalysisActionType.class);
        LinkedHashSet<Class<?>> inputs = new LinkedHashSet<>();
        for (McpToolDescriptor descriptor : descriptors) {
            if (names.putIfAbsent(descriptor.name(), descriptor) != null
                    || !actions.add(descriptor.actionType())
                    || !inputs.add(descriptor.inputType())) {
                throw new IllegalStateException("MCP tool catalog contains duplicate bindings");
            }
        }
        if (!actions.equals(expectedBaselineActions())) {
            throw new IllegalStateException("MCP tool catalog does not cover every action");
        }
        this.byName = Map.copyOf(names);
        this.actionTypes = Set.copyOf(actions);
    }

    /** 从 JAR 内由根目录 Schema 构建阶段复制的资源加载目录。 */
    public static McpToolCatalog load() {
        ObjectMapper mapper = strictMapper();
        Map<String, Object> output = readSchema(mapper, OUTPUT_SCHEMA);
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        descriptors.add(descriptor(mapper, output, "analysis_begin",
                AnalysisActionType.ANALYSIS_BEGIN, AnalysisBeginInput.class,
                "Create one analysis with a complete problem frame."));
        descriptors.add(descriptor(mapper, output, "case_inspect",
                AnalysisActionType.CASE_INSPECT, CaseInspectInput.class,
                "Read the bounded case digest for an analysis."));
        descriptors.add(descriptor(mapper, output, "algorithm_input_capture",
                AnalysisActionType.ALGORITHM_INPUT_CAPTURE, AlgorithmInputCaptureInput.class,
                "Capture the target test algorithm input through the coordinator."));
        descriptors.add(descriptor(mapper, output, "case_audit",
                AnalysisActionType.CASE_AUDIT, CaseAuditInput.class,
                "Audit archived case artifacts and provenance."));
        descriptors.add(descriptor(mapper, output, "gantt_inspect",
                AnalysisActionType.GANTT_INSPECT, GanttInspectInput.class,
                "Read a bounded projection of a registered Gantt artifact."));
        descriptors.add(descriptor(mapper, output, "run_test",
                AnalysisActionType.RUN_TEST, RunTestInput.class,
                "Run the target JUnit test and archive its structured outcome."));
        descriptors.add(descriptor(mapper, output, "static_analyze",
                AnalysisActionType.STATIC_ANALYZE, StaticAnalyzeInput.class,
                "Build the bounded deterministic source method catalog."));
        descriptors.add(descriptor(mapper, output, "source_query",
                AnalysisActionType.SOURCE_QUERY, SourceQueryInput.class,
                "Query methods, callers, callees, paths or bounded source windows."));
        descriptors.add(descriptor(mapper, output, "investigation_update",
                AnalysisActionType.INVESTIGATION_UPDATE, InvestigationUpdateInput.class,
                "Append one typed hypothesis, evidence gap or observation predicate update."));
        descriptors.add(descriptor(mapper, output, "codepath_plan_create",
                AnalysisActionType.CODEPATH_PLAN_CREATE, CodePathPlanCreateInput.class,
                "Compile and archive one typed CodePath collection plan."));
        descriptors.add(descriptor(mapper, output, "codepath_collect",
                AnalysisActionType.CODEPATH_COLLECT, CodePathCollectInput.class,
                "Execute one archived CodePath plan."));
        descriptors.add(descriptor(mapper, output, "jdwp_plan_create",
                AnalysisActionType.JDWP_PLAN_CREATE, JdwpPlanCreateInput.class,
                "Compile and archive one typed JDWP collection plan."));
        descriptors.add(descriptor(mapper, output, "jdwp_collect",
                AnalysisActionType.JDWP_COLLECT, JdwpCollectInput.class,
                "Execute one archived JDWP plan."));
        descriptors.add(descriptor(mapper, output, "artifact_read",
                AnalysisActionType.ARTIFACT_READ, ArtifactReadInput.class,
                "Read a bounded byte range from a registered artifact."));
        descriptors.add(descriptor(mapper, output, "evidence_query",
                AnalysisActionType.EVIDENCE_QUERY, EvidenceQueryInput.class,
                "Query normalized dynamic evidence with explicit budgets."));
        descriptors.add(descriptor(mapper, output, "analysis_status",
                AnalysisActionType.ANALYSIS_STATUS, AnalysisStatusInput.class,
                "Read the deterministic analysis control state and allowed next actions."));
        descriptors.add(descriptor(mapper, output, "analysis_finalize",
                AnalysisActionType.ANALYSIS_FINALIZE, AnalysisFinalizeInput.class,
                "Submit a typed conclusion candidate to the deterministic conclusion gate."));
        return new McpToolCatalog(descriptors);
    }

    /** @return 声明顺序稳定且不可变的完整目录 */
    public List<McpToolDescriptor> descriptors() {
        return descriptors;
    }

    /** @return 目录覆盖的不可变 Action 集合 */
    public Set<AnalysisActionType> actionTypes() {
        return actionTypes;
    }

    /** @return 当前代码版本必须完整覆盖的 Action 基线 */
    public static Set<AnalysisActionType> expectedBaselineActions() {
        return Set.copyOf(EnumSet.allOf(AnalysisActionType.class));
    }

    /** 按标准工具名查找绑定，未知名称确定性失败。 */
    public McpToolDescriptor require(String name) {
        McpToolDescriptor descriptor = name == null ? null : byName.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown MCP tool");
        }
        return descriptor;
    }

    /** 校验 Runtime Coordinator 与外部目录覆盖同一组 Action。 */
    public void requireCompatible(List<AnalysisActionType> registeredActions) {
        if (registeredActions == null
                || !Set.copyOf(registeredActions).equals(actionTypes)
                || registeredActions.size() != actionTypes.size()) {
            throw new IllegalStateException("Coordinator action registry does not match MCP catalog");
        }
    }

    private static McpToolDescriptor descriptor(
            ObjectMapper mapper,
            Map<String, Object> output,
            String name,
            AnalysisActionType actionType,
            Class<?> inputType,
            String description) {
        Map<String, Object> input = readSchema(mapper, TOOL_SCHEMA_PREFIX
                + name.replace('_', '-') + TOOL_SCHEMA_SUFFIX);
        if (actionType == AnalysisActionType.EVIDENCE_QUERY) {
            input = composeNestedSchema(
                    input, readSchema(mapper, EVIDENCE_QUERY_SCHEMA),
                    "request", true);
        } else if (actionType == AnalysisActionType.ANALYSIS_FINALIZE) {
            input = composeNestedSchema(
                    input, readSchema(mapper, CONCLUSION_CANDIDATE_SCHEMA),
                    "candidate", false);
        }
        return new McpToolDescriptor(
                name, actionType, description, inputType, input, output);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeNestedSchema(
            Map<String, Object> toolSchema,
            Map<String, Object> domainSchema,
            String field,
            boolean useRequestDefinition) {
        LinkedHashMap<String, Object> composed = new LinkedHashMap<>(toolSchema);
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>(
                (Map<String, Object>) toolSchema.get("properties"));
        Map<String, Object> definitions = (Map<String, Object>) domainSchema.get("$defs");
        Map<String, Object> nested;
        if (useRequestDefinition) {
            nested = (Map<String, Object>) definitions.get("request");
        } else {
            LinkedHashMap<String, Object> candidate = new LinkedHashMap<>(domainSchema);
            candidate.keySet().removeAll(Set.of("$schema", "$id", "title", "$defs"));
            nested = candidate;
        }
        if (nested == null || definitions == null) {
            throw new IllegalStateException("Domain schema cannot be composed into MCP input");
        }
        properties.put(field, nested);
        composed.put("properties", properties);
        composed.put("$defs", definitions);
        return immutableMap(composed);
    }

    private static Map<String, Object> readSchema(ObjectMapper mapper, String resource) {
        try (InputStream input = McpToolCatalog.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing MCP schema resource: " + resource);
            }
            byte[] bytes = input.readNBytes(McpServerLimits.MAX_SCHEMA_BYTES + 1);
            if (bytes.length > McpServerLimits.MAX_SCHEMA_BYTES) {
                throw new IllegalStateException("MCP schema resource exceeds its budget");
            }
            return immutableMap(mapper.readValue(bytes, MAP_TYPE));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load MCP schema resource", failure);
        }
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return (Map<String, Object>) immutableJson(value);
    }

    private static Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableJson(nested)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpToolCatalog::immutableJson).toList();
        }
        return value;
    }
}
