# ADA Core

Core 是应用用例编排层，不实现具体算法业务，也不直接调用 LLM。

当前已实现：

- `AdapterCatalog`：对组合根注入的 Adapter 做确定性选择；
- `CaseApplicationService`：打开/续接 Case 和只读检查 Digest，显式选择复用或新建 Context，打开时不运行 UT；
- `RunApplicationService`：验证 Case/Context/Analysis，先写 `run-request.json`，显式运行一次目标 UT，
  再追加 `run-result-fingerprint.json`、Context `reproduction.json`、真实比较结论、
  `run-outcome.json` 和 Artifact 引用；
- `RunArtifactArchiver`：在 Run 根目录内有界复制或引用原始产物并计算 SHA-256；
- `StaticAnalysisApplicationService`：生成有界方法目录以及精确 CodePath/JDWP 采集计划；
- `CollectionApplicationService`、`JdwpCollectionApplicationService`：执行动态采集、归档单一 Raw 流/日志/Manifest，并执行无采集 Baseline 一致性门禁；
- `ControlPlaneServices`：装配 Workspace、Project、Doctor、Case、Run、静态分析与采集用例。

Core 依赖稳定契约、Adapter SPI、Case Management 和 Debug Harness，不依赖 Wafer Demo Adapter 实现。
缺少 Maven 或进程启动失败时仍会归档 `NOT_STARTED` RunOutcome；目标 UT 断言/异常不是 Agent 崩溃。
每次 `run execute` 只创建一次 Run，不自动重试。指纹或参考后处理失败时比较为 `INCOMPARABLE`，
但已取得的目标进程、测试、异常和 Gantt 事实不会被覆盖。

Core 不计算模块源码指纹。CodePath 按已归档 Plan 的精确方法选择器采集；JDWP 继续校验每个 tracepoint 的
`SourceAnchor`。动态证据只有在 Gantt 内容或目标失败指纹与同 Context 无采集参考一致时才可用于确认结论。

```powershell
mvn -pl ada-core -am test
```
