# ada-contracts

当前契约新增 `RunOutcomeSummary`，用独立维度表达进程、测试、Gantt、目标失败、Agent 失败和基线比较。
异常仅保留通用事实，不在契约层推断算法业务根因。`ToolResponse` 2.0 删除固定的
`nextAllowedActions`，后续动作由大模型依据结构化事实与版本化 Skill 决策。

Algorithm Debug Agent 的稳定基础契约模块。它定义跨模块、跨进程和落盘 JSON 共用的不可变类型，
不负责文件读写、Agent 编排、工具调用或晶圆调度业务语义。

## 当前已实现

- Schema 版本常量；
- `ProjectId`、`CaseId`、`InquiryId`、`TurnId`、`RunId`、`AnalysisId`、`EvidenceId`；
- `TargetTest`；
- `ExecutionIdentity`；
- `ArtifactReference`；
- `BaselineManifest`；
- `ToolResponse<T>`。

类型在构造时校验不变量。ID 在 JSON 中序列化为字符串；产物路径统一使用 `/` 分隔的相对路径；
所有集合执行防御性复制。

## 依赖边界

主代码只依赖 `jackson-annotations`。Jackson Databind、JSR-310 和 JUnit 仅用于测试，
本模块不得依赖 `ada-core`、Adapter 或其他实现模块。

## 测试

```powershell
mvn -pl ada-contracts test
```

详细设计见 `docs/designs/2026-08-10-ada-contracts-phase0-design.md`。
