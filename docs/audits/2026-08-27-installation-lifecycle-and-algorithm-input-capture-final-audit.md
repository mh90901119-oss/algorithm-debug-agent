# OpenCode 安装生命周期与算法输入捕获最终审计

日期：2026-08-27

## 1. 审计结论

本轮实施完成并验证了以下目标：

1. 仓库、脚本、代码与面向用户文档不再使用特定组织环境作为产品概念，统一改为“目标算法”“目标模块”或“目标环境”。
2. 安装器不包含 Demo 模块路径，不限制 OpenCode 版本；用户可编辑路径集中在 `config/agent-settings.json`，安装时打印最终生效值。
3. 安装和卸载以安装所有权清单为边界。卸载只删除本安装器拥有且内容未被外部修改的文件，支持修改 Agent 后安全重装。
4. 分析开始后必须先确定性定位并复制目标 UT 的唯一算法输入，再允许运行 UT、创建 CodePath Plan 或创建 JDWP Plan。
5. 同一 Case 多轮分析按输入内容比较 `FIRST_CAPTURE`、`UNCHANGED`、`CHANGED`，历史输入和历史分析均追加保存，不覆盖。
6. OpenCode DFX 日志在存在时严格审计；直接 Java CLI、关闭 DFX 或 Recorder 失败时，缺少 `interaction.jsonl` 不再误判 Case 业务档案损坏。
7. Eval Harness 检查真实工具顺序：输入捕获必须在首个 Run 前完成；首个 Run 必须早于针对当前执行事实的静态分析和动态采集。

未发现阻止当前目标场景使用的遗留缺陷。

## 2. 算法输入能力契约

### 2.1 支持范围

当前只支持目标测试方法第一层代码中的一个直接局部变量声明：

```java
String inputPath = "path/to/case-input.json";
```

约束如下：

- 类型必须是 `String` 或 `java.lang.String`。
- 初始化表达式必须是直接字符串字面量。
- 字符串值必须以 `input.json` 结尾，不区分大小写。
- 路径可以是绝对路径，也可以是相对目标模块根目录的路径。
- 候选必须恰好为一个，且解析后的文件必须存在、是普通文件并位于复制字节预算内。

不扫描 helper 方法、分支内部变量、字段、常量引用、字符串拼接或运行时计算表达式。这是明确的产品边界，不由 LLM 猜测补全。

### 2.2 停止条件

以下情况在运行 UT 前停止：

- 目标测试不存在：`TARGET_TEST_NOT_FOUND`。
- 没有符合契约的输入：`ALGORITHM_INPUT_NOT_FOUND`。
- 表达式不是直接字符串字面量：`ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED`。
- 存在多个候选：`MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED`。
- 文件缺失、不是普通文件、超限或复制失败：返回对应结构化错误码。

停止后不创建 Run、CodePath Plan 或 JDWP Plan。OpenCode/LLM 只能向用户说明不支持原因，不能绕过前置检查继续分析。

### 2.3 多轮比较

每轮捕获完成后计算归档文件的 SHA-256，并与同一 Case 上一轮已验证输入比较：

- `FIRST_CAPTURE`：该 Case 没有历史输入。
- `UNCHANGED`：本轮与上一轮内容相同。
- `CHANGED`：内容不同。
- `INCOMPARABLE`：历史引用缺失或完整性校验失败。

SHA 只用于确认复制文件完整性和判断输入内容是否变化，不用作 Gantt 动态证据门禁，也不因文件名变化而改变内容比较结果。LLM 从 `input-analysis.json` 直接读取比较结论，不自行计算 SHA。

## 3. Workspace 文件职责

每个 Case 只创建实际发生行为所需要的文件，不预建空目录。

