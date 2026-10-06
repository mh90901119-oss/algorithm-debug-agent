# Conclusion Contract v2 迁移说明

## 变更原因

`ConclusionCandidate v1` 只保存 `causalChainIds[]`，但分析生命周期没有提交或解引用这些 ID 的确定性入口，
因此 `ConclusionGate` 无法实际校验模型声称的因果链。`ConclusionDecision v1` 也没有计划要求的
`allowedActions[]`，拒绝后只能依赖自然语言猜测下一步。

## v2 契约

- `ConclusionCandidate.schemaVersion` 升级为 `2.0`，用有界 `causalChains[]` 替代 `causalChainIds[]`。
  每条 Chain 必须与 Candidate 属于同一 Case/Analysis，ID 在候选内唯一。
- `ConclusionDecision.schemaVersion` 升级为 `2.0`，新增必填 `allowedActions[]`。
- v1 JSON Schema 保留在原路径，只用于识别和审计历史文档；v2 使用独立 `*-v2.schema.json`，不会用新语义
  覆盖旧 Schema。

## 调用方迁移

1. 读取 v1 Candidate 后，按其中 `causalChainIds[]` 找回原 Chain；若历史系统未保存 Chain，则不能无损升级，
   必须让模型基于已归档 Evidence 和 Source Query 重新提交 v2 Candidate。
2. v2 Candidate 必须内嵌完整 Chain，Claim 的 `sourceReferenceIds[]` 必须引用其中节点或边。
3. v1 Decision 可继续作为历史只读产物展示；新的 finalize 调用必须生成 v2 Decision。
4. v2 拒绝结果应直接使用 `allowedActions[]` 选择补证动作，不再解析自然语言提示。

迁移不修改或覆盖任何 v1 产物。新 Gate 只接受 v2 typed contract，避免把缺失的 Chain 猜测补齐。
