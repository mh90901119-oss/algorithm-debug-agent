# Debug Harness

`SurefireDiagnosticReader` 从 Surefire XML 确定性提取失败分类、异常类、规范化消息、cause 和稳定业务栈帧，
但不推断算法业务根因。目标进程结果与调度结果捕获相互独立：断言失败或算法异常前若已生成新的稳定 Gantt，
该产物仍会被捕获并保留；XML 解析禁用 DTD 和外部实体。

Phase 0 已实现目标算法 UT 的受控 Maven 执行、动态结果发现与确定性捕获：

- `MavenCommandFactory`：从 `TestLaunchSpec` 生成不经过 Shell 的参数数组；
- `MavenTestExecutor`：以显式 Maven executable 和项目工作目录运行目标 UT；
- `BoundedOutputCapture`：分别、有界归档 stdout/stderr，超限后继续排空；
- `ProcessSupervisor`：处理退出码、超时和 Maven/Surefire 进程树分级终止；
- `RunResult`：结构化保存完成分类、退出码、耗时、日志与清理事实；
- `OutputDirectorySnapshotter`：有界扫描输出目录；
- `OutputDirectorySnapshot`：比较运行前后的新增或修改文件；
- `OutputStabilityWaiter`：在有限预算内要求结果目录连续稳定；
- `ScheduleResultCapture`：用业务 Adapter Parser 验证候选，只接受唯一合法结果；
- `CapturedScheduleResult`：保存源路径、不可变副本、原始 SHA-256 和语义哈希。
- `ScheduleProducingTestRunner`：组合快照、执行、稳定确认和不可变捕获。

标准调用顺序：

```text
runner = new ScheduleProducingTestRunner(executor, snapshotter, waiter, capture)
result = runner.run(spec, options, source, parser, hashStrategy, runResultPath)
```

Adapter 不得选择“最新文件”、指定机器上的 Maven 路径或把时间戳格式作为通用规则。Maven executable
和日志路径由运行环境显式提供。真实端到端测试已使用正式 Runner 驱动两次 Demo UT，不再包含测试内
`ProcessBuilder`。完整设计与实现记录见
`docs/designs/2026-08-11-debug-harness-maven-junit-runner-design.md`。

当前仍未实现 Run Manifest Writer、Case State 持久化和正式 CLI。

```powershell
mvn -pl debug-harness -am test
```
