# 03 本地代码架构与逐文件修改设计

- 状态：Approved / Implementing（实际完成范围见 progress.md）；版本：1.0；审计基线：`39514630743a65722fca8e90f8eda4051a85a302`。
- 本文件相对路径均从仓库根解析，属于本地拓扑，不要求公司照搬。
- [机器可检查的已有文件清单](examples/local-file-map.json)；行为契约以 [02](02-architecture-and-contracts.md) 为准。

## 1. 现状审计与整改边界

| 编号 | 当前连接路径上的发现 | 影响 | 修改与回归测试 |
|---|---|---|---|
| L1 | AnalysisStateProjector 只生成 BOUNDED_HYPOTHESIS/MISSING_EVIDENCE，Gate 再按 terminalEligibility 封顶 | 生产路径不能达到 CONFIRMED；手构造 Control 的单测不能证明真实路径 | 删除全局终态上限，逐 Claim 评估；T12 |
| L2 | InvestigationBinding/recordPlanBound 要求 OPEN，第二 Plan 不允许绑定；UNRESOLVED 被当终态 | 补采可能执行但不能真正消除缺口，诱导模型复制对象绕过 | 分离 Gap 与采集历史，多 Plan/Run；T06、T07 |
| L3 | Predicate 允许 onFalse=SUPPORT，CausalChain evaluator 却把 FALSE 一律受限 | Truth 与 Effect 在不同模块语义不一致 | 共用效果解释，保留实际支持/反驳；T08 |
| L4 | ReferenceCatalog 只存宽 ID 与资格，关键边只检查某 Evidence 已被评估 | 无关记录可以借同一 Evidence 包给边“背书” | 有界见证解引用与逐边范围；T09、T13 |
| L5 | Gate 使用全局开放 Gap/假设和固定竞争假设数量 | 无关探索拖死合法输出，但数量满足也不证明正确 | Candidate 范围、取消数量门槛；T14 |
| L6 | MCP 文本只有短摘要，恢复/状态主要在 structuredContent；Handler 异常可被通用 internal error 覆盖 | 部分宿主模型看不到如何恢复，也无法区分预期失败 | 同源有界结果与 typed failure；T10、T11 |
| L7 | 业务 ID 来源包含 MCP requestId | 新连接 ID 重用可能冲突；换 wire ID 不等于同业务重试 | 显式 operationId + 独立 ID factory；T15 |
| L8 | CASE_WRITE 不获得目标执行锁以外的写事务；调查批次逐事件提交 | 并发写序号/部分批次可能损坏调查状态 | Case/Analysis 短锁与批提交；T16 |
| L9 | CoreActionHandlers 接收 cancellation，但 Run/采集调用未完整传递 | 收到 MCP cancel 不等于目标进程已终止 | 公共 ExecutionControl 贯穿进程；T17 |
| L10 | Dispatcher 每次 listRoots，Resolver 要求匹配 Root | “只支持 Tools 即可”的文档与实际入口不一致 | 能力缺失用可信配置；冲突仍拒绝；T19 |
| L11 | JDWP Plan 编译检查源码范围不等于检查可执行 bytecode 位置 | 错误断点可能到运行时才发现 | 内部共享预检；公司外露工具复用；T18 |

这些为代码路径审计结论，不声称已加入失败复现或已修复。本轮不改生产代码；每条必须在实施时先补能失败的测试。

## 2. 包路径约定

以下 Java 路径格式为 `<module>/src/main/java/org/example/algorithmdebug/<package>/<Class>.java`；对应测试在同模块 `src/test/java`。

| 模块 | package |
|---|---|
| ada-contracts | contracts、contracts.investigation、contracts.coordination |
| ada-core | core、core.coordination |
| case-management | casecore |
| evidence-engine | evidence |
| debug-plan-engine | plan |
| static-analysis | staticanalysis |
| debug-harness | harness |
| method-path-spi | methodpath |
| method-path-codepathtracer | codepath |
| jdwp-collector-adapter | jdwp |
| algorithm-debug-runtime | runtime |
| algorithm-debug-mcp-server | mcp、mcp.input |

