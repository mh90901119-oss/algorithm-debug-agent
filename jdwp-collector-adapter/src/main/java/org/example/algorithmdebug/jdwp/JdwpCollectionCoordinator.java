package org.example.algorithmdebug.jdwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.ManagedProcess;
import org.example.algorithmdebug.harness.ManagedProcessRunner;
import org.example.algorithmdebug.harness.ProcessOutputWaitResult;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunResult;

/**
 * 协调 suspended 目标 UT 与外部 JDWP Collector 的确定性启动、等待和失败清理。
 *
 * <p>目标必须先报告 JDK listening 标记，之后才允许启动 Collector。任何 Collector 失败都会
 * 终止目标 Maven/Surefire 完整进程树。</p>
 */
public final class JdwpCollectionCoordinator {
    private static final long RAW_SIZE_POLL_MILLIS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ManagedProcessRunner processes;
    private final JdwpProcessCommandFactory targetCommands;
    private final JdwpProcessCommandFactory collectorCommands;
    private final LoopbackPortReadinessProbe readiness;
    private final LongSupplier nanoTime;

    /** 使用 loopback 临时端口、标准 Maven/JDWP 命令和系统单调时钟。 */
    public JdwpCollectionCoordinator() {
        this(new ManagedProcessRunner(),
                (request, port) -> new JdwpTargetCommandFactory().create(
                        request.targetLaunch(), request.targetOptions(), port),
                (request, port) -> new JdwpCollectorCommandFactory().create(
                        request.javaExecutable(), request.collectorJar(), request.collectorPlan(),
                        request.collectorOutputDirectory(), port),
                new LoopbackPortReadinessProbe(),
                System::nanoTime);
    }

    JdwpCollectionCoordinator(
            ManagedProcessRunner processes,
            JdwpProcessCommandFactory targetCommands,
            JdwpProcessCommandFactory collectorCommands,
            LoopbackPortReadinessProbe readiness,
            LongSupplier nanoTime) {
        this.processes = processes;
        this.targetCommands = targetCommands;
        this.collectorCommands = collectorCommands;
        this.readiness = readiness;
        this.nanoTime = nanoTime;
    }

    /**
     * 执行一次双进程采集并返回进程事实；启动或资源管理失败以结构化异常报告。
     *
     * @param request 已绑定归档 Collector Plan、端口、工具锁和预算的执行请求
     * @return 成功、目标失败、工具失败、超时或截断的双进程事实
     * @throws JdwpAdapterException 工具校验、启动、协调、中断或清理失败
     */
    public JdwpExecutionResult execute(JdwpExecutionRequest request) throws JdwpAdapterException {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        verifyCollector(request);
        verifyCollectorPlanEndpoint(request);
        int port = request.port();
        long deadline = deadline(request.overallTimeout());
        ExecutionPhase phase = ExecutionPhase.TARGET_START;
        boolean targetStarted = false;
        boolean collectorStarted = false;
        try {
            try (ManagedProcess target = startTarget(request, port)) {
                targetStarted = true;
                phase = ExecutionPhase.TARGET_READY;
                ProcessOutputWaitResult readinessResult = readiness.await(
                        target, port,
                        min(request.targetReadyTimeout(), remaining(deadline)));
                if (readinessResult != ProcessOutputWaitResult.OBSERVED) {
                    RunResult targetResult = readinessResult == ProcessOutputWaitResult.PROCESS_EXITED
                            ? target.await(remaining(deadline)) : terminateAndCapture(target);
                    JdwpCollectionCompletion completion = readinessResult == ProcessOutputWaitResult.TIMED_OUT
                            ? JdwpCollectionCompletion.TIMED_OUT : JdwpCollectionCompletion.TARGET_FAILED;
                    return result(port, completion, targetResult, null);
                }

                phase = ExecutionPhase.COLLECTOR_START;
                try (ManagedProcess collector = startCollector(request, port)) {
                    collectorStarted = true;
                    phase = ExecutionPhase.COLLECTING;
                    CollectorWaitResult collectorWait = awaitCollector(request, collector, deadline);
                    RunResult collectorResult = collectorWait.process();
                    if (collectorWait.rawLimitExceeded()) {
                        RunResult targetResult = terminateAndCapture(target);
                        return result(
                                port, JdwpCollectionCompletion.TRUNCATED,
                                targetResult, collectorResult);
                    }
                    if (collectorResult.completion() != RunCompletion.SUCCEEDED) {
                        RunResult targetResult = terminateAndCapture(target);
                        JdwpCollectionCompletion completion = collectorResult.completion() == RunCompletion.TIMED_OUT
                                ? JdwpCollectionCompletion.TIMED_OUT : JdwpCollectionCompletion.TOOL_FAILED;
                        return result(port, completion, targetResult, collectorResult);
                    }

                    phase = ExecutionPhase.TARGET_COMPLETION;
                    RunResult targetResult = target.await(remaining(deadline));
                    JdwpCollectionCompletion completion = targetResult.completion() == RunCompletion.SUCCEEDED
                            ? JdwpCollectionCompletion.SUCCESS
                            : targetResult.completion() == RunCompletion.TIMED_OUT
                                    ? JdwpCollectionCompletion.TIMED_OUT
                                    : JdwpCollectionCompletion.TARGET_FAILED;
                    return result(port, completion, targetResult, collectorResult);
                }
            }
        } catch (Exception failure) {
            JdwpAdapterException wrapped = new JdwpAdapterException(
                    errorCode(phase, failure),
                    "JDWP 双进程协调失败", failure, targetStarted, collectorStarted);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw wrapped;
        }
    }

