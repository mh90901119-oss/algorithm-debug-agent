# ada-contracts

当前契约新增 `RunOutcomeSummary`，用独立维度表达进程、测试、Gantt、目标失败、Agent 失败和基线比较。
异常仅保留通用事实，不在契约层推断算法业务根因。`ToolResponse` 2.0 删除固定的
`nextAllowedActions`，后续动作由大模型依据结构化事实与版本化 Skill 决策。
`AgentFailureDiagnostic` 仅保留稳定错误码、有界说明和可选底层异常类，不携带堆栈或敏感路径。
序列化 `RunOutcomeSummary` 时，调用方必须为 Jackson 注册 `Jdk8Module` 以处理 `Optional` 字段。

Algorithm Debug Agent 的稳定基础契约模块。它定义跨模块、跨进程和落盘 JSON 共用的不可变类型，
不负责文件读写、Agent 编排、工具调用或晶圆调度业务语义。

## 当前已实现

- Schema 版本常量；
- `ProjectId`、`CaseId`、`RunId`、`AnalysisId`、`EvidenceId`；
- `TargetTest`；
- `ExecutionIdentity`；
- `ArtifactReference`；
- `BaselineManifest`；
- `WorkspaceManifest`、`WorkspaceInitializationResult`；
- `ProjectRegistration`、`ProjectRegistrationResult`；
- `DoctorCheck`、`DoctorReport`；
- `ToolResponse<T>`；
- `CaseManifest`、`AnalysisRequest`、`RunRequest`；
- `MethodCatalog`、精确 `CodePathCollectionPlan`、`MethodPathSummary` 和 JDWP 计划/采集契约；
- `CaseDigest`、`CaseOpenResult`：有界恢复最近 Run、Collection、Evidence 与 Analysis 结果；
- `EvidenceQueryFilter`、`EvidenceQueryResult`：定义有界动态证据查询条件和返回统计；
- `CaseArtifactRegistration`、`ArtifactTextExcerpt`：登记 Case 内不可变产物并返回可续读的有界 UTF-8
  片段；Artifact ID 在整个 Case 内唯一；
- `RunOutcomeSummary`、`TargetFailureDiagnostic`、`AgentFailureDiagnostic` 及正交结果枚举。

Baseline 稳定性只使用专用 `BaselineStabilityState`；Case/多轮对话不使用统一生命周期状态机。

类型在构造时校验不变量。ID 在 JSON 中序列化为字符串；产物路径统一使用 `/` 分隔的相对路径；
所有集合执行防御性复制。

## 依赖边界

主代码只依赖 `jackson-annotations`。Jackson Databind、JSR-310 和 JUnit 仅用于测试，
本模块不得依赖 `ada-core`、Adapter 或其他实现模块。

## 测试

```powershell
mvn -pl ada-contracts test
```

当前模块边界见 `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`。
