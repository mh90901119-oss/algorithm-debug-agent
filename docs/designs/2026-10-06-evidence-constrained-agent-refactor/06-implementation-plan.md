# Evidence-Constrained Algorithm Debug Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户此前选择原生执行；不创建 worktree，不擅自启用并行子 Agent。

**Goal:** 在保留现有采集能力和多工具探索的前提下，形成可恢复、语义一致、逐条证据约束的算法定位闭环。

**Architecture:** 宿主运行模型，MCP 服务共同入口协调确定性能力。追加仓库保存事实和调查历史，资格按具体见证核查，不以全局假设数量或布尔 evidenceUsable 决定根因。

**Tech Stack:** 本地 Java 21、Maven、JUnit 5、现有 MCP SDK/Jackson、Node 测试与宿主适配；无新增第三方依赖。公司按实际后端映射，不强制重新搭框架。

**Spec:** [02 完整契约](02-architecture-and-contracts.md)、[03 本地文件设计](03-local-code-change-design.md)、[04 公司映射](04-company-migration-guide.md)、[05 测试规格](05-tests-and-acceptance.md)。

**Status:** Approved / Implementing。2026-10-06 用户明确要求本地开始实施，沿用原生执行、不创建 worktree。各任务必须完整 RED/GREEN 与审计；未验证事项不标为完成。进度见 progress.md。

## Global Constraints

- 本地在当前仓库执行，不创建 worktree，不覆盖用户的 docs/sharing/ 或其他已有变更。
- 原始证据只读，Case/Analysis/Plan/Run/Collection/Evidence/Report 追加保存。
- 正常 UT 不要求普通 Run 失败基准；失败使用明确所选结构化失败指纹。
- 保留当前 Tool 名称和 CodePath/JDWP 采集逻辑；公司扩展工具按真实 Catalog 接入。
- 无第二个模型、万能 Tool、任意表达式、固定采集次数、生产源码改写或投机性空 SPI。
- 数字/字符串常量化，公共模型不可变、中文 Javadoc、异常保留 cause；Adapter 不反向依赖 Core。
- Schema/CLI/SPI/Handler/安装资产同一契约升级；新 API 必须有生产者、消费者和测试。
- 每阶段受影响测试；跨模块根 test；提交/推送需另获用户授权，计划不自动执行 git commit。

## Review Focus

1. 成功 UT 但业务异常，无普通 Run：T04 / P5，不能丢掉合法数据。
2. UNKNOWN 后调整 Plan 补采同一 Gap：T06、T07 / P4，不能永久锁死或吞原反证。
3. MCP 响应丢失、换连接 wire id 重用：T15、T23 / P2/P7，不能重复运行或串 Case。
4. 预检 PASS 后源码/bytecode/classpath 改变：T18 / P5，不能缓存放行旧断点。
5. Evidence 可读但记录来自另一实体/Run：T09、T13 / P6，不能成为确认因果见证。

## 任务关系与阶段出口

```mermaid
flowchart LR
    P0["P0 基线/公司映射"] --> P1["P1 契约与回归夹具"]
    P1 --> P2["P2 原子归档/操作/快照"]
    P2 --> P3["P3 取消与资源安全"]
    P3 --> P4["P4 可继续调查/效果语义"]
    P4 --> P5["P5 采集资格/共享预检"]
    P5 --> P6["P6 见证/逐条 Gate"]
    P6 --> P7["P7 MCP 反馈/恢复/宿主"]
    P7 --> P8["P8 全链路与故障注入"]
    P8 --> P9["P9 质量评测/审计/发布"]
```

这是默认原生实施顺序，不是用户调查工具调用顺序。P0—P7 的中间产物仅供开发测试；全部契约串联以前不切换正式入口。每任务的 test 名与断言在 05 已定义，执行者不能自由省略场景。

### P0：冻结现状与公司映射

