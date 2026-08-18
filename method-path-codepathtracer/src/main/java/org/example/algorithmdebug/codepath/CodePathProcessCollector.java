package org.example.algorithmdebug.codepath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.time.Duration;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionResult;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathManifest;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.harness.ExternalProcessRunner;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunResult;

/** 启动锁定的外部 CodePath Launcher，持续排空日志并发布过滤后的采集产物。 */
public final class CodePathProcessCollector implements MethodPathCollector {
    private final CodePathToolConfiguration configuration;
    private final CodePathCommandFactory commands;
    private final MethodPathJsonlFilter filter;
    private final Clock clock;
    private final ExternalProcessRunner processes;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).addModule(new Jdk8Module())
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    /** 使用系统时钟创建生产 Collector。 */
    public CodePathProcessCollector(CodePathToolConfiguration configuration) {
        this(configuration, new CodePathCommandFactory(java.io.File.pathSeparator),
                new MethodPathJsonlFilter(), Clock.systemUTC(), new ExternalProcessRunner());
    }

    CodePathProcessCollector(
            CodePathToolConfiguration configuration,
            CodePathCommandFactory commands,
            MethodPathJsonlFilter filter,
            Clock clock,
            ExternalProcessRunner processes) {
        this.configuration = configuration;
        this.commands = commands;
        this.filter = filter;
        this.clock = clock;
        this.processes = processes;
    }

    @Override
    public MethodPathCollectionResult collect(MethodPathCollectionRequest request)
            throws MethodPathCollectionException {
        Path root = request.collectionDirectory();
        Path raw = root.resolve("raw/codepath.jsonl");
        Path filtered = root.resolve("derived/method-path.jsonl");
        Path stdout = root.resolve("logs/stdout.log");
        Path stderr = root.resolve("logs/stderr.log");
        Instant startedAt = clock.instant();
        boolean processStarted = false;
        int observedExitCode = -1;
        try {
            configuration.verifyTool();
            Files.createDirectories(raw.getParent());
            Files.createDirectories(filtered.getParent());
            Files.createDirectories(stdout.getParent());
            RunResult process = processes.execute(
                    commands.create(configuration, request, raw), request.moduleRoot(), stdout, stderr,
                    Duration.ofMillis(request.plan().budget().timeoutMillis()), ProcessLimits.defaults());
            processStarted = true;
            boolean finished = process.completion() != RunCompletion.TIMED_OUT;
            OptionalInt exit = process.exitCode();
            observedExitCode = exit.orElse(-1);
            CodePathLauncherSummary launcherSummary = finished
                    ? new LauncherSummaryReader().read(stdout) : null;
            if (!Files.exists(raw)) {
                Files.createFile(raw);
            }
            MethodPathFilterResult filterResult = filter.filter(raw, filtered, request.plan());
            CollectionCompletion completion = completion(
                    !finished, exit.orElse(-1), launcherSummary);
            if (completion == CollectionCompletion.SUCCESS && filterResult.truncated()) {
                completion = CollectionCompletion.TRUNCATED;
            }
            Optional<String> truncation = launcherSummary != null && launcherSummary.truncated()
                    ? Optional.of("launcher " + launcherSummary.limit())
                    : filterResult.truncationReason();
            Optional<AgentFailureDiagnostic> agentFailure = completion == CollectionCompletion.TOOL_FAILED
                    ? Optional.of(new AgentFailureDiagnostic(
                            "CODEPATH_LAUNCHER_FAILED", "CodePath Launcher reported a tool failure"))
                    : Optional.empty();
            MethodPathManifest manifest = new MethodPathManifest(
                    "1.0", request.caseId(), request.contextId(), request.analysisId(), request.runId(),
                    request.plan().planId(), request.collectionId(), "code-path-tracer",
                    configuration.toolVersion(), Optional.of(configuration.expectedSha256()), planSha(request),
                    "PACKAGE_SUPERSET", "METHOD_ALLOWLIST", matchPrecision(filterResult),
                    request.plan().packagePrefixes(), completion, "COMPLETE", true,
                    exit.orElse(-1), !finished, filterResult.rawEventCount(),
                    filterResult.retainedEventCount(), filterResult.exactDescriptorMatchCount(),
                    filterResult.degradedClassMethodMatchCount(), filterResult.rawBytes(),
                    filterResult.filteredBytes(), Optional.of(CodePathToolConfiguration.sha256(raw)),
                    Optional.of(filterResult.filteredSha256()), truncation.stream().toList(),
                    agentFailure, "logs/stdout.log", "logs/stderr.log",
                    startedAt, clock.instant());
            return new MethodPathCollectionResult(request, manifest, raw, filtered, stdout, stderr);
        } catch (CodePathAdapterException failure) {
            throw new MethodPathCollectionException(
                    failure.code(), "CodePath 采集失败", failure, processStarted, observedExitCode);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MethodPathCollectionException(
                    "METHOD_PATH_COLLECTION_FAILED", "CodePath 外部进程采集失败", failure,
                    processStarted, observedExitCode);
        }
    }

    static CollectionCompletion completion(
            boolean timedOut, int exitCode, CodePathLauncherSummary summary) {
        if (timedOut) {
            return CollectionCompletion.TIMED_OUT;
        }
        if (summary == null || "TOOL_FAILED".equals(summary.outcome())) {
            return CollectionCompletion.TOOL_FAILED;
        }
        if ("TARGET_FAILED".equals(summary.outcome())) {
            return CollectionCompletion.TARGET_FAILED;
        }
        if (exitCode != 0) {
            return CollectionCompletion.TOOL_FAILED;
        }
        return summary.truncated() ? CollectionCompletion.TRUNCATED : CollectionCompletion.SUCCESS;
    }

    static String matchPrecision(MethodPathFilterResult result) {
        if (result == null) {
            throw new IllegalArgumentException("过滤结果不能为空");
        }
        if (result.retainedEventCount() == 0) {
            return "NONE";
        }
        return result.degradedClassMethodMatchCount() > 0
                ? "CLASS_METHOD_SUPERSET" : "EXACT_DESCRIPTOR";
    }

    private String planSha(MethodPathCollectionRequest request) throws IOException {
        return sha(mapper.writeValueAsBytes(request.plan()));
    }

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

}
