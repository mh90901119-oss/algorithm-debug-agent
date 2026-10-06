# 05 实施前测试规格与验收方案

- 状态：Approved（验收规格，尚未全部执行）；版本：1.0。
- 这是必须先实现并观察 RED 的测试规格，不是测试已通过记录。
- 与 [02](02-architecture-and-contracts.md)、[06](06-implementation-plan.md) 共同使用；G 编号来自 01。

## 1. 测试先行纪律

每项行为按“固定夹具→失败断言→执行确认 RED→最小实现→GREEN→重构→受影响模块回归”推进。RED 不能只是断言新增类不存在；本地已有功能缺陷必须证明当前路径确实行为错误。先补测试编译所需最小模型时，生产逻辑不得提前实现。

单元测试使用固定 ID、注入时钟、临时目录、fake process/collector、可控并发屏障。不得依赖网络/真实睡眠/随机目录排序或开发机绝对路径。真实进程/JDWP 放在集成测试，用生命周期探测和有界等待，不用固定 sleep 判断完成。

## 2. 逐项回归规格

下面每一行是测试组，组内场景全部必须落地；测试名可以按团队习惯微调，但不得少掉断言。

| ID / 对应目标 | 建议测试归属与用例 | 固定输入与核心断言 |
|---|---|---|
| T01 / G1、G9 | McpToolCatalogTest、McpToolDispatcherTest、CoreActionRegistryTest | 遍历每个实际 Catalog；Schema/Handler/Policy/权限/Eval 齐全；任意采集直调在缺前提时 handlerCalls=0；新增预检也经过入口；不硬编码工具数 |
| T02 / G1 | CoordinationSchemaTest、InvestigationSchemaTest、McpToolCatalogTest | 新字段 round-trip；未知字段拒绝；错误版本/枚举拒绝；新 $ref 全可解析；旧只读输入不伪装新见证；所有打包 Schema 与 descriptor 一致 |
| T03 / G2 | SourceQueryServiceTest、ArchivedClaimWitnessResolverTest、InvestigationApplicationServiceTest | 六类查询；重载descriptor、窗口/hash、方向/距离正确；多态/越预算显式；目录排序稳定；源码漂移拒绝；可选gapId纯源码需求从部分窗口继续到覆盖完整，不能产生运行时Effect |
| T04 / G3 | CollectionEvidenceValidatorTest、SuccessfulUtEvidenceE2ETest | 没有普通 Run 的成功 CodePath/JDWP：raw readable、baselineRequired=false、有效义务可评估；不因 absence baseline 丢证据；成功输出与业务正确性分开 |
| T05 / G3 | TargetFailureFingerprinterTest、FailedUtFingerprintE2ETest | ASSERTION/EXCEPTION 各复现 MATCHED；改变类型/栈/目标产生 CHANGED；缺基准 INCOMPARABLE；编译失败不当目标异常；非零测试退出仍保留失败前事件；Gantt 改变不能单独拒绝 |
| T06 / G4 | InvestigationApplicationServiceTest、InvestigationBindingTest | 同 Gap Plan1 缺投影→UNKNOWN/UNRESOLVED；Plan2 补齐同字段→完整可用并 CLOSED；两次记录都在；Plan2缩减原observationNeeds拒绝；相同 Plan 新 operation 两次执行；同 operation 重试不执行 |
| T07 / G4、G7 | InvestigationStateProjectorTest、InvestigationContractsTest | DISCOVERY 空假设/条件合法但窄目标必需；已有假设链接 Gap 双向一致；注册条件不能改旧条件；新条件替代保留原反证；CLOSED 后同范围矛盾再显式未解决 |
| T08 / G5 | ObservationEvaluatorTest、HypothesisEvidenceReducerTest、CausalChainEligibilityEvaluatorTest | TRUE→REFUTE、FALSE→SUPPORT 均合法；UNKNOWN→NO_CHANGE；CLUE_ONLY 不应用效果；较新 UNKNOWN 不吞较旧有效观测；同范围支持/反驳并存不选择最新当胜者 |
| T09 / G5、G7 | ClaimQualificationEvaluatorTest、ArchivedClaimWitnessResolverTest | Evidence 合格但 witness 指向别实体/Thread/Run/字段则不确认；同包另一个 Predicate 的评估不替当前评估背书；AGGREGATE 不能证明调用顺序；缺调用关联不给补造 |
| T10 / G6 | AnalysisCoordinatorTest、McpToolDispatcherTest | 前置拒绝 handlerCalls=0；启动失败/Plan 错误分别得到稳定 typed 结果；cause 仅 DFX；失败保留部分 Artifact；未知 NPE 不伪装普通证据不足 |
| T11 / G6 | McpResultMapperTest、RecoveryActionFactoryTest | 仅读取 content 的模拟宿主与读取 structuredContent 得到相同事实/限制/恢复；每条 toolName 存在、arguments 符合其 Schema；缺业务参数显式列出；不自动启动修复 UT |
| T12 / G7 | ConclusionGateTest、McpAnalysisLifecycleE2ETest | 使用真实 Bootstrap/ArchiveRouter/Projector，不手构造 CONFIRMED control；无假设无 Chain 的完整观测能够 CONFIRMED；无有效见证不能确认；status 不预先封顶；事实显示 canonical summary 而非夹带因果的 statement |
| T13 / G7 | CausalChainEligibilityEvaluatorTest、ConclusionGateTest | 连通图+合法 ID+无关记录不能确认边；Source 节点必须有精确 SOURCE witness；纯自由文本关系保持推断；相关 CHANGED/截断/反证不能被 Candidate 隐藏；历史 Run 不能拼成同次调用 |
| T14 / G7 | ConclusionGateTest、AnalysisStateProjectorTest | 单事实无需两假设/一反驳；无关开放 Gap 不降低该事实；相关未解决范围限制机制解释；两项非互斥贡献可以共同有界解释；模型声称排除已记录相关候选却无检验，排除说法不能确认 |
| T15 / G8 | OperationIdempotencyServiceTest、McpRequestContextResolverTest、McpRetryIdentityE2ETest | 两连接 wire id=1 不冲突 Case/op；同 operation 同输入 handler=1；改输入拒绝；换 wire id复用 operation 回放；新 operation 同 Plan真实新 Run；begin 响应丢失可查项目级日志 |
| T16 / G8 | InvestigationCommitArchiveTest、WorkspaceExecutionLockManagerTest、McpTargetExecutionConcurrencyE2ETest | 两写入线程/进程屏障同时提交序号不冲突；批写中断只见完整 batch；同项目 UT 互斥、独立项目允许；锁顺序不反转；短锁不阻塞长 UT 期间读状态 |
| T17 / G8 | ProcessSupervisorTest、JdwpCollectionCoordinatorTest、McpServerLifecycleTest、McpCancellationE2ETest | 启动前取消不启动；运行中取消终止目标/Collector及子进程；日志排空/Manifest/终态可见；回调可注销不重复终止；响应丢失不改成功终态；清理失败及Server重启后未确认残留阻止新启动；PID复用不能杀其他进程 |
| T18 / G9 | PlanPreflightServiceTest、JavapDebugInfoParserTest、CompanyPreflightContractTest | 有效/无效可执行行、重载、内部类、无 LNT/LVT、javap 缺失/格式未知；UNKNOWN 非 PASS；外露与内部同服务；跳过外露预检仍检查；源/classpath/Plan漂移缓存失效；不为预检再跑 UT |
| T19 / G1、G9 | McpRequestContextResolverTest、HostCapabilitiesE2ETest | tools-only 无 Roots 使用冻结配置；有 Roots 匹配接受、冲突拒绝；Resources/Prompts缺失仍闭环；任意 shell/旧工具旁路被权限阻止或明确降低保证 |
| T20 / G6、G8 | McpResultMapperTest、CoordinationSchemaTest | 128 KiB inline、1.5 MiB 完整响应帧、64 KiB Candidate 边界；中文/转义计 UTF-8；content+structured 都计入；超限前置拒绝 Finalize，未生成半个终态；查询返回分页引用，不静默删限制 |
| T21 / G3、G4、G8 | RealCodePathSmokeTest、RealJdwpCollectorSmokeTest、Normalizer/Collector 现有测试 | TRACE/AGGREGATE 原语义；未选方法不插桩；采样/三类命中/sequence/projection unchanged；失败前事件保留；多轮独立 Run；旧插桩测试不能只编译跳过 |
| T22 / G2、G9 | render-host-config.test.mjs、agent-definition.test.mjs、真实宿主测试 | 无知识闭环；知识来源/适用/哈希；误导知识不能变硬证据；安装权限等于 Catalog 快照；规范 Prompt 同版本；旧入口不同时开放 |
| T23 / G4、G8 | OperationRecoveryServiceTest、InvestigationJournalReaderTest、CaseAuditTest | Raw已提交后派生失败→新恢复operation仅派生且collectorCalls不增加，原终态不改；缺证明保持UNCERTAIN；旧格式只读/兼容；重复/断序/坏hash fail-closed；candidate已写Decision未写能幂等完成，token变化则只追加明确拒绝 |

