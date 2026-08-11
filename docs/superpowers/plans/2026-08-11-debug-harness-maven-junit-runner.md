# Debug Harness Maven/JUnit Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将集成测试中的临时 `ProcessBuilder` 提取为安全、可测试、可复用的 Maven/JUnit Runner，并与现有调度结果捕获链路组合。

**Architecture:** 纯 `MavenCommandFactory` 只生成 argv；`MavenTestExecutor` 与 `ProcessSupervisor` 负责进程、日志和超时；`ScheduleProducingTestRunner` 负责运行前快照、稳定轮询及结果捕获。Case、Manifest 和 CLI 不进入本计划。

**Tech Stack:** Java 21、Maven、JUnit 5、JDK `ProcessBuilder`/`ProcessHandle`/NIO。

## Global Constraints

- 不修改目标算法生产源码、目标 POM 或原始 UT。
- 不调用 Shell，不拼接命令字符串；每个参数作为独立 argv token。
- stdout/stderr 分离、有界归档，超限后继续排空。
- 超时必须清理 Maven 与 Surefire 进程树。
- 每项行为先运行失败测试，再写最小实现。
- 当前仓库无 `HEAD`，不创建 worktree、不提交、不清理全部 untracked 内容。

---

### Task 1: 命令编译和不可变运行结果

**Files:**
- Modify: `adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/AdapterChecks.java`
- Modify: `adapter-sdk/src/test/java/org/example/algorithmdebug/adapter/TestLaunchSpecTest.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/MavenExecutionOptions.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ProcessLimits.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/MavenCommandFactory.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/RunCompletion.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/RunLog.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/TerminationReport.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/RunResult.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/MavenCommandFactoryTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/RunResultTest.java`

**Interfaces:**
- Consumes: `TestLaunchSpec`。
- Produces: `List<String> MavenCommandFactory.create(TestLaunchSpec, MavenExecutionOptions)`；不可变 `RunResult` 模型。

- [x] **Step 1: 写失败测试**

```java
assertEquals(List.of(maven.toString(), "-Dtest=A#b", "-Dvalue=x&y", "test"),
        factory.create(spec, options));
assertThrows(IllegalArgumentException.class,
        () -> launchSpecWithJvmArgument("-javaagent=has space.jar"));
```

- [x] **Step 2: 验证 Red**

Run: `mvn -pl debug-harness -am -Dtest=MavenCommandFactoryTest,RunResultTest,TestLaunchSpecTest test`

Expected: 新类型不存在导致 testCompile 失败。

- [x] **Step 3: 最小实现**

实现构造校验、argv 顺序、`argLine` 冲突拒绝，以及 `RunResult` 的 completion/exitCode 不变量；所有公共类型添加中文 Javadoc。

- [x] **Step 4: 验证 Green 并审计**

Run: `mvn -pl debug-harness -am test`

审计：确认没有 `cmd /c`、`powershell`、`sh -c` 或字符串连接执行路径；确认 property value 是一个 token。

### Task 2: 有界双流日志和进程树监管

**Files:**
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/BoundedOutputCapture.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ProcessSupervisor.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/MavenTestExecutor.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/BoundedOutputCaptureTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ProcessSupervisorTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ProcessFixtureMain.java`

**Interfaces:**
- Consumes: argv、工作目录、`ProcessLimits`、`TestLaunchSpec.timeout()`。
- Produces: `RunResult MavenTestExecutor.execute(TestLaunchSpec, MavenExecutionOptions)`。

- [x] **Step 1: 写日志分离、截断、非零退出、超时子进程失败测试**

```java
assertEquals("stdout", Files.readString(result.stdout().path()));
assertEquals("stderr", Files.readString(result.stderr().path()));
assertTrue(result.stdout().truncated());
assertEquals(RunCompletion.TIMED_OUT, result.completion());
assertTrue(result.termination().survivingProcessIds().isEmpty());
```

- [x] **Step 2: 验证 Red**

Run: `mvn -pl debug-harness -am -Dtest=BoundedOutputCaptureTest,ProcessSupervisorTest test`

Expected: 监管类型不存在或行为断言失败。

- [x] **Step 3: 最小实现**

以两个线程持续排空流；日志达到预算后只计数丢弃字节。超时先按后代深度优先 `destroy()`，等待，再对存活进程 `destroyForcibly()`；中断路径执行相同清理并恢复中断标记。

- [x] **Step 4: 验证 Green 并审计**

Run: `mvn -pl debug-harness -am test`

审计：检查所有线程、流和进程在成功/失败/超时路径关闭；确认失败保留 cause，日志使用 `CREATE_NEW`。

### Task 3: 文件稳定和调度结果组合器

**Files:**
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleResultCapture.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/OutputStabilityPolicy.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/OutputStabilityWaiter.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleRunResult.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunner.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleResultCaptureTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/OutputStabilityWaiterTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`