这张表与类名共同给出完整相对路径。例如 `ada-core / core.coordination / ConclusionGate` 就是 `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ConclusionGate.java`。不使用含糊的“在某个中间层处理”。

## 3. 已有文件修改清单

| 模块 / 已有类或文件 | 具体修改 | 输入输出 / 责任边界 | 测试 |
|---|---|---|---|
| ada-contracts / SchemaVersions | 新增版本常量与兼容读版本，不改 Raw 版本 | 所有 Schema 版本真源 | T02 |
| ada-contracts / investigation / EvidenceGap、EvidenceGapStatus、InvestigationBinding | Gap 不承载 Plan 执行阶段；不可变 observationNeeds；允许探索空假设；绑定 purpose | 不可变公共 DTO | T06、T07 |
| ada-contracts / investigation / HypothesisRecord、ObservationPredicate、InvestigationUpdateCommand、InvestigationEvent、InvestigationState | 关联命令、条件替代、继续事件；状态由服务派生 | 模型仍不能提交 Evaluation/状态 | T07、T08 |
| ada-contracts / coordination / AnalysisActionRequest、AnalysisActionType、ActionOutcome、AnalysisControlView、CoordinatedToolResult、OperationReceipt | 操作 ID、快照 token、恢复动作、回放/恢复来源引用；内部恢复 Action；v2 outcome；移除全局终态与空义务 | 保留 action/result 强类型；旧 SUCCEEDED 在读适配器映射 EXECUTED | T10、T15 |
| ada-contracts / coordination / ConclusionClaim、ConclusionCandidate、ConclusionDecision、ConclusionFinalization | witness 引用、逐条评估与 canonical factSummary；普通事实无强制 Chain | 同一 candidate/decision/token 绑定 | T12、T13 |
| ada-contracts / investigation / CausalEdge、CausalChain | 每边见证、targetHypothesisIds；自由文本不是确认关系 | 不建立任意业务因果 DSL | T13、T14 |
| case-management / InvestigationEventArchive、InvestigationJournalReader、CaseArchiveLayout | 新批次路径和原子提交；双格式严格读取，保持旧事件只读 | Commit 是调查事务可见边界 | T16、T23 |
| case-management / OperationJournal、ConclusionDecisionArchive、AnalysisArchiveReader | 操作结果引用、唯一终态、candidate-first 的恢复/冲突；读取新格式 | 不自动重新启动目标 UT | T15、T16、T23 |
| ada-core / core.coordination / AnalysisCoordinator、OperationIdempotencyService | 统一生命周期、失败映射、回放完整引用、取消传播；不要膨胀算法逻辑 | 策略与用例独立 | T01、T10、T15、T17 |
| ada-core / core.coordination / WorkspaceExecutionLockManager | 项目锁管理保留，协调短写事务入口；固定锁序 | 不持短锁执行整个 UT | T16 |
| ada-core / InvestigationApplicationService、StaticAnalysisApplicationService | 单事务更新、多个 Plan、UNKNOWN 可继续、双向关系派生、冲突保留；source_query的可选Gap覆盖写入 | 账本唯一写服务，纯源码需求也有消费者 | T03、T06、T07、T08 |
| ada-core / core.coordination / InvestigationStateProjector、AnalysisStateProjector | 严格重建新状态；token；allowedActions 允许无假设 finalize | 不产生结论等级，不按真实时间挑结果 | T07、T12、T14 |
| evidence-engine / ObservationEvaluator、HypothesisEvidenceReducer、CausalChainEligibilityEvaluator | 使用冻结映射；分范围处理历史；引用 witness，删除全局数量/布尔真假门槛 | 不读不受信路径、不调用模型 | T08、T09、T13 |
| ada-core / core.coordination / ConclusionGate | 逐 Claim 资格；允许无 Chain 的事实；唯一结论等级计算 | Candidate 原文只审计，事实按服务端渲染 | T12、T13、T14 |
| ada-core / CollectionEvidenceViewFactory、CollectionPostProcessingService | Derived 记录可追溯解析、失败产物保留、派生幂等恢复 | 不向模型发送完整对象 | T04、T05、T09、T23 |
| debug-plan-engine / InvestigationBindingRequest、InvestigationBindingValidator、CodePathPlanCompiler、JdwpPlanCompiler | 新绑定结构、合法继续、明确探索/验证；消除相关魔鬼数字 | 编译计划仍确定性执行 | T06、T07、T18 |
| ada-core / RunApplicationService、CollectionApplicationService、JdwpCollectionApplicationService、JdwpCollectionExecutor、TargetFailureBaselineEvaluator | 传 ExecutionControl；明确所选 baselineRunId；共享预检与恢复 | 不新增固定轮数或常驻调试会话 | T04、T05、T17、T18 |
| debug-harness / TargetTestExecutor、ExternalProcessRunner、ManagedProcessRunner、ManagedProcess、ProcessSupervisor | 注入取消、终止注销、排空输出、清理状态 | 原受控进程实现继续使用 | T17 |
| method-path-spi / MethodPathCollector；method-path-codepathtracer / CodePathProcessCollector | 追加 ExecutionControl 参数或 request 成员，SPI/实现/测试同时升级 | 不泄露 ada-core ActionCancellation | T17、T21 |
| jdwp-collector-adapter / JdwpCollectionCoordinator | 两个受管进程使用同一控制；断点安装保留 LIVE_BINDING 结果 | 不改 JDWP 协议/命中规则 | T17、T18、T21 |
| algorithm-debug-runtime / AlgorithmDebugRuntimeBootstrap、RuntimeAnalysisArchiveRouter、RuntimeToolchainResolver | 唯一装配新增服务、可信见证解析、快照、预检/恢复适配、javap 解析路径 | MCP/CLI 不复制装配 | T01、T12、T18、T19 |
| algorithm-debug-mcp-server / McpToolCatalog、McpToolDispatcher、McpActionRequestFactory、McpRequestContextResolver | v2 参数/输出、共享动作入口、独立业务 ID、Roots 可选、预期失败不平铺 | 当前工具名称保留 | T01、T02、T10、T15、T19 |
| algorithm-debug-mcp-server / McpResultMapper、McpServerLimits、McpServerLifecycle | 同源文本/结构结果、完整帧预算、取消/关闭有界等待 | 不保留第二套结论判断 | T11、T17、T20 |
| algorithm-debug-mcp-server / mcp.input / AnalysisBeginInput、AnalysisStatusInput、AnalysisFinalizeInput、InvestigationUpdateInput、SourceQueryInput、CodePathCollectInput、JdwpCollectInput | operationId/token、status 恢复分支、可选source gapId、baselineRunId；其他写入输入统一元数据 | status 恢复参数用 CASE_WRITE 策略，读参数仍只读 | T02、T03、T15、T23 |
| static-analysis / SourceQueryService、BoundedSourceWindowReader | 保留有界查询，确保源码 hash/窗口能供 Witness 解引用；限制稳定输出 | 不额外建全程序语义引擎 | T03、T13 |
| trace-validator / CollectionEvidenceValidator | 保持成功无失败基准；失败同类指纹/覆盖；补边界回归 | 技术资格不等于业务解释正确 | T04、T05 |
| agent-definition / system-prompt-v1.md、capability-manifest-v1.json、algorithm-debug-agent-v1.json | 更新语义、能力资产 hash/Profile 引用；名称文件可保留，内部版本显式升级 | Manifest 来源 Catalog，数量不硬编码 | T01、T22 |
| integrations/host-adapter-kit/scripts/render-host-config.mjs、integrations/qwen-cli、integrations/opencode | 按实际目录映射权限、同源角色指令；旧兼容工具不形成绕过 | 宿主-specific 配置仅在 Adapter | T19、T22 |
| agent-evals / grade.mjs、run.mjs、schemas、suites | 加入结构化机制正确性/有界性/重复指标；保留旧测试 | 无证据不以模型互评替代真值 | E01、E02、E03 |

