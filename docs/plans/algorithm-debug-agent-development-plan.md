# Algorithm Debug Agent 当前开发计划

更新日期：2026-09-01。

## 目标

通过 OpenCode 分析一个明确的 Java/Maven 算法 UT：先理解唯一算法输入和当前源码，再用最小 CodePath/JDWP 证据验证因果链，最终输出可追溯且标明证据等级的结论。

## 已完成阶段

| 阶段 | 状态 | 交付 |
| --- | --- | --- |
| 1. Case 与 Run | 完成 | 追加式 Case/Analysis/Run、Maven/JUnit、失败指纹、原名 Gantt |
| 2. OpenCode 集成 | 完成 | Agent、Skill、Command、13 个 Tool、CLI Adapter、安装/卸载 |
| 3. 静态与 CodePath | 完成 | 方法目录、调用关系、精确方法路径、Raw/Derived/Validation |
| 4. JDWP | 完成 | Agent-owned Collector、断点、局部变量、字段、有界预算、基线校验 |
| 5. 输入优先因果工作流 | 完成 | 唯一输入识别、Case 级原名复用、Plan 意图和 Evidence 谱系、条件化 JDWP |
| 6. 复杂因果 Eval | 完成 | 10-Case Smoke、跨实体因果 Case、确定性 Grader |
| 7. 文档与端到端审计 | 执行中 | 当前文档收敛、全测试、安装生命周期、真实 OpenCode E2E、Workspace/日志审计 |

## 阶段 7 完成标准

1. 文档只引用当前有效文件和当前归档布局。
2. Java、OpenCode Adapter、Eval Harness 全部自动测试通过。
3. Agent 可构建、卸载、重新安装并通过能力发现。
4. Launcher 和 JDWP loopback 验证通过。
5. 10 个真实 OpenCode Smoke Case 完成，逐项审计 Workspace 和日志。
6. 最终审计记录命令、结果、产物、限制和未解决风险。

## 后续候选项

后续工作只能由真实目标算法 Eval 的失败证据驱动：

- 提升复杂多态或外部依赖调用的静态解析覆盖，但不追求无界全程序分析。
- 扩展通用 JSON 有界访问，避免向 LLM 发送过大输入或 Gantt。
- 扩展 JDWP 通用值类型或路径表达能力，但不执行任意表达式。
- 增加真实复杂因果 Eval 样本和错误假设拒绝样本。

暂不实施领域 Gantt 语义引擎、固定采集轮数、自动修改目标源码、生产调度接管或无需求支撑的新模块。
