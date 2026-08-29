package org.example.algorithmdebug.jdwp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.ProcessLimits;

/**
 * 一次 JDWP 双进程运行所需的机器边界、日志路径和时间预算。
 *
 * <p>该类型是 Agent 内部执行请求，不接收大模型拼接后的 argv。</p>
 *
 * @param targetLaunch 目标 UT 的 JDWP 模式启动规格
 * @param targetOptions Maven 可执行文件、目标日志和进程限制
 * @param port 已写入并归档到 Collector Plan 的 loopback 端口
 * @param javaExecutable 启动 Collector 的 Java 21 可执行文件
 * @param collectorJar Collector JAR
 * @param collectorPlan 已归档的 Collector Plan
 * @param collectorOutputDirectory 本次 Collector Raw 输出目录
 * @param collectorStdoutLog Collector stdout create-new 日志
 * @param collectorStderrLog Collector stderr create-new 日志
 * @param collectorProcessLimits Collector 日志和终止预算
 * @param maximumRawBytes Raw Trace 最大允许字节数
 * @param targetReadyTimeout 目标 JDWP listening 标记等待预算
 * @param overallTimeout 双进程整体执行预算
 */
public record JdwpExecutionRequest(
        TestLaunchSpec targetLaunch,
        MavenExecutionOptions targetOptions,
        int port,
        Path javaExecutable,
        Path collectorJar,
        Path collectorPlan,
        Path collectorOutputDirectory,
        Path collectorStdoutLog,
        Path collectorStderrLog,
        ProcessLimits collectorProcessLimits,
        long maximumRawBytes,
        Duration targetReadyTimeout,
        Duration overallTimeout) {

    /** 校验运行模式、工具输入、日志隔离和有界超时。 */
    public JdwpExecutionRequest {
        if (targetLaunch == null || targetOptions == null || collectorProcessLimits == null) {
            throw new IllegalArgumentException("The target launch specification, execution options, and Collector limits must not be null");
        }
        if (targetLaunch.runMode() != RunMode.JDWP) {
            throw new IllegalArgumentException("targetLaunch.runMode must be JDWP");
        }
        JdwpTargetCommandFactory.requirePort(port);
        javaExecutable = requireFile(javaExecutable, "javaExecutable");
        collectorJar = requireFile(collectorJar, "collectorJar");
        collectorPlan = requireFile(collectorPlan, "collectorPlan");
        collectorOutputDirectory = requireAbsolute(collectorOutputDirectory, "collectorOutputDirectory");
        if (Files.exists(collectorOutputDirectory.resolve("raw-trace.jsonl"))
                || Files.exists(collectorOutputDirectory.resolve("collection-manifest.json"))) {
            throw new IllegalArgumentException("The Collector output directory must not contain a historical Raw Trace or Manifest");
        }
        collectorStdoutLog = requireAbsolute(collectorStdoutLog, "collectorStdoutLog");
        collectorStderrLog = requireAbsolute(collectorStderrLog, "collectorStderrLog");
        List<Path> logPaths = List.of(
                targetOptions.stdoutLog(), targetOptions.stderrLog(),
                collectorStdoutLog, collectorStderrLog);
        if (new HashSet<>(logPaths).size() != logPaths.size()) {
            throw new IllegalArgumentException("The four target and Collector log paths must all be distinct");
        }
        if (maximumRawBytes < 1 || maximumRawBytes > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("maximumRawBytes must be between 1 and 50 MiB");
        }
        requirePositive(targetReadyTimeout, "targetReadyTimeout");
        requirePositive(overallTimeout, "overallTimeout");
        if (targetReadyTimeout.compareTo(overallTimeout) > 0) {
            throw new IllegalArgumentException("targetReadyTimeout must not exceed overallTimeout");
        }
    }

    /** @return 已配置 Collector 在 outputDirectory 下使用的 Raw Trace 路径 */
    public Path rawTracePath() {
        return collectorOutputDirectory.resolve("raw-trace.jsonl");
    }

    private static Path requireFile(Path value, String field) {
        Path path = requireAbsolute(value, field);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(field + " must be an existing regular file: " + path);
        }
        return path;
    }

    private static Path requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute path");
        }
        return value.normalize();
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofMinutes(20)) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 20 minutes");
        }
    }
}