| 路径 | 作用 | 何时创建 |
|---|---|---|
| `case.json` | Case 身份、目标 UT、Adapter 与创建时间 | `analysis_begin` 首次创建 Case |
| `contexts/<contextId>/context.json` | 当前可复用执行上下文身份 | 创建或显式刷新 Context |
| `analyses/<analysisId>/analysis-request.json` | 本轮问题与 Case/Context 关联 | 每轮分析开始 |
| `analyses/<analysisId>/input/input-analysis.json` | 源码位置、解析路径、ArtifactReference、比较状态 | 唯一输入成功复制后 |
| `analyses/<analysisId>/input/algorithm-input.json` | 本轮不可变算法输入副本 | 唯一输入成功复制后 |
| `artifacts/<analysisId>-algorithm-input.json` | 算法输入 ArtifactReference 注册记录 | 输入复制提交后 |
| `runs/<runId>/run-request.json` | 目标 UT、Analysis 与启动参数事实 | UT 子进程启动前 |
| `runs/<runId>/run-outcome.json` | 退出码、成功/异常/断言结果和归档摘要 | UT 子进程结束后 |
| `runs/<runId>/raw/*` | stdout、stderr、Surefire XML 和本次 Gantt 原始证据 | 对应原始数据存在时 |
| `artifacts/run-*.json` | Run 原始文件的 ArtifactReference | 原始文件归档后 |
| `analyses/<analysisId>/method-catalog.json` | 有界静态方法目录 | LLM 在首个 Run 后请求静态分析时 |
| `collections/<collectionId>/*` | CodePath/JDWP Plan、Raw Trace、派生证据 | LLM 判断确实需要动态值时 |
| `analyses/<analysisId>/analysis-result.json` | 最终答案、事实类型和证据引用 | `analysis_complete` 成功时 |
| `interaction.jsonl` | OpenCode Tool/CLI 时序 DFX | OpenCode Recorder 启用并成功写入时 |

`interaction.jsonl` 是可选诊断文件，不是直接 Java CLI 的业务必需文件；真实 OpenCode Eval 仍单独要求并严格校验该日志。

## 4. 真实端到端验收

### 4.1 成功 UT

- 报告：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\evals\20260826181158-550f66f3`
- 结果：PASS，56.441 秒，无超时。
- 顺序：`analysis_begin -> algorithm_input_capture -> run_test -> gantt_inspect -> case_audit -> analysis_complete`，中间只穿插证据读取。
- 输入：成功复制，源文件与归档 ArtifactReference 完整性一致。
- Run：UT 通过并归档 Gantt JSON。
- Workspace Audit：PASS，检查 5 个注册 Artifact，0 个问题。
- Interaction Audit：PASS，81 个事件，0 个问题。

### 4.2 输入缺失

- 报告：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\evals\20260826181343-024f843a`
- 结果：PASS，47.473 秒，无超时。
- 顺序：`analysis_begin -> algorithm_input_capture -> case_audit -> analysis_complete`。
- DFX：CLI 和 Tool 均记录 `ALGORITHM_INPUT_NOT_FOUND`。
- Run/Collection/输入归档：均为 0，符合提前停止契约。
- Workspace Audit：PASS，0 个问题。
- Interaction Audit：PASS，36 个事件，0 个问题。

### 4.3 算法异常

- 报告：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\evals\20260826183422-0003d739`
- 结果：PASS，137.293 秒，无超时。
- 首个 Run 早于静态分析，没有 CodePath/JDWP 过度采集。
- Run 归档 `IllegalStateException`，消息包含最大迭代次数、waferId 和触发原因，稳定栈帧指向 `SimpleWaferScheduler.scheduleWafer`。
- Workspace Audit：PASS，检查 6 个注册 Artifact，0 个问题。
- Interaction Audit：PASS，99 个事件，0 个问题。

### 4.4 断言失败

- 报告：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\evals\20260826183031-5ba2b43d`
- 结果：PASS，77.854 秒，无超时。
- Run 归档 `AssertionFailedError`，期望值 164、实际值 165，并保留 Gantt。
- 首个 Run 后才执行静态分析，没有动态采集。
- Workspace Audit：PASS，检查 7 个注册 Artifact，0 个问题。
- Interaction Audit：PASS，81 个事件，0 个问题。

以上四个 Agent Workspace 均无空目录。成功 Run 可能存在合法的零字节 `stderr.log`，表示进程没有标准错误输出，不是无意义占位文件。

### 4.5 同一 Case 三轮输入

- Workspace：`C:\Users\zhao1k\AppData\Local\Temp\ada-input-e2e-49d52def0f9f45c996ffb05cb2c3fdaa`
- Case：`case-a2fce61b-6c37-4dc5-af41-4cbeffca0e6e`
- 三轮结果：`FIRST_CAPTURE -> UNCHANGED -> CHANGED`。
- 归档：3 个 AnalysisRequest、3 个 `input-analysis.json`、3 个输入副本和 3 个 Artifact 注册记录。
- Case Audit：PASS，预期与实际 14 个文件完全一致；0 个问题、0 个空目录、0 个零字节文件。

