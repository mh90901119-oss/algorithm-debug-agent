# 2026-09-04 全仓一致性审计

## 范围

本次按 Custom Tool、Java CLI、应用服务、Workspace、Collector、Normalizer、Validator、
Evidence、安装器和现行文档的实际调用方向执行审计。历史 ADR 与已完成计划只作为决策记录，
不要求改写成当前时态。

## 已修复问题

| 级别 | 模块 | 问题 | 修复 |
|---|---|---|---|
| P1 | OpenCode Runtime | 参数校验抛出未结构化 JS 异常，模型无法按 ToolResponse 决策 | 统一返回 `ADA_TOOL_INPUT_INVALID` |
| P1 | Evidence | 零个当前 Collection 仍可获得 Validation 覆盖；截断 Bundle 可被判充分 | 取消虚假覆盖，截断时移除有效 Validation 覆盖 |
| P1 | JDWP | 生命周期事件和快照共用 `maxEvents`，满预算时可能导致 Normalizer 误判 | `maxEvents` 统一为快照数，Normalizer 固定预留两条生命周期记录 |
| P1 | JDWP | 缺失命中计数被旧兼容逻辑补值，可能把损坏 Manifest 当成有效 | 必需计数缺失即拒绝，并校验 `captured <= matched <= observed` |
| P2 | Workspace | 首次原子写失败可能残留空目录 | 统一父目录托管写入，仅清理本次新建的空目录 |
| P2 | Workspace Audit | 已知控制文件只检查存在，不检查 JSON 与路径身份 | 增加有界 Schema/Case/Analysis/Run/Collection/Evidence 身份审计 |
| P2 | JDWP | Collector 可接受非 loopback、过大计划、静态字段和错误 UTF-16 `char` | 收紧为确定性安全边界 |
| P2 | CodePath | Trace Sink 首次 I/O 失败后仍持续格式化和重试 | 首次失败后停止采集并保留原错误 |
| P2 | Maven | 若干模块直接使用传递依赖 | 补充实际直接依赖，不添加 Dependency Analyzer 的 JUnit 噪声项 |
| P3 | 文本契约 | 错误消息存在拼写或重复词 | 与当前实际行为统一 |

## 明确保留的边界

- 不增加文件锁、跨会话协调、异步写盘或 Collector 队列。
- 不让 Java 代码解释算法输入或 Gantt 的业务语义。
- 不把静态 Method Catalog 宣称为完整调用图。
- 不把 Artifact SHA、Plan 身份或失败指纹扩大成业务正确性证明。
- JDWP Raw 字节上限继续由协调器监控，Normalizer 做确定性二次拒绝；本次不复制一套并发预算系统。
- 安装器继续从仓库运行 Java 后端，不绑定 OpenCode 版本，不修改目标算法 POM。

## 验证门禁

实施后执行受影响模块测试、根 Reactor 测试、Node 契约测试、PowerShell 语法检查、构建、
安装/卸载隔离验证、JDWP loopback、Smoke/Quality Eval，并抽查每个真实 Case 的控制文件、
Artifact、交互日志、Java 日志、空目录和 `case_audit` 结果。实际命令与结果在交付说明中记录。

## 验证中追加修复

- Case 审计只对版本化有界控制 JSON 执行 Schema 与路径身份校验；`collection-summary.json` 继续参与已知文件审计，但不伪装成版本化契约。
- 所有成功创建 Case 的退出路径都必须在最终回答前执行 `case_audit`；“停止”只表示停止后续 UT 或动态采集。
- Eval Grader 接受明确列出实际能力的 `Workflow used` 表述，不再要求固定口令。
- `evidence_query` 的参数说明明确限定 `CODEPATH_INVOCATIONS` 和 `JDWP_SNAPSHOT_SUMMARY`；错误恢复消息会引导其它 Artifact 使用 `artifact_read`，并给出正确的输出预算恢复动作。

## 实际验证结果

- `mvn -Pcodepath-launcher test`：20 个 Reactor 模块全部通过。
- OpenCode/Eval Node 契约测试：全部通过；最终数量以交付时最后一次命令输出为准。
- PowerShell AST：9 个仓库脚本全部通过。
- `scripts/build-agent.ps1`：通过，CLI、CodePath Launcher 和 JDWP Collector 均完成打包。
- 实际卸载、安装与 `-Mode Check`：通过，继续使用仓库源码后端，没有写死目标算法模块路径。
- `scripts/verify-opencode-installer.ps1`：通过重复安装、检查、卸载和重装隔离验证。
- `scripts/verify-jdwp-loopback.ps1`：loopback attach 与采集验证通过。
- Smoke：首轮 9/10；唯一失败是缺失 UT 路径漏调用 `case_audit`。Skill 修复后单独重跑该 Case 为 1/1，因此 10 种 Smoke 场景均已有真实通过记录。
- Quality-50：完整运行得到 46/50。`jdwp-01` 的答案实际列出了完整能力但被旧 Grader 误判，评分规则已增加回归测试；`causal-03` 在执行中收到 DeepSeek `402 Insufficient Balance`，`causal-04` 和 `causal-05` 随后无法启动。单独重跑 `causal-03` 再次确认同一 402，因此这 3 个场景属于外部模型余额阻塞，不能宣称通过。
- Quality Workspace：48 个已启动 Case 的交互审计全部通过且无空目录；5 个 `integrity-*` Case 按预期因人工篡改 Artifact 被 `case_audit` 拒绝；其余已完成 Case 的 Workspace 审计通过。

## 剩余验证条件

DeepSeek 余额恢复后，需要单独重跑 `quality-50` 的 `causal-03`、`causal-04`、`causal-05`。这是外部验证缺口，不是已知 Agent 代码失败；在真实报告通过前仍不得把 Quality-50 标记为全绿。