`CoreActionHandlers` 与 Action Policies 还需同步所有签名、输入和结果适配：LifecycleActionPolicies、TargetExecutionPolicies、ReadActionPolicies、InvestigationUpdatePolicy、CoordinatedResultFactory。不得修改服务却保留 Handler 调用旧签名的空悬链路。

## 4. 有真实需求支撑的新增文件

| 模块 / package / 新类 | 责任及调用者 | 依赖 / 不做什么 |
|---|---|---|
| ada-contracts / contracts / ExecutionControl | Harness、Collector 的可替换取消边界，回调可注销 | 无 MCP/Core 依赖；提供不可取消实现供人工/测试 |
| ada-contracts / coordination / RecoveryAction、ClaimWitness、ResolvedClaimWitness、ClaimAssessment、OperationRecoveryResult | 统一恢复动作、解析见证、逐条结果与恢复关联 | 严格类型/预算，非自由 Map |
| ada-contracts / investigation / InvestigationCommit、InvestigationPurpose、ObservationNeed | 原子批次、绑定目的与不可缩减需求 | 无存储实现依赖 |
| ada-contracts / contracts / PlanPreflightReport、ClassDebugInfo | 预检等级/check/result 和方法调试信息 | 文件位置为可移植相对引用，不持进程 |
| case-management / casecore / CaseWriteLock、InvestigationCommitArchive、ProcessOwnershipArchive | Case/Analysis 短事务、不可变批次、进程归属/清理记录 | 复用已有 OS 文件锁思想，测试真实跨进程；不任意杀 PID |
| evidence-engine / evidence / ClaimQualificationEvaluator、ObservationEffectInterpreter、ClaimWitnessResolver | 无模型逐条资格、共享 Truth→Effect、可替换只读见证端口 | Resolver 只返回已解析 typed 见证，不暴露实现仓库 |
| ada-core / core / ArchivedClaimWitnessResolver | 用已登记 Artifact/SourceQuery 解析具体记录 | 有界读取，不按模型传入任意路径读文件 |
| ada-core / core / OperationRecoveryService | status 恢复分支调用，完成 Raw 后的派生/评估 | 永远不重新启动 UT；无恢复证据保持 UNCERTAIN |
| ada-core / core.coordination / RecoveryActionFactory | 将稳定原因映射至 Catalog 中可执行动作 | 不猜字段、不增加调度框架 |
| debug-plan-engine / plan / PlanPreflightService、ClassDebugInfoSource、JavapDebugInfoParser | 源/字节码规则、外部调试信息端口、有界纯解析 | 不从 Core 反向调用 Collector，未知格式不能 PASS |
| algorithm-debug-runtime / runtime / JavapClassDebugInfoSource | 调受控 `javap`，解析为 ClassDebugInfo | 复用 debug-harness；由可信 JDK 路径定位，不引入字节码库 |