## 3. 必须运行的端到端链路

新增测试类统一以 `E2ETest` 结尾，使现有 Maven Surefire 默认执行；不能仅命名 IT 然后没有 Failsafe 配置导致永远不运行。位于 `integration-tests/src/test/java/org/example/algorithmdebug/integration/`，依赖真实 runtime/MCP 模块应在该模块 pom 明确加入。

| 链路 | 通过定义 |
|---|---|
| McpAnalysisLifecycleE2ETest | initialize/list/call→begin→input→source→观测→finalize→audit；完整真实装配，非手建 Control |
| SuccessfulUtEvidenceE2ETest | 无普通 Run；CodePath 与 JDWP 各成功采集；必要事实可确认 |
| FailedUtFingerprintE2ETest | 普通失败基准→同类失败采集→有效事件确认；不同失败/成功则线索 |
| SourceToRuntimeCausalLoopE2ETest | 源码定位→冻结检验→不完整→补采→上游变量→有界机制解释→归档完整 |
| McpRetryIdentityE2ETest | 同业务重试回放/新业务重跑/新连接 wire ID 重用不冲突 |
| McpTargetExecutionConcurrencyE2ETest | 真实跨进程锁+短写并发；无重复序列与死锁 |
| McpCancellationE2ETest | MCP cancellation 与 Server close 实际终止受管 JVM，不只是 token=true |
| KnowledgeIndependentCoreE2ETest | 同一核心证据，无知识与合法知识均能闭环 |
| ContradictoryHypothesisE2ETest | 有关反证/不同 Run 差异保留且错误解释不能确认 |
| HostCapabilitiesE2ETest | 真实 wire tools-only；最小能力与 Roots fallback 无伪依赖 |

