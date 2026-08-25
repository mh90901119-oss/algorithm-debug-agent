# Agent Runtime Simplification and End-to-End Audit Implementation Plan

- 状态：Completed
- 日期：2026-08-25
- 设计：[运行时精简设计](../../designs/2026-08-25-agent-runtime-simplification-and-audit-design.md)
- 审计：[最终实施审计](../../audits/agent-runtime-simplification-final-audit.md)

## 1. 实施原则

- 保持 OpenCode Agent + Skill + Tool + JS Adapter + Java CLI/Core + Workspace 架构。
- LLM 决定分析步骤和业务解释，代码只做确定性执行。
- 不修改目标算法生产源码。
- 不以命令参数传递机器路径；安装器统一读取仓库配置。
- 不创建没有当前消费者的模块、文件或目录。
- 所有真实 E2E 同时验证答案、工具调用、Workspace 和交互日志。

## 2. 已完成任务

### Task 1：建立当前设计和审计边界

- [x] 明确单算法模块、单 Maven/JUnit 5 目标 UT。
- [x] 明确正常、目标不存在、异常、断言失败和工具失败分支。
- [x] 明确 Gantt 语义只由 LLM 解释。
- [x] 记录 SHA producer、consumer、mismatch 和 LLM 可见行为。

### Task 2：统一路径配置和安装

- [x] 使用 `config/agent-settings.json` 保存默认值和手工可修改项。
- [x] 安装器解析并打印 OpenCode、Workspace、Gantt、DFX 和 Eval 路径。
- [x] Gantt 输出允许绝对路径。
- [x] 删除 OpenCode 版本锁定，仅在实际命令不兼容时明确失败。
- [x] JDWP Collector 从 Agent 安装目录解析。

### Task 3：删除空模块、死代码和占位目录

- [x] 删除 `agent-evaluation`、`explanation-reporter`、`gantt-analysis`、`knowledge-engine`。
- [x] 删除旧 Baseline 类型、无消费者的 Store/Resolver 和 `.gitkeep`。
- [x] Workspace 子目录改为按文件懒创建。
- [x] 删除仓库空 `.agents` 目录。

### Task 4：统一 ArtifactReference

- [x] 所有归档文件登记相对路径、size 和 SHA。
- [x] 读取、Gantt 查询和 Case 审计共用完整性检查。
- [x] 删除 Manifest 中 Plan/Raw 的重复 SHA。
- [x] 损坏文件返回稳定完整性问题并阻断引用。

### Task 5：删除无闭环 SHA

- [x] 删除 Source whole-file SHA。
- [x] 删除 POM SHA。
- [x] 删除 CodePath/JDWP Plan SHA。
- [x] 删除 Collector JAR SHA。
- [x] 删除 Gantt raw/normalized SHA 门禁。
- [x] 保留失败事实指纹和 ArtifactReference SHA。

### Task 6：收窄动态基线

- [x] 成功目标 Collection 使用 `NOT_COMPARED`。
- [x] 失败目标比较结构化失败指纹。
- [x] `MATCHED` 才允许动态证据确认同类失败。
- [x] `CHANGED/INCOMPARABLE` 明确降级为线索。

### Task 7：静态分析和缺失 UT

- [x] 保留当前源码 Javac AST MethodCatalog。
- [x] 行范围只用于本次导航和计划候选。
- [x] 找不到 selector 返回 `TARGET_TEST_NOT_FOUND`。
- [x] OpenCode 缺失 UT 用例不执行 run_test、CodePath 或 JDWP。

### Task 8：CodePath/JDWP 采集

- [x] 两个工具独立执行目标 UT。
- [x] 计划预算、Raw、Normalizer、Validator 和 Evidence 完整归档。
- [x] JDWP completion 与 Collector Manifest 一致。
- [x] ToolResponse 使用 `collectorExecutionRunId` 消除 Case Run 歧义。
- [x] 删除 JDWP Plan 重复 Artifact 登记，保留独立 `collector-plan.json`。

### Task 9：有界读取和 Case 审计

- [x] 实现 `gantt_inspect`，只输出结构和有界内容。
- [x] 实现 `case_audit`，按动作状态推导 expected files。
- [x] 审计 Artifact、缺失、异常文件和空目录。
- [x] 明确零字节 stderr 是有语义的进程流证据。

### Task 10：OpenCode、Skill 和 DFX

- [x] Skill 定义先运行/分析目标 UT、按证据选择工具和停止条件。
- [x] Collection ID 字段对 LLM 消歧。
- [x] DFX 记录工具开始/结束、状态、耗时、错误码和产物。
- [x] Case 根目录保存可直接打开的 `interaction.jsonl`。
- [x] JS Adapter 不做业务语义。

### Task 11：Eval Harness

- [x] Suite/Case 可版本化。
- [x] Runner 启动真实 OpenCode 会话。
- [x] Parser 按 `part.callID` 合并重复状态快照。
- [x] Grader 校验工具序列、答案、Workspace、交互和动态状态。
- [x] Report Writer 输出 summary、result、case-review 和日志。
- [x] Eval 场景预算与产品工作流分离。

### Task 12：九个真实 E2E

- [x] passing-ut
- [x] missing-ut
- [x] missing-input
- [x] algorithm-loop-guard
- [x] assertion-failure
- [x] static-current-source
- [x] codepath-independent
- [x] jdwp-independent
- [x] artifact-integrity-rejection

### Task 13：验证和文档

- [x] 根 Maven 测试。
- [x] Node/OpenCode/Eval 测试。
- [x] 安装器重复安装和 Check。
- [x] 每个 E2E 的 expected/actual、空目录和 interaction audit。
- [x] README、工作流、能力、架构、ADR、Eval 和最终审计同步。

## 3. 最终验收规则

1. 普通用例必须 `workspaceAuditPassed=true` 和 `interactionAuditPassed=true`。
2. 故意损坏用例必须只产生预期完整性问题，并被 Eval 判定为 PASS。
3. 动态用例必须保存 Plan、Collection、Raw、Derived、Validation 和 Evidence。
4. 不要求未执行动作的目录或文件。
5. 不允许空目录、占位文件、重复 Plan Artifact 或无法解释的 SHA。
6. LLM 最终答案必须引用已通过完整性校验的事实，并区分推断和缺失证据。

## 4. 交付内容

- 当前实现和测试；
- 安装器及仓库路径配置；
- 12 个 OpenCode Tools；
- Case-local interaction 日志和全局 DFX；
- 9 个真实 E2E 的审计报告；
- 当前模块、交互流程、Case 文件和限制的最终审计。