**Files:** 本地只读取 03 的 existingFiles；公司创建 04 的 company-capability-inventory.md、company-file-map.json；验收记录存团队受控目录。

**Interfaces:** 产生实际 Catalog/文件映射、固定模型/输入/代码/Prompt 基线与可运行测试命令；消费现有工具与宿主安装配置。

- [ ] 读取实际 AGENTS/架构/当前计划、git status 与 HEAD，保留现有变更。
- [ ] 执行旧版本受影响单测及真实复杂 Case 基线，记录命令/错误/skip，不冒充整改测试。
- [ ] 公司逐工具补完整注册和副作用表；未映射能力不凭空实施。
- [ ] 审查目标设计和公司精确文件修改表；批准后进入 P1。

**Exit:** 工具能力、缺口、未知项及目标边界可以逐项核对。公司无法提供宿主仅阻塞其真实验证，不阻塞已盘点服务的单测。

### P1：先冻结契约和失败夹具

**Files:** `schemas/investigation/`、`schemas/coordination/`、`schemas/collection/`、`schemas/tool/`、`schemas/mcp/tools/`；`ada-contracts/src/test/java/org/example/algorithmdebug/contracts/investigation/InvestigationSchemaTest.java`、`coordination/CoordinationSchemaTest.java`；新增 `ClaimWitnessSchemaTest.java`、`PlanPreflightSchemaTest.java` 同属 contracts 测试包。

**Interfaces:** 产生 02 第 14 节全部新 Schema、合法/非法 JSON 夹具和公共字段字典。新 DTO 在后续所属用例任务中和消费者一起落地，不创建没有消费者的占位实现。

- [ ] 编写 T02/T20 的 Schema 失败测试，固定 v2 Binding、v3 Candidate、v2 Finalization 与未知字段/越界样例。
- [ ] 运行 `mvn -pl ada-contracts -am test`；确认失败来自目标契约缺失/拒绝规则，而非 fixture 路径错误。
- [ ] 新增严格 Schema 与 `$ref`，使用 02 的命名预算；旧 Schema 不改语义。
- [ ] 加入教学夹具源代码/UT 与已知观测，使后续成功/失败/反证测试复用同一真值。
- [ ] 同命令 GREEN，记录“Schema 已通过，生产新协议未接通”，不提前改正式 Catalog。

### P2：原子调查事务、业务操作与快照

**Files:** 修改 `case-management/src/main/java/org/example/algorithmdebug/casecore/{CaseArchiveLayout,InvestigationEventArchive,InvestigationJournalReader,OperationJournal,AnalysisArchiveReader,ConclusionDecisionArchive}.java`；创建同包 `CaseWriteLock.java`、`InvestigationCommitArchive.java`；修改 `ada-core/.../core/coordination/{OperationIdempotencyService,WorkspaceExecutionLockManager}.java`；相关 Contracts Commit/Receipt/Request/Control 与 SchemaVersions。

**Interfaces:** `appendBatch(identity, operationId, expectedSnapshotToken, events)` 返回不可变 Commit；`snapshot(identity)` 返回统一 token 与校验后只读内容；`claim/replay` 返回操作状态与结果 Artifact 引用。文件 IO 的异常不得返回伪空快照。

- [ ] 编写 T15/T16/T23：用并发屏障复现重复序号；batch 中断；同 operation/input replay；新 transport id；begin 未知响应。
- [ ] 运行 `mvn -pl case-management,ada-core -am test`，确认旧行为对应 RED。
- [ ] 落地短锁固定顺序、单批原子提交、严格双格式读和项目级 begin operation；绑定模型与仓库完整消费者。
- [ ] token 只包含控制依据，诊断/Candidate 本身不影响 token；增加重排文件不改变 token 断言。
- [ ] GREEN 后检查任何写账本/注册公共输入的入口都用事务，没有绕过路径；完整操作不重跑。

### P3：公共取消控制贯穿进程

