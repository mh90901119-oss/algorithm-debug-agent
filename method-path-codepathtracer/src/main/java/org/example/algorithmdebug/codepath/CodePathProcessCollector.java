package org.example.algorithmdebug.codepath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.harness.ExternalProcessRunner;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunResult;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionResult;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathManifest;

/** 启动锁定的 CodePath Launcher，并保留精确筛选后的单一 Raw Trace。 */
public final class CodePathProcessCollector implements MethodPathCollector {
    private final CodePathToolConfiguration configuration;
    private final CodePathCommandFactory commands;
    private final Clock clock;
    private final ExternalProcessRunner processes;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).addModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    /** 使用系统时钟创建生产 Collector。 */
    public CodePathProcessCollector(CodePathToolConfiguration configuration) {
        this(configuration, new CodePathCommandFactory(java.io.File.pathSeparator),
                Clock.systemUTC(), new ExternalProcessRunner());
    }

    CodePathProcessCollector(CodePathToolConfiguration configuration, CodePathCommandFactory commands,
            Clock clock, ExternalProcessRunner processes) {
        this.configuration = configuration;
        this.commands = commands;
        this.clock = clock;
        this.processes = processes;
    }

    @Override
    public MethodPathCollectionResult collect(MethodPathCollectionRequest request)
            throws MethodPathCollectionException {
        Path root = request.collectionDirectory();
        Path raw = root.resolve("raw/codepath.jsonl");
        Path stdout = root.resolve("logs/stdout.log");
        Path stderr = root.resolve("logs/stderr.log");
        Path launcherPlan = root.resolve("request/plan.json");
        Instant startedAt = clock.instant();
        boolean processStarted = false;
        int observedExitCode = -1;
        try {
            configuration.verifyTool();
            Files.createDirectories(launcherPlan.getParent());
            Files.write(launcherPlan, mapper.writeValueAsBytes(request.plan()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.createDirectories(raw.getParent());
            Files.createDirectories(stdout.getParent());
            RunResult process = processes.execute(
                    commands.create(configuration, request, launcherPlan, raw), request.moduleRoot(), stdout, stderr,
                    Duration.ofMillis(request.plan().budget().timeoutMillis()), ProcessLimits.defaults());
            processStarted = true;
            boolean finished = process.completion() != RunCompletion.TIMED_OUT;
            observedExitCode = process.exitCode().orElse(-1);
            CodePathLauncherSummary summary = finished ? new LauncherSummaryReader().read(stdout) : null;
            if (!Files.exists(raw)) Files.createFile(raw);
            CollectionCompletion completion = completion(!finished, observedExitCode, summary);
            List<String> truncation = summary != null && summary.truncated()
                    ? List.of("launcher " + summary.limit()) : List.of();
            Optional<AgentFailureDiagnostic> diagnostic = completion == CollectionCompletion.TOOL_FAILED
                    ? Optional.of(new AgentFailureDiagnostic(
                            "CODEPATH_LAUNCHER_FAILED",
                            summary == null ? "CodePath Launcher did not return a valid summary" : summary.detail()))
                    : Optional.empty();
            long events = summary == null ? 0 : summary.eventsWritten();
            long bytes = Files.size(raw);
            MethodPathManifest manifest = new MethodPathManifest(
                    "2.0", request.caseId(), request.contextId(), request.analysisId(), request.runId(),
                    request.plan().planId(), request.collectionId(), "code-path-tracer",
                    configuration.toolVersion(),
                    completion, "COMPLETE", true, observedExitCode, !finished,
                    targetOutcome(summary), summary == null ? 0 : summary.testsFound(),
                    summary == null ? 0 : summary.testsSucceeded(),
                    summary == null ? 0 : summary.testsAborted(),
                    summary == null ? 0 : summary.testsFailed(), events, bytes,
                    truncation, diagnostic,
                    "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log", startedAt, clock.instant());
            return new MethodPathCollectionResult(request, manifest, raw, stdout, stderr);
        } catch (CodePathAdapterException failure) {
            throw new MethodPathCollectionException(
                    failure.code(), "CodePath collection failed", failure, processStarted, observedExitCode);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MethodPathCollectionException(
                    "METHOD_PATH_COLLECTION_FAILED", "CodePath external process collection failed", failure,
                    processStarted, observedExitCode);
        } finally {
            deleteLauncherFiles(launcherPlan, raw.getParent());
        }
    }

    private static void deleteLauncherFiles(Path plan, Path rawDirectory) {
        try {
            Files.deleteIfExists(plan);
            deleteIfEmpty(plan.getParent());
            deleteIfEmpty(rawDirectory);
        } catch (java.io.IOException cleanupFailure) {
            plan.toFile().deleteOnExit();
            plan.getParent().toFile().deleteOnExit();
            rawDirectory.toFile().deleteOnExit();
        }
    }

    private static void deleteIfEmpty(Path directory) throws java.io.IOException {
        if (!Files.isDirectory(directory)) return;
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
    }

    static CollectionCompletion completion(boolean timedOut, int exitCode, CodePathLauncherSummary summary) {
        if (timedOut) return CollectionCompletion.TIMED_OUT;
        if (summary == null || "TOOL_FAILED".equals(summary.outcome())) return CollectionCompletion.TOOL_FAILED;
        if ("TARGET_FAILED".equals(summary.outcome())) return CollectionCompletion.TARGET_FAILED;
        if (exitCode != 0) return CollectionCompletion.TOOL_FAILED;
        return summary.truncated() ? CollectionCompletion.TRUNCATED : CollectionCompletion.SUCCESS;
    }

    private static String targetOutcome(CodePathLauncherSummary summary) {
        if (summary == null || summary.testsFound() == 0) return "NOT_EXECUTED";
        return summary.testsFailed() > 0 || summary.testsAborted() > 0 ? "FAILED" : "PASSED";
    }
}
