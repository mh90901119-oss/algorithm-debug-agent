# 可移植 Agent 边界简化最终审计

- 审计日期：2026-09-29
- 审计范围：Agent Profile、宿主 Adapter、可选知识、Conclusion Finalization、MCP 发布包及同步文档
- 关联设计：[可移植 Agent 边界收敛与功能闭环可实施详细设计](../designs/2026-09-29-portable-agent-boundary-simplification-design.md)
- 关联计划：[Portable Agent Boundary Simplification Implementation Plan](../superpowers/plans/2026-09-29-portable-agent-boundary-simplification.md)
- 审计结论：通过；可以提交和推送当前分支

## 1. 结论

本次修改没有增加第 18 个 Tool、第二个模型循环、数据库、消息队列或目标源码插桩。运行时仍由 17 个 MCP Tool、
统一 Dispatcher、Coordinator、追加式归档和 Conclusion Gate 构成。修改收敛了三个外围问题：

1. Agent Definition 不再复制 Tool 清单；Capability Manifest 是宿主工具权限的唯一发布快照，Java Catalog 仍是
   运行时真源，两者由打包测试强制相等。
2. 可选知识不再是空悬路径；Adapter 在安装时执行稳定排序、严格 UTF-8、符号链接拒绝和四类硬预算，并把
   provenance 与正文追加为 `KNOWLEDGE_HINT`。知识不进入 Evidence、Predicate 或 Conclusion Gate。
3. `analysis_finalize` 不再只返回 Decision 后要求模型另造完成 JSON；服务端返回
   `ConclusionFinalization(candidate, decision)`，并强制结论 ID、Analysis identity、控制修订及原 Candidate 一致。

Profile 从 1.0 显式升级到 2.0。17 个 Tool 名称和输入、Case/Run/Trace/Evidence 布局、成功/失败 UT 证据资格、
CodePath/JDWP 采集语义均未改变。

## 2. 需求—实现—测试追踪

| 目标 | 实现位置 | 关键测试 | 结果 |
|---|---|---|---|
| 删除重复 Tool 权限与空悬宿主字段 | `agent-definition/algorithm-debug-agent-v1.json`、Agent Schema、Host Renderer | `agent-definition.test.mjs`、`McpPackagingTest` | 通过 |
| Manifest 与 Java Catalog 不漂移 | `McpToolCatalog`、Capability Manifest | `McpPackagingTest`、`McpToolCatalogTest` | 通过 |
| 零知识等价、知识实际可用且有界 | `render-host-config.mjs` | Renderer/Installer 缺失、空目录、排序、UTF-8、符号链接、四类预算、原子保留测试 | 通过 |
| 服务端拥有唯一完成结果 | `ConclusionFinalization`、`CoreActionHandlers` | `ConclusionFinalizationTest`、`CoreActionRegistryTest`、`CoordinationSchemaTest` | 通过 |
| 返回原样提交的 Candidate | `AnalysisActionPolicies.analysisFinalize` | `AnalysisFinalizeActionPolicyTest` | 通过 |
| Finalize 输出有严格 Schema | `conclusion-finalization-v1.schema.json`、`McpToolCatalog` | `McpToolCatalogTest`、`McpPackagingTest` | 通过 |
| 合法 Finalization 不被通用结果截断 | `McpServerLimits`、`McpResultMapper` | `McpResultMapperTest` | 通过 |
| Qwen 安装资产与 MCP 权限一致 | Qwen Adapter 与生命周期脚本 | Node Installer Suite、TestProfile install/check/uninstall | 通过 |

## 3. Red-Green 证据

- Profile/知识/Finalization 的首轮 Node 与 Java 测试在旧实现上分别因 Profile 1.0 字段、知识正文未注入和
  Finalization 类型缺失而失败；实现对应最小闭环后转绿。
- 深度审计新增“返回对象必须包含请求中的原 Candidate”回归测试；旧后置条件只比较 identity，测试先返回
  `ALLOWED` 而失败。加入 Candidate 全值相等校验后通过。
- 深度审计新增“请求预算大小的数据必须完整往返”回归测试；旧 768 KiB 结果预算会截断，测试先失败。
  结果上限改为 `1 MiB 请求预算 + 512 KiB Coordinator 响应预算` 后通过；超过 1.5 MiB 的通用结果仍有界截断。

这些修复没有通过削弱断言、删除测试或修改无关 golden 数据取得绿色结果。

