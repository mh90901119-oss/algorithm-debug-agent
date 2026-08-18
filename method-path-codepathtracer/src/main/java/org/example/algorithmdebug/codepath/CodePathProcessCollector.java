package org.example.algorithmdebug.codepath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    /** 使用系统时钟创建生产 Collector。 */
    public CodePathProcessCollector(CodePathToolConfiguration configuration) {
        this(configuration, new CodePathCommandFactory(java.io.File.pathSeparator),
                Clock.systemUTC(), new ExternalProcessRunner());
    }

    CodePathProcessCollector(CodePathToolConfiguration configuration, CodePathCommandFactory commands,
            Clock clock, ExternalProcessRunner processes) {
        this.configuration = configuration; this.commands = commands; this.clock = clock; this.processes = processes;
    }

    @Override
    public MethodPathCollectionResult collect(MethodPathCollectionRequest request)
            throws MethodPathCollectionException {
        Path root = request.collectionDirectory();
        Path plan = root.resolve("request/plan.json");
        Path raw = root.resolve("raw/codepath.jsonl");
        Path stdout = root.resolve("logs/stdout.log");
        Path stderr = root.resolve("logs/stderr.log");
        Instant startedAt = clock.instant();
        boolean processStarted = false;
        int observedExitCode = -1;
        try {
            configuration.verifyTool();
            Files.createDirectories(plan.getParent()); Files.createDirectories(raw.getParent());
            Files.createDirectories(stdout.getParent());
            byte[] planBytes = mapper.writeValueAsBytes(request.plan());
            archivePlan(plan, planBytes);
            RunResult process = processes.execute(
                    commands.create(configuration, request, plan, raw), request.moduleRoot(), stdout, stderr,
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
                            summary == null ? "CodePath Launcher 未返回有效摘要" : summary.detail()))
                    : Optional.empty();
            long events = summary == null ? 0 : summary.eventsWritten();
            long bytes = Files.size(raw);
            MethodPathManifest manifest = new MethodPathManifest(
                    "2.0", request.caseId(), request.contextId(), request.analysisId(), request.runId(),
                    request.plan().planId(), request.collectionId(), "code-path-tracer",
                    configuration.toolVersion(), Optional.of(configuration.expectedSha256()), sha(planBytes),
                    completion, "COMPLETE", true, observedExitCode, !finished,
                    targetOutcome(summary), summary == null ? 0 : summary.testsFound(),
                    summary == null ? 0 : summary.testsSucceeded(),
                    summary == null ? 0 : summary.testsAborted(),
                    summary == null ? 0 : summary.testsFailed(), events, bytes,
                    Optional.of(CodePathToolConfiguration.sha256(raw)), truncation, diagnostic,
                    "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log", startedAt, clock.instant());
            return new MethodPathCollectionResult(request, manifest, raw, stdout, stderr);
        } catch (CodePathAdapterException failure) {
            throw new MethodPathCollectionException(
                    failure.code(), "CodePath 采集失败", failure, processStarted, observedExitCode);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MethodPathCollectionException(
                    "METHOD_PATH_COLLECTION_FAILED", "CodePath 外部进程采集失败", failure,
                    processStarted, observedExitCode);
        }
    }

    private static void archivePlan(Path destination, byte[] content) throws java.io.IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
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

    private static String sha(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("JDK 缺少 SHA-256", failure); }
    }
}
