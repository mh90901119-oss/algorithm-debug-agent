package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.TestLaunchSpec;

/** 可替换的目标测试进程执行端口，用于隔离进程与结果编排。 */
@FunctionalInterface
public interface TargetTestExecutor {

    /** @return 一次目标测试的结构化进程结果 */
    RunResult execute(TestLaunchSpec spec, MavenExecutionOptions options) throws HarnessException;
}
