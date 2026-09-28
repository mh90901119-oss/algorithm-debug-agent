# Algorithm Debug Agent 当前开发计划

更新日期：2026-09-28。

## 目标

把现有宿主专用工具链演进为 Java 原生、宿主无关的 MCP Algorithm Debug 子 Agent：模型提出问题框架和竞争假设，
确定性代码执行有界源码查询、CodePath/JDWP 采集、三值观测评估、反证保留和因果结论门禁，最终输出可追溯且
标明证据等级的结论。

## 已完成阶段

| 阶段 | 状态 | 交付 |
| --- | --- | --- |
| 1. Case 与 Run | 完成 | 追加式 Case/Analysis/Run、Maven/JUnit、失败指纹、原名 Gantt |
| 2. OpenCode 集成 | 完成 | Agent、Skill、Command、13 个 Tool、CLI Adapter、安装/卸载 |
| 3. 静态与 CodePath | 完成 | 方法目录、调用关系、精确方法路径、Raw/Derived/Validation |
| 4. JDWP | 完成 | Agent-owned Collector、断点、局部变量、字段、有界预算、基线校验 |
| 5. 输入优先因果工作流 | 完成 | 唯一输入识别、Case 级原名复用、Plan 意图和 Evidence 谱系、条件化 JDWP |
| 6. 复杂因果 Eval | 完成 | 10-Case Smoke、跨实体因果 Case、确定性 Grader |
| 7. OpenCode 文档与端到端审计 | 已形成迁移基线 | 当前文档、自动测试、安装生命周期、Workspace/日志审计 |
| 8. 证据约束 MCP 可移植子 Agent | 执行中 | 17 Tool、Source Query、Investigation Runtime、Coordinator、MCP Server、Qwen/第二宿主、收敛 Eval |

## 阶段 8 实施依据

- 设计：`docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md` 0.4。
- 决策：`docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`。
- 基线：`docs/development/evidence-constrained-mcp-implementation-baseline.md`。
- 计划：`docs/superpowers/plans/2026-09-28-evidence-constrained-portable-mcp-subagent.md`，它是当前唯一可执行计划。

旧 0.3 计划已失效，不得通过恢复旧文件或向旧 Task 零散插入补丁继续使用。

## 阶段 8 完成标准

1. 17 个基线 MCP Tool 从唯一 Catalog 暴露，所有模型动作强制经过 Coordinator。
2. `source_query`、调查账本、冻结 Predicate、三值 Evaluation、反证和 CausalChain 结论门禁闭环。
3. 成功 UT 没有普通 Run 时动态证据不被误丢弃；失败 UT 只有匹配失败指纹可用于确认同类失败。
4. CLI 与 MCP 共用唯一 Runtime；Case、Operation、Investigation 和 Conclusion 可追加恢复且跨进程执行互斥。
5. 无知识目录也能完成闭环；知识文件只能帮助模型理解，不能绕过 Evidence/Predicate/Conclusion 门禁。
6. Qwen CLI 和一个真实第二宿主运行同一 MCP Catalog、Agent Definition 和 Eval Suite。
7. Maven、Node、MCP Contract、7 条 E2E、10 类机制重复收敛 Eval、性能和故障注入均达到设计阈值。
8. 最终审计记录 requirement-to-code-to-test 映射、命令、结果、偏差、限制和回滚方式。

## 后续候选项

后续工作只能由真实目标算法 Eval 的失败证据驱动：

- 提升复杂多态或外部依赖调用的静态解析覆盖，但不追求无界全程序分析。
- 扩展通用 JSON 有界访问，避免向 LLM 发送过大输入或 Gantt。
- 扩展 JDWP 通用值类型或路径表达能力，但不执行任意表达式。
- 增加真实复杂因果 Eval 样本和错误假设拒绝样本。

暂不实施领域 Gantt 语义引擎、固定采集轮数、自动修改目标源码、生产调度接管或无需求支撑的新模块。
