package org.example.algorithmdebug.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Root;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.core.coordination.ActionCancellation;

/** 所有 MCP tools/call 的唯一分派器；参数验证后只能调用一次共享 Coordinator。 */
public final class McpToolDispatcher {
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int SERVER_BUSY = -32000;

    private final McpToolCatalog catalog;
    private final McpRequestContextResolver contexts;
    private final McpServerLifecycle lifecycle;
    private final CoordinatorExecutor coordinator;
    private final McpResultMapper results;
    private final McpActionRequestFactory requests;
    private final ObjectMapper mapper;

    /** 创建只依赖窄 Coordinator 端口的工具分派器。 */
    public McpToolDispatcher(
            McpToolCatalog catalog,
            McpRequestContextResolver contexts,
            McpServerLifecycle lifecycle,
            CoordinatorExecutor coordinator,
            McpResultMapper results,
            Clock clock) {
        if (catalog == null || contexts == null || lifecycle == null
                || coordinator == null || results == null || clock == null) {
            throw new IllegalArgumentException("MCP dispatcher dependencies are invalid");
        }
        this.catalog = catalog;
        this.contexts = contexts;
        this.lifecycle = lifecycle;
        this.coordinator = coordinator;
        this.results = results;
        this.requests = new McpActionRequestFactory(clock);
        this.mapper = McpJsonSupport.strictMapper();
    }

    /** 供测试或已取得 roots 的宿主直接分派。 */
    public CallToolResult dispatch(List<Root> roots, CallToolRequest call) {
        return dispatch(() -> roots, call);
    }

    /** 生成 SDK tool specifications；roots 在每次请求内由客户端会话读取。 */
    List<SyncToolSpecification> specifications() {
        return catalog.descriptors().stream()
                .map(descriptor -> new SyncToolSpecification(
                        descriptor.toTool(),
                        (exchange, call) -> dispatch(
                                () -> exchange.listRoots().roots(), call)))
                .toList();
    }

    private CallToolResult dispatch(Supplier<List<Root>> roots, CallToolRequest call) {
        if (call == null) {
            throw invalidParams();
        }
        McpRequestId requestId;
        try {
            requestId = contexts.requestId(call.meta());
        } catch (IllegalArgumentException failure) {
            throw invalidParams();
        }
        ActionCancellation cancellation = ActionCancellation.active();
        final McpServerLifecycle.CallLease lease;
        try {
            lease = lifecycle.beginCall(requestId, cancellation);
        } catch (RejectedExecutionException failure) {
            throw protocolError(SERVER_BUSY, "MCP server is busy");
        }
        try (lease) {
            McpToolDescriptor descriptor = catalog.require(call.name());
            Map<String, Object> arguments = call.arguments() == null
                    ? Map.of() : call.arguments();
            var validation = McpJsonDefaults.getSchemaValidator()
                    .validate(descriptor.inputSchema(), arguments);
            if (!validation.valid()) {
                throw invalidParams();
            }
            McpRequestContext context = contexts.resolve(call.meta(), roots.get());
            Object input = mapper.convertValue(arguments, descriptor.inputType());
            AnalysisActionRequest<?> request = requests.create(descriptor, input, context);
            CoordinatedToolResult<?> result = coordinator.execute(request, cancellation);
            return results.map(result);
        } catch (McpError failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalidParams();
        } catch (RuntimeException failure) {
            throw protocolError(INTERNAL_ERROR, "Internal MCP server error");
        }
    }

    private static McpError invalidParams() {
        return protocolError(INVALID_PARAMS, "Invalid tool arguments");
    }

    private static McpError protocolError(int code, String message) {
        return McpError.builder(code).message(message).build();
    }

    /** Coordinator 的唯一窄执行端口，便于验证不存在工具旁路。 */
    @FunctionalInterface
    public interface CoordinatorExecutor {
        CoordinatedToolResult<?> execute(
                AnalysisActionRequest<?> request, ActionCancellation cancellation);
    }
}
