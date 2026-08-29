package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
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
            throw new IllegalArgumentException("Runner dependencies must not be null");
        }
        this.executor = executor;
        this.snapshotter = snapshotter;
        this.stabilityWaiter = stabilityWaiter;
        this.resultCapture = resultCapture;
    }

    /**
     * 执行目标 UT，并在取得进程结果后把 Gantt 后处理失败作为独立事实返回。
     */
    public ScheduleRunResult<T> run(
            TestLaunchSpec spec,
            MavenExecutionOptions options,
            ScheduleResultSource source,
            ScheduleResultParser<T> parser,
            Path destination) throws HarnessException {
        if (spec == null || options == null || source == null || parser == null
                || destination == null) {
            throw new IllegalArgumentException("Run arguments must not be null");
        }
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        RunResult run = executor.execute(spec, options);
        java.util.List<Path> changedOutputCandidates = java.util.List.of();
        try {
            OutputDirectorySnapshot immediateAfter = snapshotter.snapshot(source);
            changedOutputCandidates = immediateAfter.changedSince(before);
            if (changedOutputCandidates.isEmpty()) {
                return ScheduleRunResult.absent(run);
            }
            OutputDirectorySnapshot stableAfter = stabilityWaiter.awaitStable(before, source);
            changedOutputCandidates = stableAfter.changedSince(before);
            CapturedScheduleResult<T> captured = resultCapture.capture(
                    before, stableAfter, parser, destination);
            return ScheduleRunResult.present(run, captured, changedOutputCandidates);
        } catch (HarnessException exception) {
            return ScheduleRunResult.incomplete(run, exception, changedOutputCandidates);
        } catch (RuntimeException exception) {
            return ScheduleRunResult.incomplete(
                    run, "HARNESS_GANTT_PROCESSING_FAILED", exception, changedOutputCandidates);
        }
    }

    /** 未配置结果目录时仍执行 UT，只将 Gantt 观察记为 ABSENT。 */
    public ScheduleRunResult<T> run(
            TestLaunchSpec spec,
            MavenExecutionOptions options,
            Optional<ScheduleResultSource> source,
            ScheduleResultParser<T> parser,
            Path destination) throws HarnessException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (source.isEmpty()) {
            return ScheduleRunResult.absent(executor.execute(spec, options));
        }
        return run(spec, options, source.orElseThrow(), parser, destination);
    }
}