**Interfaces:**
- Consumes: Runner、result source、parser、hash strategy、稳定策略和不可变输出路径。
- Produces: `ScheduleRunResult<T>`；成功含捕获结果，失败/超时不捕获。

- [x] **Step 1: 写失败测试**

```java
assertEquals(stableAfter, waiter.awaitStable(before, source));
assertTrue(success.scheduleResult().isPresent());
assertTrue(failed.scheduleResult().isEmpty());
```

- [x] **Step 2: 验证 Red**

Run: `mvn -pl debug-harness -am -Dtest=OutputStabilityWaiterTest,ScheduleProducingTestRunnerTest,ScheduleResultCaptureTest test`

Expected: 新类型或显式 after snapshot 重载缺失。

- [x] **Step 3: 最小实现**

稳定 waiter 连续观察两次相同且相对 before 有变化的快照；`ScheduleResultCapture` 新重载使用调用方提供的 after；组合器只在退出码 0 后稳定和捕获。

- [x] **Step 4: 验证 Green 并审计**

Run: `mvn -pl debug-harness -am test`

审计：确认无结果时不等待无限时长、失败运行不读取旧结果、现有捕获入口兼容且目标不可覆盖。

### Task 4: 真实集成替换、文档和全量验证

**Files:**
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java`
- Modify: `debug-harness/README.md`
- Modify: `integration-tests/README.md`
- Modify: `docs/designs/2026-08-11-debug-harness-maven-junit-runner-design.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`

**Interfaces:**
- Consumes: Task 1-3 的正式 Runner API。
- Produces: 不含测试内 `ProcessBuilder` 的两次真实 Baseline 验证与同步文档。

- [x] **Step 1: 先修改集成测试调用正式 Runner，并观察 Red**

Run: `mvn -pl integration-tests -am test "-Dwafer.demo.projectRoot=D:\javacode\hellomvn"`

Expected: 若正式 API 尚有集成缺口，测试必须明确失败；修复缺口前不弱化原有 165 操作和稳定哈希断言。

- [x] **Step 2: 最小修复并运行相关测试**

Run: `mvn -pl integration-tests -am test "-Dwafer.demo.projectRoot=D:\javacode\hellomvn"`

Expected: 两次正式 Runner 调用成功，两个不可变结果存在，语义哈希一致，165 个操作，Baseline 稳定。

- [x] **Step 3: 同步文档并完成代码审计**

检查：`rg -n "ProcessBuilder|destroyForcibly|redirectErrorStream" integration-tests debug-harness/src/main`。测试模块不得再拥有进程实现；生产代码中的每个匹配必须位于职责对应类。

- [x] **Step 4: Fresh verification**

Run: `mvn test "-Dwafer.demo.projectRoot=D:\javacode\hellomvn"`

Expected: 根 Reactor `BUILD SUCCESS`，报告精确 tests/failures/errors/skipped。

---

## Execution Notes

- 采用内联 `superpowers:executing-plans`，每个 Task 结束进行代码审计和测试检查点。
- 发现 bug 时先增加能复现的失败测试，再修改生产代码。
- 因仓库无首个 commit，本计划不执行提交；最终只报告本轮明确修改的文件。
