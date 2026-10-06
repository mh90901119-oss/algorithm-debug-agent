package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三个稳定 MCP Prompt；只提供工作流引导，不授予能力或替代确定性门禁。 */
public final class AgentPromptProvider {
    public static final int MAX_ARGUMENT_CHARS = 4_096;
    static final Set<String> PROMPT_NAMES = Set.of(
            "algorithm-debug/start",
            "algorithm-debug/continue",
            "algorithm-debug/explain-evidence");
    private static final int INVALID_PARAMS = -32602;
    private static final String START = "algorithm-debug/start";
    private static final String CONTINUE = "algorithm-debug/continue";
    private static final String EXPLAIN = "algorithm-debug/explain-evidence";

    /** @return 三个稳定 Prompt specification */
    List<SyncPromptSpecification> prompts() {
        return List.of(
                specification(START, "Start an evidence-constrained analysis",
                        List.of(argument("problem", "Problem statement", true))),
                specification(CONTINUE, "Continue from archived control state",
                        identityArguments()),
                specification(EXPLAIN, "Explain evidence without upgrading claims",
                        identityArguments()));
    }

    /** 严格校验参数并返回一条有界用户消息。 */
    GetPromptResult get(String name, Map<String, Object> arguments) {
        Map<String, Object> safe = arguments == null ? Map.of() : Map.copyOf(arguments);
        String text;
        if (START.equals(name)) {
            requireKeys(safe, Set.of("problem"));
            String problem = value(safe, "problem");
            text = "Problem: " + problem + "\n"
                    + "Call analysis_begin with a complete Problem Frame. Then read "
                    + "analysis_status and follow only allowedActions. Treat knowledge as hints, "
                    + "source relations as SOURCE_INFERENCE, and runtime observations as facts only "
                    + "when provenance and gates permit. If evidence is insufficient, return "
                    + "MISSING_EVIDENCE instead of forcing a cause.";
        } else if (CONTINUE.equals(name)) {
            requireKeys(safe, Set.of("caseId", "analysisId"));
            text = "Continue case " + value(safe, "caseId") + ", analysis "
                    + value(safe, "analysisId") + ". First call analysis_status. Select only an "
                    + "action listed in allowedActions, bind each collection to one explicit gap, "
                    + "hypothesis and predicate, and preserve FALSE/UNKNOWN observations.";
        } else if (EXPLAIN.equals(name)) {
            requireKeys(safe, Set.of("caseId", "analysisId"));
            text = "Explain archived evidence for case " + value(safe, "caseId")
                    + ", analysis " + value(safe, "analysisId") + ". Label every statement as "
                    + "CONFIRMED_FACT, VALIDATOR_CONCLUSION, SOURCE_INFERENCE, LLM_HYPOTHESIS, "
                    + "or MISSING_EVIDENCE. Do not convert correlation, source reachability, "
                    + "truncated data or unmatched failures into a confirmed cause.";
        } else {
            throw invalidPrompt();
        }
        return new GetPromptResult(
                "Evidence-constrained algorithm debugging",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }

    private SyncPromptSpecification specification(
            String name, String description, List<PromptArgument> arguments) {
        Prompt prompt = Prompt.builder(name)
                .description(description)
                .arguments(arguments)
                .build();
        return new SyncPromptSpecification(
                prompt, (exchange, request) -> get(request.name(), request.arguments()));
    }

    private static List<PromptArgument> identityArguments() {
        return List.of(
                argument("caseId", "Case identifier", true),
                argument("analysisId", "Analysis identifier", true));
    }

    private static PromptArgument argument(
            String name, String description, boolean required) {
        return new PromptArgument(name, description, required);
    }

    private static void requireKeys(Map<String, Object> arguments, Set<String> expected) {
        if (!arguments.keySet().equals(expected)) {
            throw invalidPrompt();
        }
    }

    private static String value(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()
                || !text.equals(text.strip()) || text.length() > MAX_ARGUMENT_CHARS) {
            throw invalidPrompt();
        }
        return text;
    }

    private static McpError invalidPrompt() {
        return McpError.builder(INVALID_PARAMS).message("Invalid prompt arguments").build();
    }
}
