package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.core.JdiSocketAttacher;
import one.edee.mcp.jdwp.core.JdwpEndpoint;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-owned JDWP Collector 命令行入口；不依赖 Spring、MCP 或目标算法代码。
 */
public final class CollectorMain {
    static final String COLLECTOR_VERSION = "4.0.0";
    static final String RAW_TRACE_SCHEMA_VERSION = "3.0";
    static final long MAX_PLAN_BYTES = 1024L * 1024;

    private CollectorMain() {
    }

    public static void main(String[] args) {
        try {
            run(Arguments.parse(args));
        } catch (Exception exception) {
            System.err.println("JDWP collection failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(2);
        }
    }

    static void run(Arguments arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        DebugPlan plan = readPlan(mapper, arguments.plan());
        plan.validate();

        Files.createDirectories(arguments.outputDirectory());
        Path trace = arguments.outputDirectory().resolve("raw-trace.jsonl");
        Path manifest = arguments.outputDirectory().resolve("collection-manifest.json");
        Path manifestTemporary = arguments.outputDirectory().resolve("collection-manifest.json.tmp");
        Instant startedAt = Instant.now();
        TracePlanExecutor.CollectionResult result;
        VirtualMachine vm = new JdiSocketAttacher().attach(
            new JdwpEndpoint(plan.target.host, plan.target.port)
        );
        try (JsonlTraceWriter writer = new JsonlTraceWriter(mapper, trace)) {
            result = new TracePlanExecutor(vm, plan, writer).execute();
        } finally {
            try {
                vm.dispose();
            } catch (RuntimeException ignored) {
                // 目标 JVM 通常先退出，此时不需要再次 dispose。
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "2.0");
        data.put("collectorVersion", COLLECTOR_VERSION);
        data.put("rawTraceSchemaVersion", RAW_TRACE_SCHEMA_VERSION);
        data.put("capabilities", List.of(
            "exact-method-descriptor",
            "code-index",
            "typed-values",
            "precise-value-paths",
            "tracepoint-request-group",
            "and-conditions",
            "separate-hit-counters"
        ));
        data.put("sessionId", plan.sessionId);
        data.put("target", Map.of("host", plan.target.host, "port", plan.target.port));
        data.put("plan", arguments.plan().getFileName().toString());
        data.put("trace", trace.getFileName().toString());
        data.put("startedAt", startedAt.toString());
        data.put("finishedAt", Instant.now().toString());
        data.put("completionReason", result.completionReason());
        data.put("eventCount", result.eventCount());
        data.put("observedHitCounts", result.observedHitCounts());
        data.put("matchedHitCounts", result.matchedHitCounts());
        data.put("capturedHitCounts", result.capturedHitCounts());
        data.put("conditionUnavailableCounts", result.conditionUnavailableCounts());
        data.put("conditionUnavailableReasons", result.conditionUnavailableReasons());
        data.put("installedLocations", result.installedLocations());
        mapper.writeValue(manifestTemporary.toFile(), data);
        Files.move(
            manifestTemporary,
            manifest,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );

        System.out.printf(
            "Collection finished (%s), %d events written to raw-trace.jsonl%n",
            result.completionReason(),
            result.eventCount()
        );
    }

    static DebugPlan readPlan(ObjectMapper mapper, Path path) throws java.io.IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new java.io.IOException("JDWP plan must be a regular non-symbolic-link file");
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_PLAN_BYTES) {
            throw new java.io.IOException("JDWP plan size must be between 1 byte and 1 MiB");
        }
        return mapper.readValue(Files.readAllBytes(path), DebugPlan.class);
    }

    record Arguments(Path plan, Path outputDirectory) {
        static Arguments parse(String[] args) {
            Path plan = null;
            Path output = Path.of("output", "jdwp-trace");
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "collect" -> {
                        // 可选的可读性命令词。
                    }
                    case "--plan" -> plan = Path.of(requireValue(args, ++index, argument));
                    case "--output" -> output = Path.of(requireValue(args, ++index, argument));
                    case "--help", "-h" -> throw new IllegalArgumentException(usage());
                    default -> throw new IllegalArgumentException(
                        "Unknown argument: " + argument + System.lineSeparator() + usage()
                    );
                }
            }
            if (plan == null) {
                throw new IllegalArgumentException("--plan is required" + System.lineSeparator() + usage());
            }
            return new Arguments(plan, output);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: java --add-modules jdk.jdi -jar jdwp-batch-collector.jar collect "
                + "--plan <debug-plan.json> [--output <directory>]";
        }
    }
}