这里列的是实施目标，不在本轮创建占位 Java 文件。可以把小型值枚举作为记录的嵌套类型，不能为表面分层创建大量只转发的类。每个新增生产文件的调用者、失败路径、测试必须在同一阶段出现。

### 4.1 关键 API 形状

以下是语言级接口形状，不是可直接粘贴编译的完整实现；其类型必须先按 02 写 Schema/测试后落地。

```java
// contracts：资源回调必须能够解除注册。
interface ExecutionControl {
    boolean isCancelled();
    AutoCloseable onCancellation(Runnable cleanup);
    void throwIfCancelled();
}

// evidence-engine：IO 由可信实现隔离，评估器只使用解析后的不可变内容。
interface ClaimWitnessResolver {
    ResolvedClaimWitness resolve(AnalysisIdentity identity, ClaimWitness witness);
}

// core：调用者不自己设置 truth/status。
InvestigationState recordPlanBound(PlanId planId, InvestigationBinding binding);
InvestigationState recordCollectedEvidence(InvestigationBinding binding, EvidenceView evidence);
InvestigationState recordSourceQueryEvidence(EvidenceGapId gapId, SourceQueryResult result);
OperationRecoveryResult recover(AnalysisIdentity identity, OperationId operationId);
```

`ResolvedClaimWitness` 与 `OperationRecoveryResult` 位于 contracts.coordination，不以内部实现类型当公共返回值。前者包含原 witness、服务端归属、解析状态、typed scalar/source/finding 内容、覆盖/资格和限制；后者包含 recoveryOperationId、recoveredFromOperationId、outcome、resultReferences、limitations。新恢复操作成功不改原操作既有终态。新增 enum/字段见 02 与 Schema，不许用散落字符串替代。