**Files:** 创建 `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ExecutionControl.java`、`case-management/src/main/java/org/example/algorithmdebug/casecore/ProcessOwnershipArchive.java` 及 process-ownership Schema；修改 `debug-harness/.../harness/{TargetTestExecutor,ExternalProcessRunner,ManagedProcessRunner,ManagedProcess,ProcessSupervisor}.java`、`method-path-spi/.../methodpath/MethodPathCollector.java`、`method-path-codepathtracer/.../codepath/CodePathProcessCollector.java`、`jdwp-collector-adapter/.../jdwp/JdwpCollectionCoordinator.java`、Core Run/Collection/JDWP 服务与 CoreActionHandlers、McpServerLifecycle。

**Interfaces:** Run/Collect 的执行端口接受同一个 ExecutionControl；ActionCancellation 实现/适配该契约；回调返回可关闭注册。人工/测试入口显式使用不可取消控制，不作为模型路径默认吞取消。

- [ ] 写 T17 启动前/运行中/响应后取消与清理失败测试，fake supervisor 检查 terminateCalls 和 Manifest。
- [ ] 执行受影响 Maven 模块 test，确认当前 token 未传播的场景 RED。
- [ ] 同步端口、实现、所有生产调用与测试，finally 注销并清理；复用现有进程树终止。
- [ ] 运行 GREEN，再用真实 fixture 验证 JVM/Collector 无残留，清理失败不会允许新启动。
- [ ] 边界审计：Collector/adapter 不 import core ActionCancellation，公开接口无实现类型泄漏。

### P4：可继续的调查与统一效果语义

**Files:** 修改 `ada-contracts/.../contracts/investigation/` 中 03 列出的模型；新增 InvestigationPurpose、ObservationNeed；修改 `ada-core/.../core/{InvestigationApplicationService,StaticAnalysisApplicationService}.java`、`core/coordination/{InvestigationStateProjector,CoreActionInputs,CoreActionHandlers}.java`、MCP SourceQueryInput；`debug-plan-engine/.../plan/InvestigationBindingRequest.java`、InvestigationBindingValidator、两种 Compiler；`evidence-engine/.../evidence/{ObservationEvaluator,HypothesisEvidenceReducer,CausalChainEligibilityEvaluator}.java`；新增 ObservationEffectInterpreter。

**Interfaces:** 保留 `recordPlanBound(planId,binding)` / `recordCollectedEvidence(binding,evidence)` 的应用职责，新 Binding purpose、Gap observationNeeds；新增 `recordSourceQueryEvidence(gapId,SourceQueryResult)` 并由StaticAnalysisApplicationService消费可选gapId；LINK_GAP_HYPOTHESIS命令和predicate supersedes字段；Interpreter返回根据资格实际可应用的Effect。

- [ ] 写 T06—T08 的全部失败测试：Plan1 UNKNOWN 后 Plan2 同 Gap 完整关闭，FALSE→SUPPORT，与最新 UNKNOWN 不吞历史。
- [ ] 运行 `mvn -pl ada-core,debug-plan-engine,evidence-engine -am test` 确认 RED。
- [ ] 接入 P2 单批提交；状态不由模型设置；补关联/继续/条件版本与SourceQueryLinked的生产、Schema、Handler和事件消费者；纯源码需求可关闭但不假装运行时观测。
- [ ] 两种 Compiler 使用同绑定校验；探索不编造假设，验证不改冻结条件。
- [ ] GREEN 并审查反证历史及多 Run 范围；不留下旧 OPEN-only/UNRESOLVED terminal 分支。

### P5：UT 资格与共享 JDWP 预检