### 4.6 多输入拒绝

- Workspace：`C:\Users\zhao1k\AppData\Local\Temp\ada-multiple-input-e2e-2a6c5982a97b412d96cc42af12fca544`
- Case：`case-760017f7-c395-442b-a225-783d35444860`
- 捕获结果：进程退出码 3，`MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED`。
- 输入归档：0。
- Run 目录：0。
- Case 文件：3 个必要控制文件。
- Case Audit：PASS；0 个问题、0 个空目录。

## 5. 构建、测试与安装验证

| 验证 | 结果 |
|---|---|
| `scripts/build-agent.ps1` | 20 个 Maven Reactor 模块全部 SUCCESS，包含单元、契约、集成和 shaded JAR 验证 |
| Contracts | 84 tests，0 failure，0 error |
| Case Management | 93 tests，0 failure，0 error，1 个平台条件 skip |
| CLI | 26 tests，0 failure，0 error |
| Case/Run 集成 | 9 tests，0 failure，0 error |
| CodePath Launcher | 15 tests，0 failure，0 error |
| 全部 Node 测试 | 48 tests，48 pass |
| Demo 默认 `mvn test` | 11 tests，0 failure，0 error |
| 安装生命周期隔离验证 | 卸载、安装、Check 全流程通过 |
| 当前真实安装 | `Install` 与 `Check` 均成功 |

当前安装输出确认：

- OpenCode 配置：`C:\Users\zhao1k\.config\opencode`
- Workspace：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\workspace`
- 算法结果：`D:\log\scheduler\${runDate}\gant`
- DFX：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\diagnostics`
- Eval：`C:\Users\zhao1k\AppData\Local\algorithm-debug-agent\evals`

## 6. 实施中发现并修复的问题

1. 旧集成测试绕过输入捕获直接运行 UT。测试夹具已增加真实输入并执行捕获，同时新增目标 UT 不存在的无 Run 验证。
2. 第一次算法异常 Eval 过度调用静态、JDWP 和任务工具并最终超时。Skill、Agent Prompt 和 Grader 已改为“先 Run，证据足够即停止”，修复后 137.293 秒 PASS。
3. 一次 Eval 在 Run 前执行静态分析。Grader 新增确定性顺序拒绝，Agent Prompt 明确首个 Run 前禁止当前事实分析；重跑顺序正确。
4. 直接 Java CLI Case 因没有 OpenCode `interaction.jsonl` 被误判审计失败。该文件现为可选；存在时仍严格验证。
5. Demo 输入文件改名后有 3 处测试引用遗漏，默认 Maven 测试发现 `FileNotFoundException`。引用统一修正后 11 项测试全部通过。

## 7. 最终静态审计

- 禁用术语扫描：0 个命中。
- 安装/运行范围 Demo 路径扫描：只有一处“验证脚本不得包含 `hellomvn`”的负向测试断言，没有运行时写死路径。
- 仓库根目录 `.opencode`：不存在。
- 仓库根目录 `opencode.json`：不存在。
- 无作用空目录 `distribution`、`examples`、`knowledge` 已删除；非生成目录空目录复查为 0。
- 安装器不限制 OpenCode 版本；命令或配置契约不兼容时返回清晰错误。
- 算法输入、Run、Collection 和最终结果都按 `caseId/runId/analysisId` 追加保存，不覆盖历史。

## 8. 保留边界与风险

- 一个 UT 多输入、间接构造路径或 helper 中隐藏输入目前明确不支持。这是为了避免 AST 推断过度扩张和误复制，不影响已约定的单 UT 单输入主场景。
- 输入 SHA 比较确认的是内容相同，不代表业务语义相同；LLM 仍必须读取输入和问题进行分析。
- 动态采集次数没有按对话写死为固定次数，但每次 Plan 和 Collection 有事件数、命中数、对象深度、字节数和超时预算。LLM 只有在现有证据不足时才应新增采集。
- Eval Harness 能确定性检查工具顺序、产物、错误码和证据引用，但无法让语言模型输出完全确定；因此保留真实 Smoke Eval 作为发布门禁。
- Demo 中为本轮验收增加的输入文件与测试修改属于本机目标模块验证数据，不属于 Agent 仓库交付内容。