真实动态测试需要 JDK/toolchain。公司无法提供时可先完成纯机制测试，但动态采集/宿主/质量门禁保持未完成；不允许“跳过=通过”。

## 4. 模型质量与重复稳定性

### 4.1 评测输入 E01

至少 10 个有可核查参考机制的复杂 Case，每类覆盖：成功结果异常、断言失败、异常失败、输入/上游状态导致、下游表象非根因、实体混淆、缺投影/UNKNOWN、真实反证、工具故障、复合贡献原因。

每个 Case 保存：脱敏输入/UT/代码版本、正确程序机制与核心变量、允许的等价解释、明显错误解释、需要哪些记录/源码、哪些业务结论不能确认。真值来自可执行夹具、确定性业务 Validator 或经团队确认的业务分析，不来自待测模型自评。

比较公司原 Skill+MCP 与目标架构时，保持相同模型版本/配置、输入、代码、已有工具能力和总预算。目标架构新增 Source Query 时报告能力差异，不能把其效果全部归因于 Coordinator。不得只挑有利样本，必须保留一组未用于调 Prompt 的留出 Case。

### 4.2 扰动与重复 E02

每个机制至少重复 5 次；额外覆盖提问改写、无关摘要排序、无关源码、知识缺失/误导、不同可用工具顺序。固定案例真值，但不要求模型 tool sequence 一致。记录 code/prompt/model/tool/policy/JDK 版本与预算。