**Files:** 修改 `trace-validator/.../validator/CollectionEvidenceValidator.java`、`ada-core/.../core/{CollectionApplicationService,JdwpCollectionApplicationService,CollectionPostProcessingService}.java`；创建 `debug-plan-engine/.../plan/{PlanPreflightService,ClassDebugInfoSource,JavapDebugInfoParser}.java`、`algorithm-debug-runtime/.../runtime/JavapClassDebugInfoSource.java`；新增 Contracts PlanPreflightReport/ClassDebugInfo；修改 RuntimeToolchainResolver、CaseArchiveLayout/Artifact 登记。

**Interfaces:** `preflight(plan, targetFingerprints, validationLevel, executionControl)` 返回不可变 PlanPreflightReport；采集服务启动前调用共享 ensure 检查，不依赖模型先调外露工具。collect 输入显式可选 baselineRunId；不选时失败为 INCOMPARABLE，成功 NOT_REQUIRED。

- [ ] 写 T04/T05/T18：成功无普通 Run、失败指纹分类、非执行行/过期报告/UNKNOWN；javap 重载/内部类/缺工具/异常输出。
- [ ] 模块 test RED，源安全检查失败不得仅变 UNKNOWN。
- [ ] 保持正常/动态 UT 原执行方法，明确基准归属和配置；完整归档失败前数据。同步Manifest/Collection请求/Launcher CodePathPlanReader的新版本读取，Raw不变。
- [ ] 本地复用 JDK 工具受控进程；公司已有预检优先适配共享服务，不替换其更精确实现。
- [ ] GREEN + 真实动态验证，确认预检不另执行一次 UT、Collector 数量不增加、报告不是算法 Evidence。

### P6：记录见证与唯一逐条结论门禁

**Files:** 创建 `evidence-engine/.../evidence/{ClaimQualificationEvaluator,ClaimWitnessResolver}.java`、`ada-core/.../core/ArchivedClaimWitnessResolver.java`；新增不可变 ClaimWitness/ResolvedClaimWitness/ClaimAssessment；修改 ConclusionClaim/Candidate/Decision/Finalization、CausalChain/Edge、ConclusionGate、AnalysisStateProjector、CollectionEvidenceViewFactory、RuntimeAnalysisArchiveRouter。

**Interfaces:** `ClaimWitnessResolver.resolve(identity,witness) -> ResolvedClaimWitness`；`ClaimQualificationEvaluator.evaluate(claim,investigation,resolver) -> ClaimAssessment`；Gate 组合逐条评估与相关链，不再消费 Control.terminalEligibility。

- [ ] 写 T09/T12—T14：真实 Bootstrap 的无假设事实可确认；另实体/另 Run/AGGREGATE 顺序错误；无关 Gap；自由文本 cause 不确认。
- [ ] 执行 `mvn -pl ada-core,evidence-engine,algorithm-debug-runtime -am test` 确认 RED。
- [ ] 实现有界可信记录解析、canonical factSummary 与等级；SourceAnchor身份配合查询SHA，不伪造hash。解析不持短锁；提交重新核对token；ALLOWED依据一致，STATE_CHANGED拒绝记录真实新token，不伪造Finalization一致性。
- [ ] 删除全局终态封顶/数量门槛/FALSE 硬拒绝；已记录相关矛盾仍保留，fact-only 不编造图。
- [ ] GREEN，审计正常“确认事实”与复杂“有界机制解释”两条生产路径均可结束，不是所有输出都降级拒绝。

### P7：MCP 同源反馈、恢复与宿主资产

**Files:** 创建 `ada-core/.../core/OperationRecoveryService.java`、`core/coordination/RecoveryActionFactory.java`；修改 Coordinator、CoreActionInputs/Handlers/Policies/ResultFactory、Runtime Bootstrap；修改 MCP Catalog/Dispatcher/Factory/ContextResolver/ResultMapper/Limits/input；更新 `agent-definition/system-prompt-v1.md`、Manifest/Definition、`integrations/host-adapter-kit/scripts/render-host-config.mjs` 和宿主 Adapter。