### 4.2 本地 BYTECODE 预检实现

无需新增第三方依赖。使用目标配置 JDK 21 的 `javap -l -p -s` 读取已准备 classpath 中的类，JVM 语言/地区固定为解析支持的值，stdout/stderr 和超时仍走 Harness。用方法 descriptor 区分重载；输出映射 LineNumberTable/LocalVariableTable 与可见范围。

`JavapDebugInfoParser` 只解析受支持 JDK 21 的有界输出，支持构造器、内部类、重载、缺调试信息与警告；未知格式返回 UNKNOWN 而非“没有行号”。不得 shell 拼接，所有参数以 argv 列表传给 ProcessBuilder。java/javap 通过 RuntimeToolchainResolver 与显式配置解析，不写死开发机 JDK 路径。

JDK 不含 javap 时返回可解释的 UNKNOWN，运行期 LIVE_BINDING 仍校验；不能把可选增强变成全 Agent 无法运行。源码不存在/越界等确定安全失败必须拒绝。公司已有更精确的字节码预检实现应直接复用，不为统一而降级成 javap。

## 5. Schema、存储与产物

### 5.1 新增/升版 Schema

新增目录文件全部在现有 schemas 下，不写入本交付包作为“已发布协议”。

| 路径 | 实施动作 |
|---|---|
| schemas/investigation/*-v2.schema.json | Gap/Hypothesis/Predicate/Binding/Event/State/CausalChain 新契约；保留 v1 读 |
| schemas/investigation/investigation-commit-v1.schema.json | 原子事件批文档 |
| schemas/coordination/conclusion-candidate-v3.schema.json、conclusion-decision-v3.schema.json、conclusion-finalization-v2.schema.json | Candidate/逐条资格/Finalization |
| schemas/coordination/analysis-control-view-v2.schema.json、analysis-action-request-v2.schema.json、operation-receipt-v2.schema.json | 状态、请求与回放 |
| schemas/coordination/claim-witness-v1.schema.json、recovery-action-v1.schema.json | 见证联合类型与可执行恢复 |
| schemas/tool/coordinated-tool-result-v2.schema.json | 同源工具结果 |
| schemas/mcp/tools/*-v2.schema.json | 对受影响 Tool 参数/输出使用对应新版本；未变的读工具不无谓复制 |
| schemas/collection/codepath-plan-v8.schema.json、jdwp-plan-v7.schema.json | 新 Plan，复用既有 schemas/collection 目录 |
| schemas/collection/plan-preflight-report-v1.schema.json | 不可变预检报告 |
| schemas/collection/method-path-manifest-v6.schema.json、jdwp-manifest-v4.schema.json、method-path-collection-request-v3.schema.json、jdwp-collection-request-v3.schema.json | 预检/基准/执行结果版本同步 |
| schemas/coordination/process-ownership-v1.schema.json | 进程归属与清理的版本化记录 |

既有 Plan 文件为 schemas/collection/codepath-plan-v7.schema.json 与 jdwp-plan-v6.schema.json；JDWP请求DTO实际为 JdwpCollectionRecord，不是不存在的 JdwpCollectionRequest 类；CodePath请求/Manifest为 method-path-spi 下的 MethodPathCollectionRequest/MethodPathManifest。不要把 `*` 当成允许创建没有 DTO 消费者的 Schema。每个新 $ref 必须可解析，嵌入 JAR 的根 Schema 与传输 descriptor 一致。新增 observation-need-v1.schema.json 与公共 DTO 同时被 Gap/Binding 编译/状态消费者使用。

### 5.2 归档增量

```text
case/
  analyses/<analysisId>/
    investigation/commits/<firstSequence>-<commitId>.json    # 新原子批次
    investigation/events/...                              # 旧文件只读
    plans/<planId>.json                                   # 已有，不覆写
    plan-preflight/<reportId>.json                        # 新报告
    operations/<operationId>/...                          # 已有 + 结果引用
    process-ownership/<processHandleId>/...               # 新不可变归属/清理记录
    source-queries/<queryId>/...                          # 已有
    conclusions/<conclusionId>/candidate.json             # 已有模式
    conclusions/<conclusionId>/accepted.json|rejected.json # 唯一决策
  collections/<collectionId>/                             # 已有 Raw/Derived/Manifest
  evidence/<evidenceId>/                                  # 已有不可变证据
  artifacts/<artifactId>.json                             # 所有新可读产物登记
```

begin 的项目级 operation 日志放在已登记 Project Workspace 下，由 CaseArchiveLayout 同级项目布局管理，不强塞进尚未创建的 Case。项目可维护一个有界的当前目标租约索引，指向不可变 process-ownership 记录；索引只是可重建查询缓存，不是事实源，损坏/缺失时不能忽略潜在残留直接启动。

PlanPreflight、Commit、Candidate、Decision、Operation terminal、Raw、Derived 都有生产者、版本、归属和完整性；只有 Raw/Derived/可信 Validator 内容可以支持对应算法事实，预检/授权/操作成功记录不是算法证据。

## 6. 不改变的采集功能与明确风险

本批不改 Byte Buddy Advice、CodePath TRACE/AGGREGATE 事件生成、JDWP 投影语法/三类命中计数、Collector sequence 含义和每次采集的调试 JVM 生命周期。不顺带实施常驻 JDWP 会话。

CodePath/JDWP 没有固定使用次数限制；受到预算、合法身份、项目互斥与每次有效 Plan 的限制。UNKNOWN 后可新 Plan/operation 补采。SPI 的 ExecutionControl 和 Plan 新绑定版本会影响 Collector/Launcher 反序列化，Launcher 的 CodePathPlanReader 必须接收新 Plan 而不改变 Advice 行为。Manifest/Collection 请求引用预检与 baseline 时，严格 Schema 也必须同步升级；见 02 版本表。因此即使插桩算法不变，仍须 T21 + 真实动态测试，不能声称“只加拦截器所以无回归风险”。

预算文件已有：`ada-contracts/.../CollectionBudget.java`、`JdwpCollectionBudget.java`、`NormalizationBudget.java`、`investigation/SourceQueryLimits.java`、`InvestigationLimits.java`、`coordination/CoordinationLimits.java`；进程预算在 `debug-harness/.../ProcessLimits.java`，MCP 在 McpServerLimits。新增值集中命名、配置与验证，不复制这些值到 Adapter。

## 7. 文档同步与代码质量

批准实施后更新：README、docs/current-capabilities.md、docs/architecture、docs/plans、ADR-018、旧 09-25/09-29 设计被替代条款、schemas/README、工具使用说明、Prompt/Manifest 和 Host Adapter release metadata。

禁止通过异常字符串匹配制造恢复动作，禁止 catch-all 当普通拒绝、未注册 Artifact 指针、循环依赖、假空默认实现和遗留双入口。公共模型/API 用中文 Javadoc 描述边界；字符串 enum 化、常量集中；ID/时钟/文件/进程由可替换边界提供。旧格式兼容封装在读适配器，不把版本判断散落到每个业务 Handler。