## 4. 最终验证

| 命令 | 结果 |
|---|---|
| `mvn -Pcodepath-launcher test` | 22 个 Reactor 模块 `BUILD SUCCESS`；最新 clean 构建报告合计 1432 tests、0 failures、0 errors、11 skipped |
| `node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs` | 90/90 通过 |
| `scripts/build-agent.ps1` | `AGENT_BUILD_OK`；生成发布清单并校验 MCP/CLI/CodePath/JDWP 产物 |
| Qwen `install.ps1 -Scope TestProfile` | `QWEN_ADAPTER_INSTALL_OK` |
| Qwen `check.ps1 -Scope TestProfile` | `QWEN_ADAPTER_CHECK_OK` |
| Qwen `uninstall.ps1 -Scope TestProfile` | `QWEN_ADAPTER_UNINSTALL_OK` |
| `git diff --check` | 通过；仅报告仓库既有 Windows 行尾转换提示，无空白错误 |
| JSON 解析与 Schema/打包测试 | Definition、Manifest、Agent Schema、Finalization Schema 全部有效 |
| 变更差异扫描 | 未新增 TODO/FIXME/HACK、开发机绝对路径、凭据模式、`System.out` 或 `printStackTrace` |

Maven 的 11 个 skipped 属于仓库既有条件化测试，不是本次失败或临时禁用。构建日志中的 SLF4J provider、
Byte Buddy 动态 Agent 及 Shade 重复资源提示为既有非失败告警，未改变产物校验结果。

## 5. 架构和代码质量审计

- 高内聚：知识读取只在 Host Adapter；Finalization 契约只在 contracts；Gate 包装只在 core；MCP Schema 组合只在
  MCP Catalog。没有把宿主知识逻辑放入 Collector，也没有让 Adapter 依赖 `ada-core` 内部类型。
- 低耦合：宿主只消费 Definition、Manifest 和标准 MCP；运行时 Tool 完整性由枚举、Catalog、Registry、Manifest
  四者的测试关系锁定，而不是复制业务 Handler。
- 确定性：目录和文件排序稳定；知识超限明确失败而不静默截断；Finalization 校验全值 Candidate、身份和修订；
  Gate 的 `ALLOWED/REJECTED` 仍是领域结果，不伪装为协议错误。
- 有界性：知识默认 32 文件/4 层/64 KiB 单文件/256 KiB 总量，Schema 硬上限为 64/8/256 KiB/1 MiB；MCP
  请求为 1 MiB，结构化结果为 1.5 MiB。所有数值都有命名常量或版本化 Schema，不存在新增魔鬼数字。
- 可恢复：Case、Candidate 和 Decision 继续追加保存；知识安装失败保留既有安装；原始证据没有被改写。
- 兼容性：Profile 2.0 明确表达宿主契约破坏性简化；历史 Case 和协调归档不迁移；Qwen 通过重装生成新资产。

## 6. 已知边界

- 本次结构验证证明门禁不可绕过、输入输出有界、归档可追溯，但不把它宣称为“大模型根因准确率已提升”。
  真实复杂算法的重复收敛率仍需使用固定模型/Prompt/工具版本的机制 Eval 量化。
- 当前只有 Qwen CLI 完成真实 Adapter 生命周期验证。第二个真实宿主仍需其正式配置契约和可执行环境；仓库没有
  为未知 DeepSeek Harness 猜测实现。
- Java 可以限制可确认的结构化事实，不能完全阻止宿主模型在自然语言措辞中犯错；Canonical Prompt 要求只解释
  最后一个 Finalization 与 `case_audit`，最终仍需 Eval 监控虚假关联率。

上述限制是阶段 8 已声明的外部验证项，不形成当前实现的代码空悬或降级路径。

## 7. 回滚

如发布后发现宿主 Profile 2.0 不兼容，整体回退本次提交并重装旧 Adapter。不要只恢复 Completion Contract 或
只撤销 Finalization 包装，否则会重新形成双重完成语义。Case、Run、Evidence、Candidate 和 Decision 均为追加式
版本化产物，回滚不删除或重写已有 Workspace。

## 8. 提交范围

本次提交应包含代码、测试、Schema、Agent Definition/Prompt、Adapter、构建脚本、同步文档和构建生成的受管
JDWP Collector JAR。用户已有且未跟踪的 `docs/sharing/` 明确排除，不修改、不暂存、不提交。