**Interfaces:** recoveryActions 指向当前 Catalog；普通 status 可用 inspectOperationId 只读查询，recoverOperationId 分支映射内部 ANALYSIS_RECOVER_OPERATION / CASE_WRITE，恢复调用自身operationId与目标互不混淆；McpResultMapper 将同一 CoordinatedToolResult 输出至 content/structuredContent；Roots optional fallback 仅用可信配置。

- [ ] 写 T01/T10/T11/T19/T20/T22/T23：text-only 客户端完整反馈、错误分类、恢复不再运行 UT、Roots 冲突、超限、权限旁路。
- [ ] 运行 MCP/Core/Runtime/Node 测试 RED，不能只看内容截图。
- [ ] 显式 operationId 和 token 参数统一；参数解析不从 wire ID 推导 Case/op；回放返回完整结果引用。
- [ ] 全部工具从共同入口注册；公司扩展逐项适配/Schema/Policy/Eval；Prompt 解释 Truth/Effect 与服务端事实来源。
- [ ] GREEN + 打包/安装/卸载测试；正式 profile 不同时开放旧旁路。无宿主真实验证时仍不发布兼容宣称。

### P8：真实端到端与故障恢复

**Files:** 修改 `integration-tests/pom.xml`；新增 05 第 3 节十个 `integration-tests/src/test/java/org/example/algorithmdebug/integration/*E2ETest.java`；使用现有动态 Collector 测试与安装脚本，新增测试 fixture 只放脱敏/合成数据。

**Interfaces:** 从 stdio MCP 调用真实 Runtime 到 Case audit；process/Collector 行为用真 fixture 验证，模型质量留给 P9。

- [ ] 先写 E2E，保证 Surefire 实际发现；阶段未接通时失败原因能够定位到具体契约。
- [ ] 运行 `mvn -Pcodepath-launcher test`；不得修改断言/golden 掩盖真实语义差异。
- [ ] 按 05 注入每个持久化/响应故障点，验证没有重跑/坏账本/半终态。
- [ ] 在真实宿主中确认模型可见恢复、finalize 拒绝、工具权限；不拿模拟客户端冒充真实宿主。
- [ ] GREEN 后归档命令/报告/skip/风险；重大缺口返回所属任务修正，不能只修改文档宣布完成。

### P9：复杂机制 Eval、全面审计与发布

**Files:** 修改 `agent-evals/run.mjs`、`grade.mjs`、test、schemas；新增 `agent-evals/suites/evidence-convergence.json`；同步 03 第 7 节文档；保存与代码版本绑定的发布审计。

**Interfaces:** 新 runner 支持受控 `--repeat 5`、`--suite <实际suite路径>`，记录每次结果与轨迹；grader 输入固定参考机制和 typed Finalization，输出 E01—E03 指标。不用自然语言关键词出现当根因正确。

- [ ] 先写 runner/grader 单测：机制等价、稳定身份、拒答惩罚、反证错误确认、留出 Case 与版本不可比。
- [ ] Node RED/GREEN；落地 05 的 10 机制 × 至少 5 次 + 扰动/留出案例。
- [ ] A/B 对比公司旧版本，不遗漏新增源码能力差异；同时跑性能与预算，报告失败明细。
- [ ] 全面审计：需求→文件→入口→产物→消费者→失败恢复→测试→Eval；扫描旁路、吞异常、魔鬼数字、未消费字段、循环依赖、临时兼容和敏感数据。
- [ ] 所有发布门禁通过后批准切换；若准确性未达标，明确仍不满足目标，不以功能通过代替质量。
- [ ] 做版本包整体回滚演练并记录新档案保护；用户授权后才提交/推送。

## 完成定义

全部任务有验证记录；新生产 API 有真实调用；每个公司工具四类 Eval 完整；动态与宿主验证不跳过；关键根因质量与稳定性达到预先冻结门槛；未验证的事项单列。完成前不得把本设计标为 Implemented/Verified。
