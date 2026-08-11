package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.SemanticHashStrategy;
import org.example.algorithmdebug.adapter.TestLaunchSpec;

import java.nio.file.Path;
import java.util.Optional;

/** 组合运行前快照、目标测试进程、文件稳定确认和不可变结果捕获。 */
public final class ScheduleProducingTestRunner<T extends ScheduleResultSnapshot> {

    private final TargetTestExecutor executor;
    private final OutputDirectorySnapshotter snapshotter;
    private final OutputStabilityWaiter stabilityWaiter;
    private final ScheduleResultCapture<T> resultCapture;

    /** 注入彼此隔离、可独立测试的确定性端口。 */
    public ScheduleProducingTestRunner(
            TargetTestExecutor executor,
            OutputDirectorySnapshotter snapshotter,
            OutputStabilityWaiter stabilityWaiter,
            ScheduleResultCapture<T> resultCapture) {
        if (executor == null || snapshotter == null || stabilityWaiter == null || resultCapture == null) {
            throw new IllegalArgumentException("Runner 依赖不能为空");
        }
        this.executor = executor;
        this.snapshotter = snapshotter;
        this.stabilityWaiter = stabilityWaiter;
        this.resultCapture = resultCapture;
    }

    /**
     * 失败或超时运行只返回进程事实；仅成功运行继续稳定确认并捕获结果。
     */
    public ScheduleRunResult<T> run(
            TestLaunchSpec spec,
            MavenExecutionOptions options,
            ScheduleResultSource source,
            ScheduleResultParser<T> parser,
            SemanticHashStrategy<T> hashStrategy,
            Path destination) throws HarnessException {
        if (spec == null || options == null || source == null || parser == null
                || hashStrategy == null || destination == null) {
            throw new IllegalArgumentException("运行参数不能为空");
        }
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        RunResult run = executor.execute(spec, options);
        OutputDirectorySnapshot immediateAfter = snapshotter.snapshot(source);
        if (immediateAfter.changedSince(before).isEmpty()) {
            return new ScheduleRunResult<>(run, Optional.empty());
        }
        OutputDirectorySnapshot stableAfter = stabilityWaiter.awaitStable(before, source);
        CapturedScheduleResult<T> captured = resultCapture.capture(
                before, stableAfter, parser, hashStrategy, destination);
        return new ScheduleRunResult<>(run, Optional.of(captured));
    }
}