    private ManagedProcess startTarget(JdwpExecutionRequest request, int port) throws HarnessException {
        return processes.start(
                targetCommands.create(request, port), request.targetLaunch().project().projectRoot(),
                request.targetOptions().stdoutLog(), request.targetOptions().stderrLog(),
                request.targetOptions().processLimits(), List.of());
    }

    private ManagedProcess startCollector(JdwpExecutionRequest request, int port) throws HarnessException {
        return processes.start(
                collectorCommands.create(request, port), request.targetLaunch().project().projectRoot(),
                request.collectorStdoutLog(), request.collectorStderrLog(),
                request.collectorProcessLimits(), List.of());
    }

    private static RunResult terminateAndCapture(ManagedProcess process) throws HarnessException {
        return process.await(Duration.ofMillis(1));
    }

    private CollectorWaitResult awaitCollector(
            JdwpExecutionRequest request,
            ManagedProcess collector,
            long deadline) throws HarnessException, IOException, InterruptedException {
        while (collector.isAlive()) {
            if (rawLimitExceeded(request)) {
                return new CollectorWaitResult(terminateAndCapture(collector), true);
            }
            long remainingNanos = deadline - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                return new CollectorWaitResult(terminateAndCapture(collector), false);
            }
            long sleepMillis = Math.min(
                    RAW_SIZE_POLL_MILLIS,
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMillis);
        }
        RunResult process = collector.await(remaining(deadline));
        return new CollectorWaitResult(process, rawLimitExceeded(request));
    }

    private static boolean rawLimitExceeded(JdwpExecutionRequest request) throws IOException {
        return Files.exists(request.rawTracePath())
                && Files.size(request.rawTracePath()) > request.maximumRawBytes();
    }

    private static void verifyCollector(JdwpExecutionRequest request) throws JdwpAdapterException {
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(request.collectorJar())) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            actual = HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new JdwpAdapterException(
                    "JDWP_TOOL_VERIFICATION_FAILED", "无法读取锁定的 JDWP Collector JAR", failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
        if (!actual.equals(request.expectedCollectorSha256())) {
            throw new JdwpAdapterException(
                    "JDWP_TOOL_HASH_MISMATCH", "JDWP Collector JAR SHA-256 与工具锁不一致", null);
        }
    }

    private static void verifyCollectorPlanEndpoint(JdwpExecutionRequest request)
            throws JdwpAdapterException {
        try {
            JsonNode plan = JSON.readTree(request.collectorPlan().toFile());
            JsonNode target = plan.path("target");
            if (!"127.0.0.1".equals(target.path("host").asText())
                    || target.path("port").asInt(-1) != request.port()
                    || !plan.path("resumeOnAttach").asBoolean(false)) {
                throw new JdwpAdapterException(
                        "JDWP_COLLECTOR_PLAN_ENDPOINT_MISMATCH",
                        "Collector Plan 必须使用本次执行的 loopback 端口并启用 resumeOnAttach",
                        null);
            }
        } catch (JdwpAdapterException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new JdwpAdapterException(
                    "JDWP_COLLECTOR_PLAN_INVALID", "无法读取 Collector Plan", failure);
        }
    }

    private static JdwpExecutionResult result(
            int port,
            JdwpCollectionCompletion completion,
            RunResult target,
            RunResult collector) {
        return new JdwpExecutionResult(
                port, completion, target != null, collector != null,
                Optional.ofNullable(target), Optional.ofNullable(collector));
    }

    private long deadline(Duration timeout) {
        long now = nanoTime.getAsLong();
        long nanos = timeout.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private Duration remaining(long deadline) {
        long nanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - nanoTime.getAsLong());
        return Duration.ofNanos(nanos);
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static String errorCode(ExecutionPhase phase, Exception failure) {
        if (failure instanceof HarnessException harness
                && "HARNESS_PROCESS_TREE_CLEANUP_FAILED".equals(harness.code())) {
            return "JDWP_PROCESS_TREE_CLEANUP_FAILED";
        }
        return switch (phase) {
            case TARGET_START -> "JDWP_TARGET_START_FAILED";
            case TARGET_READY -> "JDWP_TARGET_READY_FAILED";
            case COLLECTOR_START -> "JDWP_COLLECTOR_START_FAILED";
            case COLLECTING, TARGET_COMPLETION -> "JDWP_COLLECTION_FAILED";
        };
    }

    private enum ExecutionPhase {
        TARGET_START,
        TARGET_READY,
        COLLECTOR_START,
        COLLECTING,
        TARGET_COMPLETION
    }

    private record CollectorWaitResult(RunResult process, boolean rawLimitExceeded) { }
}
