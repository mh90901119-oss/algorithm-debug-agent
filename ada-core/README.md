# ADA Core

Core 是应用用例编排层，不实现具体算法业务，也不直接调用 LLM。

当前已实现：

- `AdapterCatalog`：对组合根注入的 Adapter 做确定性选择；
- `CaseApplicationService`：打开/续接 Case 和只读检查 Digest，打开时不运行 UT；
- `RunApplicationService`：验证 Case/Context/Analysis，先写 `run-request.json`，显式运行一次目标 UT，
  再追加 `run-outcome.json` 和 Artifact 引用；
- `RunArtifactArchiver`：在 Run 根目录内有界复制或引用原始产物并计算 SHA-256；
- `ControlPlaneServices`：装配 Workspace、Project、Doctor、Case 和 Run 用例。

Core 依赖稳定契约、Adapter SPI、Case Management 和 Debug Harness，不依赖 Wafer Demo Adapter 实现。
缺少 Maven 或进程启动失败时仍会归档 `NOT_STARTED` RunOutcome；目标 UT 断言/异常不是 Agent 崩溃。
每次 `run execute` 只创建一次 Run，不自动重试。

```powershell
mvn -pl ada-core -am test
```
