package org.example.algorithmdebug.jdwp;

import java.util.Optional;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunResult;

/**
 * 一次双进程协调得到的确定性进程事实；不包含 Raw Trace 业务解释。
 *
 * @param port 本次实际使用的 loopback 端口
 * @param completion 双进程确定性完成分类
 * @param targetStarted 目标进程是否启动
 * @param collectorStarted Collector 进程是否启动
 * @param target 目标完成事实；未启动时为空
 * @param collector Collector 完成事实；未启动时为空
 */
public record JdwpExecutionResult(
        int port,
        JdwpCollectionCompletion completion,
        boolean targetStarted,
        boolean collectorStarted,
        Optional<RunResult> target,
        Optional<RunResult> collector) {

    /** 校验启动事实、运行结果与完成分类的一致性。 */
    public JdwpExecutionResult {
        JdwpTargetCommandFactory.requirePort(port);
        if (completion == null || target == null || collector == null) {
            throw new IllegalArgumentException("completion and process result must not be null");
        }
        if (targetStarted != target.isPresent() || collectorStarted != collector.isPresent()) {
            throw new IllegalArgumentException("The started flag must match the process result");
        }
        if (completion == JdwpCollectionCompletion.SUCCESS
                && (target.stream().anyMatch(result -> result.completion() != RunCompletion.SUCCEEDED)
                || collector.stream().anyMatch(result -> result.completion() != RunCompletion.SUCCEEDED)
                || target.isEmpty() || collector.isEmpty())) {
            throw new IllegalArgumentException("SUCCESS must contain two successful process results");
        }
    }
}