用归一化“源码方法/表达式位置+实体+字段+观测值+适用 Run 范围”比较事实，不能直接比较随机 Artifact ID 或自然语言字符串。跨真实 Run 的稳定实体身份必须来自业务投影，不存在时不计算虚假的证据 Jaccard。

### 4.3 指标与发布门禁 E03

下表为本项目提出的首版验收门槛，不是已达到结果，也不是行业公认保证。公司需在实施前用真实基线评审冻结；不能验收时为了通过临时放宽。

| 指标 | 首版门槛 | 防止的误判 |
|---|---|---|
| 硬契约违规、串案、伪造事实、未清理目标进程 | 0 | 平均分高不能掩盖安全错误 |
| 不充分证据/已反驳解释被确认 | 0 | UNKNOWN/反证被硬解释 |
| 可回答 Case 的正确机制覆盖 | ≥90%，且不低于公司基线 | 一律 MISSING_EVIDENCE 伪装稳定 |
| 核心变量/上游源码定位 | 与参考机制对应，宏平均 F1 ≥0.85 | 只解释 Gantt 或末端方法 |
| 机制结论重复一致性 | ≥0.90，按相容机制/边界而非措辞 | 每问一次换核心原因 |
| 无关内容成为关键归因 | ≤0.10，且不高于基线 | 摘要注意力偏差 |
| 错误拒答/过度降级 | 可回答 Case 中 ≤0.10，且不高于基线 | 过严门禁损害分析能力 |

每项同时报告分子/分母、失败 Case、样本量和不确定性；50 次结果不是大规模泛化保证。准确性失败时先判明是源码覆盖、采集范围、数据关联、模型检验选择还是规则误拦截，再改对应部分，不一律增加更多 Prompt。

Gate 的 CONFIRMED 是事实/规则资格；Eval 的“机制答对”可以接受正确且证据边界明确的 BOUNDED_HYPOTHESIS。否则会为了分数逼模型把源码推断伪装成确定性事实。

## 5. 性能与故障注入

静态/热状态/预检/逐条见证：每项固定数据规模重复至少 20 次，报告 median/p95/最大值与峰值内存。使用同机器/JDK/输入/预算基线；新增控制开销的 p95 默认不得超过相同业务工具总耗时的 10%，且状态/有界证据解析不能随 Raw 全量字节线性无界增长。对极短读工具，单独报告绝对开销，不用比例制造误判。

所有数据量在 Plan/命名预算以内；扩展数据量接近硬上限时明确截断/拒绝，不能 OOM。真实 JDWP 暂停时间与 CodePath 输出量不能因本批改变而显著回归；不要仅凭一次耗时宣称提升。

故障注入点：operation claim 后、目标启动后、Raw 提交后、Artifact 登记前/后、调查 batch rename 前/后、Candidate 提交后、Decision 提交后、结果响应前。每点检验：读者只见已提交记录、唯一终态、保留诊断、无自动重跑、可证明恢复/明确 UNCERTAIN。

## 6. 执行命令与结果记录

本地受影响单测示例（新测试落地后执行；PowerShell 的 -D 参数需引用）：

```powershell
mvn -pl ada-core,algorithm-debug-runtime,algorithm-debug-mcp-server -am test "-Dtest=InvestigationApplicationServiceTest,ConclusionGateTest,AnalysisCoordinatorTest,McpResultMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -Pcodepath-launcher test
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

关键 E2E 通过根 test 确认新 `*E2ETest` 被实际发现；Surefire XML 必须逐模块统计，仅扫描根 Reactor 的模块，不递归混入旧 `.worktrees`/历史 reports。记录测试数、failure/error/skip 与 skip 原因。

动态与安装命令使用现有验证基线指定的脚本；公司版按真实脚本映射。质量重复 Eval 的 suite/runner 扩展在任务 P9 中落地，未实现前不能给出一个不存在的“已经跑过”的命令。

最终验收必须包含：代码路径审计、测试报告、真实 Tool/宿主轨迹、Case audit、性能结果、复杂机制评分、偏差/已知限制、回滚演练。只有单测通过时，只能宣称单测层级通过。
