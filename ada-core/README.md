# ADA Core

Core 是应用用例编排层，不实现目标算法业务语义，也不直接调用 LLM。

## 当前职责

- `AdapterCatalog`：确定性选择目标项目 Adapter。
- `CaseApplicationService`：创建或复用 Case、追加 Analysis、读取 Digest；打开 Case 时不运行 UT。
- `AlgorithmInputApplicationService`：识别目标 UT 第一层唯一输入路径，首次按原名复制到 Case，后续 Analysis 校验并复用。
- `RunApplicationService`：先归档 `run-request.json`，再执行一次目标 UT，最后追加 `run-outcome.json`、失败指纹和 Artifact 引用；不自动重试。
- `RunArtifactArchiver`：在 Run 目录内有界复制 stdout、stderr、Surefire XML 和本次新增 Gantt，并计算 Artifact SHA-256。
- `StaticAnalysisApplicationService`：为当前 Analysis 生成有界 Method Catalog，并编译精确 CodePath/JDWP Plan。
- `CollectionApplicationService`：执行 CodePath 动态采集并保存请求、Manifest、Raw Trace 和日志。
- `JdwpCollectionApplicationService`：启动目标测试 JVM 与 loopback Collector，保存有界快照及执行事实。
- `CollectionPostProcessingService`：确定性生成 Normalized Summary、Validation、Evidence Bundle 和 Sufficiency Evaluation；后处理失败单独保存，不覆盖原始采集事实。
- `ControlPlaneServices`：装配 Workspace、Project、Doctor、Case、Run、静态分析和动态采集用例。

## 执行边界

缺少 Maven、JDK 或进程启动失败属于 Agent/环境故障；目标 UT 的异常、断言失败、超时和非零退出仍是目标执行证据。每次 `run execute` 只创建一个 Run。

普通 Run 与动态 Collection 通过同一 `analysisId` 关联，不要求使用同一个 `runId`。失败动态采集通过结构化失败指纹与该 Analysis 的普通 Run 比较；成功运行不比较 Gantt 内容 SHA。CodePath 与 JDWP 相互独立，均按已归档 Plan 和预算执行。

```powershell
mvn -pl ada-core -am test
```