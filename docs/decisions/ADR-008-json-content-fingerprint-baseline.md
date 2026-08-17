# ADR-008：Baseline 使用 JSON 内容指纹，不实现字段级 Gantt Diff

- 状态：Proposed
- 日期：2026-08-17

## 背景

目标算法 UT 每次在同一输出目录生成以运行时间命名的 `.json` 文件。真实检查表明最近五次相同 UT
生成的文件名不同，但文件大小均为 102,603 字节，原始内容 SHA-256 均为
`cd09cdb200821c47e6fb464274bd36c317245b4026e37999d27ed9614dc4cb4d`。文件名不参与文件内容 SHA-256，
因此当前 Baseline 只需要判断本次合法 JSON 内容是否与参考 Run 一致。

上一版 Proposed ADR 计划引入通用调度结果投影、类型化扩展属性和条目级 Diff。它能说明具体字段变化，
但当前实际需求只要求大模型明确知道“相同或变化”；变化后大模型可以按需读取两份原始 Gantt。提前
实现投影和 Diff 会增加大量契约、Adapter 映射和预算规则，没有当前收益。

## 决策

1. Harness 继续计算原始文件 `rawSha256`，它只用于 Artifact 完整性校验，不包含文件名或路径。
2. Harness 另用 Jackson 流式解析 JSON，对 Token 类型和值计算 `normalizedJsonSha256`。JSON Token 之间
   的空格、缩进和换行不参与 Hash；字符串值内部的空格、对象成员顺序、数组顺序和值仍参与 Hash。
3. 不通过正则或字符串替换直接删除全部空白，避免把 `"A B"` 和 `"AB"` 错误视为相同。
4. Baseline 的 Gantt 一致性只比较 `normalizedJsonSha256`：相同为 `MATCHED`，不同为 `CHANGED`。
5. 无 Gantt 的目标失败使用现有 `TargetFailureDiagnostic` 字段生成简单失败指纹；只有 Agent 自身失败且
   没有可信目标观察时不建立参考。
6. 每个 Run 追加一个小型 `run-result-fingerprint.json`；每个 Context 首次有效无采集 Run 的同一记录
   原子复制为 write-once `reproduction.json`。不引入复杂 Baseline 状态机。
7. 当前不生成 operation/资源/字段级 Diff，不实现 `gantt-analysis` 生产代码，也不要求 Adapter 把业务
   DTO 投影为通用模型。
8. Wafer Demo Adapter 继续负责项目识别、输出目录定位和候选结果解析，不负责 Baseline Hash 或比较。
9. 同 Context 后续 Run 与本 Context 参考比较；新 Context 首次 Run 可与最近旧 Context 参考比较，但只
   报告内容是否变化和两份 Artifact 引用，不解释变化原因。

## 影响

- 删除上一版 Proposed 设计中的 `ScheduleResultProjection`、`ScheduleEntryProjection`、
  `SemanticScalar`、Projector、Hasher、Differ 和字段级 Diff Schema；
- 当前未发布的 Adapter `SemanticHashStrategy<T>` 被通用 JSON Token 内容 Hash 取代，Wafer 专属 Hash
  实现和测试同步删除；
- `gantt-analysis` 保持空骨架，出现真实字段级查询需求后再单独设计；
- 原始文件字节完全相同时，`rawSha256` 和 `normalizedJsonSha256` 都相同；只有格式空白变化时，原始
  Hash 不同但归一化 Hash 相同；
- 内容 Hash 变化只证明结果不同，不证明算法错误或根因；LLM 仍需结合原始 Artifact、源码和后续采集
  分析。

## 被否决方案

- **只比较时间戳文件名**：每次运行必然不同，不能表示结果一致性；
- **删除文本中的所有空格和换行**：会破坏 JSON 字符串值并产生假相同；
- **直接使用原始 SHA 作为唯一比较值**：当前数据可用，但无法容忍仅缩进/换行变化；增加流式 Token
  Hash 的成本很小；
- **通用业务投影和字段级 Diff**：能力更强但当前没有使用证据，按 YAGNI 后置；
- **每个 Adapter 自定义 Hash**：会把简单内容一致性重新绑定到具体业务 Adapter。
