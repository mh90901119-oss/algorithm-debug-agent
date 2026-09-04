# Algorithm Debug Agent Full Repository Consistency Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全量审计并修复当前 Agent 的跨模块契约漂移、潜在缺陷、模型交互歧义和 Skill 工作流问题，同时保持目标算法无关性。

**Architecture:** 先建立当前实现基线和 13 个 Tool 的纵向追踪矩阵，再按 Maven 依赖方向审计各模块。所有行为修复由失败测试驱动，最后通过 Java、Node、安装脚本、Collector、Eval、真实 OpenCode E2E 和 Workspace 人工检查完成闭环。

**Tech Stack:** Java 21、Maven、JUnit 5、TypeScript/JavaScript、Node Test Runner、PowerShell、OpenCode、JSON Schema、CodePath Tracer、JDWP/JDI。

**Spec:** `docs/designs/2026-09-04-full-repository-consistency-audit-design.md`

## Global Constraints

- 不修改目标算法生产源码增加 Trace。
- 不把 Demo 或特定调度业务语义写入 Agent。
- 不引入文件锁、跨会话协调、新框架或无调用方抽象。
- Java 能确定性保证的约束必须由代码和测试保证，不能只写进 Skill。
- 每项行为修复先增加失败测试，再写最小实现，最后重构。
- Raw Trace 只读，派生数据不得伪造原始事实。
- 生产代码、配置和测试不得写死开发机绝对路径。
- 不覆盖 Case、Run、Plan、Collection、Evidence 或 Artifact 历史。
- 文档只描述当前实现；ADR 可保留历史，但必须标记被取代关系。

---

## Task 1: 建立审计基线和最终报告骨架

**Files:**
- Create: `docs/audits/2026-09-04-full-repository-consistency-audit.md`
- Inspect: `pom.xml`
- Inspect: `docs/current-capabilities.md`
- Inspect: `docs/algorithm-debug-workflow-and-artifacts.md`
- Inspect: `docs/architecture/tool-validation-baseline.md`
- Inspect: `docs/decisions/*.md`
- Inspect: `docs/designs/*.md`

**Produces:** 当前提交、模块、工具、Schema、脚本、测试和文档清单；后续 Finding 的唯一汇总位置。

- [ ] 记录 Git 提交、分支、Java、Maven、Node 和 OpenCode 版本。
- [ ] 记录 18 个默认 Maven 模块和可选 CodePath Launcher Profile。
- [ ] 记录 13 个 OpenCode Tool、全部 Schema 版本和 Workspace 文件类型。
- [ ] 记录当前安装配置、JDK 分离、Maven、Workspace、Gantt、Collector 和 Launcher 解析方式。
- [ ] 在审计报告中建立 `P0/P1/P2/P3` Finding 表，不提前填写推测性缺陷。
- [ ] 执行基线测试并保存命令、退出码和失败摘要。

Run:

```powershell
mvn -Pcodepath-launcher test
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

Expected: 当前基线结果被完整记录；任何失败先登记为基线 Finding，不立即修改代码。

- [ ] Commit audit baseline.

```powershell
git add docs/audits/2026-09-04-full-repository-consistency-audit.md
git commit -m "docs: establish repository audit baseline"
```

## Task 2: 建立 13 个 Tool 的跨层追踪矩阵

**Files:**
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`
- Inspect: `integrations/opencode/tools/algorithm-debug.ts`
- Inspect: `integrations/opencode/lib/ada-cli.mjs`
- Inspect: `integrations/opencode/lib/tool-runtime.mjs`
- Inspect: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Inspect: `ada-core/src/main/java/org/example/algorithmdebug/core`
- Inspect: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts`

**Produces:** 每个 Tool 从模型输入到 Workspace 和 ToolResponse 的可追踪链路。

- [ ] 为 `analysis_begin` 记录 Skill 规则、Tool 参数、CLI 命令、Application Service、控制文件和返回身份。
- [ ] 为 `case_inspect` 记录摘要边界、历史引用和不执行目标 UT 的保证。
- [ ] 为 `algorithm_input_capture` 记录输入识别、唯一性、复制、复用、SHA 校验和停止条件。
- [ ] 为 `case_audit` 记录控制文件、Artifact、日志、JSONL 和空目录检查。
- [ ] 为 `gantt_inspect` 记录 Artifact 类型、结构查询、分页和业务语义边界。
- [ ] 为 `run_test` 记录 Maven 命令、目标结果、工具结果、Gantt 和失败指纹。
- [ ] 为 `static_analyze` 记录 Method Catalog、Source Anchor、完整性和落盘位置。
- [ ] 为 `codepath_plan_create` 与 `codepath_collect` 记录 Plan、Launcher、Raw、Summary、Validation 和 Evidence。
- [ ] 为 `jdwp_plan_create` 与 `jdwp_collect` 记录 Plan v5、Collector、Raw v3、Summary v4、Validation 和 Evidence。
- [ ] 为 `artifact_read` 与 `evidence_query` 记录 Artifact 校验、支持类型、过滤、分页和字节预算。
- [ ] 对每条链路比较字段名、类型、必填性、默认值、枚举、版本和错误代码。
- [ ] 将不一致项登记 Finding，并标明模型可能采取的错误动作。

Expected: 每个 Tool 均可从 Skill 追踪到确定性后端和实际产物，不存在只在文档中出现的步骤。

## Task 3: 审计 Contracts 与 Schema

**Files:**
- Inspect/Modify when proven: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts`
- Inspect/Modify when proven: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts`
- Inspect/Modify when proven: `schemas`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Consumes:** Task 2 字段矩阵。

**Produces:** Java、JSON 和 TypeScript 之间一致的版本化契约。

- [ ] 对所有 Java Record 与对应 JSON Schema 执行字段级比较。
- [ ] 检查必填、可选、默认值、枚举、数值下限、集合上限和未知字段策略。
- [ ] 检查 `SchemaVersions` 与写入代码使用的版本完全一致。
- [ ] 检查 JDWP Plan v4/v5、Raw v2/v3、Summary v3/v4 的真实生产者和消费者。
- [ ] 检查没有生产者、消费者、迁移用途或兼容测试的旧 Schema。
- [ ] 检查 Case、Analysis、Run、Plan、Collection、Evidence 和 Artifact ID 是否可能混用。
- [ ] 检查 `ToolResponse` 是否能表达成功、目标失败、工具失败、部分数据和恢复动作。
- [ ] 对每个已确认漂移先写失败契约测试。
- [ ] 实施最小契约修复；破坏性变更必须升级主版本并补兼容测试。

Run:

```powershell
mvn -pl ada-contracts -am test
node --test integrations/opencode/test/*.test.mjs
```

Expected: 同一字段在 Java、Schema、TypeScript 中只有一种当前含义。

- [ ] Commit confirmed contract repairs.

```powershell
git add ada-contracts schemas integrations/opencode docs/audits/2026-09-04-full-repository-consistency-audit.md
git commit -m "fix: align agent contracts across runtimes"
```

## Task 4: 审计 Workspace、Case 和 Artifact 生命周期

**Files:**
- Inspect/Modify when proven: `case-management/src/main/java`
- Inspect/Modify when proven: `case-management/src/test/java`
- Inspect/Modify when proven: `ada-core/src/main/java/org/example/algorithmdebug/core/WorkspaceApplicationService.java`
- Inspect/Modify when proven: `ada-core/src/test/java/org/example/algorithmdebug/core/WorkspaceApplicationServiceTest.java`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 追加式、可审计、无越界和无空目录的 Case 数据生命周期。

- [ ] 验证 projectId 对同一规范化模块路径稳定，对不同模块不冲突。
- [ ] 验证同一目标 UT 复用 Case，新确定性工作只追加 Analysis。
- [ ] 验证所有 Run、Plan、Collection、Evidence 和 Artifact 归属正确 Analysis。
- [ ] 验证 Artifact 必须注册、SHA 校验通过且路径不能越出 Case。
- [ ] 验证写入采用临时文件和原子提交，失败不留下伪完整文件。
- [ ] 验证目录按需创建，`case_audit` 能发现空目录、未注册文件、损坏 JSONL 和 SHA 不匹配。
- [ ] 搜索并移除无现行用途的 Context 字段、路径、Schema、测试或文档残留。
- [ ] 验证 DFX 日志位于 Case；Case 尚未创建时只使用配置的 `unassigned` 目录。
- [ ] 对发现的问题先增加文件系统隔离回归测试，再修改实现。

Run:

```powershell
mvn -pl case-management,ada-core -am test
```

Expected: 审计夹具中无覆盖历史、路径逃逸、空目录和未注册产物。

## Task 5: 审计目标 UT、Maven 和 Gantt 链路

**Files:**
- Inspect/Modify when proven: `adapter-sdk/src/main/java`
- Inspect/Modify when proven: `debug-harness/src/main/java`
- Inspect/Modify when proven: `adapters/maven-junit-adapter/src/main/java`
- Inspect/Modify when proven: `ada-core/src/main/java/org/example/algorithmdebug/core`
- Inspect/Modify when proven: corresponding `src/test/java` directories
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 只执行目标 UT、正确区分目标结果与 Agent 故障的普通 Run。

- [ ] 验证 UT 不存在时不启动 Maven 并返回明确停止原因。
- [ ] 验证 Surefire 只执行指定测试类或测试方法。
- [ ] 验证 Java 21 Agent 与可配置目标 JDK 互不污染。
- [ ] 验证 stdout/stderr 有界捕获且不会造成进程管道阻塞。
- [ ] 验证超时、退出码、Surefire XML、测试数量和异常链一致。
- [ ] 验证业务异常、断言失败和未知失败不被强塞入错误分类。
- [ ] 验证工具启动失败不能产出目标算法结论。
- [ ] 验证普通 Run 只复制本次新建的一个 Gantt JSON 并保留原名。
- [ ] 验证 `${runDate}` 日期目录、无 JSON、多个 JSON、无效 JSON 和目录创建延迟。
- [ ] 验证 CodePath/JDWP 重跑不复制 Gantt，不比较 Gantt SHA。
- [ ] 验证失败指纹仅用于同 Analysis 的动态复现。
- [ ] 先增加失败测试，再修复确认的问题。

Run:

```powershell
mvn -pl adapter-sdk,debug-harness,adapters/maven-junit-adapter,ada-core -am test
```

Expected: 每种运行结果均有唯一、无歧义的结构化表达。

## Task 6: 审计静态分析和 Method Catalog

**Files:**
- Inspect/Modify when proven: `static-analysis/src/main/java`
- Inspect/Modify when proven: `static-analysis/src/test/java`
- Inspect/Modify when proven: `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Inspect/Modify when proven: `ada-core/src/test/java/org/example/algorithmdebug/core/StaticAnalysisApplicationServiceTest.java`
- Inspect/Modify when proven: `schemas/analysis/method-catalog-v3.schema.json`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 适合作为动态 Plan 索引、但不会冒充运行时事实的 Method Catalog。

- [ ] 验证主源码、测试源码和依赖 classpath 的发现边界。
- [ ] 验证重载方法 descriptor 唯一且与 CodePath Plan 使用一致。
- [ ] 验证 `DIRECT` 和 `POLYMORPHIC_CANDIDATE` 不会混淆。
- [ ] 验证 Maven test classpath 不完整时显式降级并列出未解析边界。
- [ ] 验证大型调用关系受预算限制且标记 `INCOMPLETE`。
- [ ] 验证 Source Anchor、源码行和源码 SHA 的每个字段都有当前消费方。
- [ ] 验证 Catalog 能支撑 CodePath 方法选择与 JDWP 可执行行选择。
- [ ] 验证 ToolResponse 不诱导模型把候选路径当成实际执行路径。
- [ ] 对确认缺陷增加 AST/解析夹具和回归测试后修复。

Run:

```powershell
mvn -pl static-analysis,ada-core -am test
```

Expected: Catalog 的完整性、边界和证据等级均可由模型正确识别。

## Task 7: 审计 CodePath 计划、采集和派生证据

**Files:**
- Inspect/Modify when proven: `method-path-spi/src/main/java`
- Inspect/Modify when proven: `method-path-codepathtracer/src/main/java`
- Inspect/Modify when proven: `tools/code-path-tracer-junit-launcher/src/main/java`
- Inspect/Modify when proven: `debug-plan-engine/src/main/java`
- Inspect/Modify when proven: `trace-normalizer/src/main/java`
- Inspect/Modify when proven: `trace-validator/src/main/java`
- Inspect/Modify when proven: `evidence-engine/src/main/java`
- Inspect/Modify when proven: corresponding `src/test/java` directories
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 可区分实际方法路径和关键标量值、可增量迭代的 CodePath 证据。

- [ ] 验证 Plan 只接受 Method Catalog 的精确 `class#method(descriptor)`。
- [ ] 验证 `scopeMethodKey` 对重复主循环 invocation 的分组行为。
- [ ] 验证 `arg[n](.field)*`、`return(.field)*` 和 required 投影契约。
- [ ] 验证单个投影不可读不会丢弃整个方法调用事件。
- [ ] 验证 enter/exit 配对、调用顺序、调用次数、路径变体和未配对事件处理。
- [ ] 验证事件、字节、深度、字符串和超时预算在所有派生产物中一致。
- [ ] 验证 Normalizer 只整理 Raw，Validator 不推断业务语义。
- [ ] 验证 `evidence_query` 可按 methodRef、valueName、scalarValue 和窗口有界查询。
- [ ] 验证增量 Plan 必须引用同 Case 既有 Evidence，且不能无理由原样重复。
- [ ] 验证 Tool/Collector 失败不会被解释为目标 UT 失败。
- [ ] 对发现的问题先增加 Launcher、Normalizer、Validator 或 Evidence 回归测试。

Run:

```powershell
mvn -Pcodepath-launcher -pl method-path-spi,method-path-codepathtracer,tools/code-path-tracer-junit-launcher,debug-plan-engine,trace-normalizer,trace-validator,evidence-engine,ada-core -am test
```

Expected: Raw、Invocation、Summary、Validation 和 Evidence 的计数与关联一致。

## Task 8: 审计 JDWP Plan v5 和 Collector 4.0

**Files:**
- Inspect/Modify when proven: `jdwp-collector-core/src/main/java`
- Inspect/Modify when proven: `jdwp-collector-adapter/src/main/java`
- Inspect/Modify when proven: `tools/jdwp-batch-collector/src/main/java`
- Inspect/Modify when proven: `debug-plan-engine/src/main/java`
- Inspect/Modify when proven: `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/JdwpSnapshotNormalizer.java`
- Inspect/Modify when proven: `trace-validator/src/main/java`
- Inspect/Modify when proven: `evidence-engine/src/main/java`
- Inspect/Modify when proven: corresponding `src/test/java` directories
- Inspect/Modify when proven: `schemas/collection/jdwp-plan-v5.schema.json`
- Inspect/Modify when proven: `schemas/trace/jdwp-raw-event-v3.schema.json`
- Inspect/Modify when proven: `schemas/trace/jdwp-snapshot-summary-v4.schema.json`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 精确、有界、低影响且能明确表达部分数据的 JDWP 证据。

- [ ] 验证旧 Collector Manifest 被明确拒绝且不会部分执行。
- [ ] 验证断点方法、descriptor、源码行和可执行位置一致。
- [ ] 验证最多四个 AND 条件的路径、类型和比较规则全链路一致。
- [ ] 验证条件与投影使用同一有界值路径读取器。
- [ ] 验证 `maxObservedHits`、`maxCapturedHits`、首批采样和周期采样。
- [ ] 验证 observed、matched、captured、unavailable 计数相互独立且准确。
- [ ] 验证 `CAPTURED/TRUNCATED/REFERENCE_ONLY/UNAVAILABLE` 在 Raw、Summary、Query 和 Tool Schema 中一致。
- [ ] 验证对象、数组、集合和 Map 不被默认递归展开。
- [ ] 验证事件线程在读取完成或异常后均被恢复。
- [ ] 验证同步 JSONL 写入在正常退出、超时和异常时不会丢失已确认事件。
- [ ] 验证 attach、目标 JVM 退出、Collector 退出、超时和清理状态均进入 Manifest。
- [ ] 验证零 captured 且 unavailable/truncated 时不会被上层解释为状态不存在。
- [ ] 对确认缺陷先增加 JDI 单元测试、Collector 集成测试或 loopback 回归测试。

Run:

```powershell
mvn -pl jdwp-collector-core,jdwp-collector-adapter,tools/jdwp-batch-collector,debug-plan-engine,trace-normalizer,trace-validator,evidence-engine,ada-core -am test
.\scripts\verify-jdwp-loopback.ps1
```

Expected: 条件命中、投影状态、预算和 Manifest 在真实 loopback 采集中一致。

## Task 9: 审计 Normalizer、Validator、Evidence 和查询闭环

**Files:**
- Inspect/Modify when proven: `trace-normalizer/src/main/java`
- Inspect/Modify when proven: `trace-validator/src/main/java`
- Inspect/Modify when proven: `evidence-engine/src/main/java`
- Inspect/Modify when proven: `case-management/src/main/java/org/example/algorithmdebug/casecore/RegisteredEvidenceQuery.java`
- Inspect/Modify when proven: corresponding `src/test/java` directories
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 不重复、不伪造、可有界读取且能判断充分性的派生证据。

- [ ] 逐字段验证 Raw 到 Summary 的确定性映射。
- [ ] 检查所有派生文件是否有实际消费方和 Artifact 注册。
- [ ] 检查 Validation 对截断、冲突、失败基线和缺失值的处理。
- [ ] 检查 Sufficiency 是否可能在关键维度缺失时返回 `SUFFICIENT`。
- [ ] 检查 Evidence 是否引用正确 Plan、Collection、Raw 和 Summary。
- [ ] 检查 Query 结果只作为临时有界视图，不重复归档为业务证据。
- [ ] 检查输入事实、源码推断、运行事实和模型假设是否保持分类边界。
- [ ] 为每项错误映射增加最小 Raw 夹具和失败回归测试。

Run:

```powershell
mvn -pl trace-normalizer,trace-validator,evidence-engine,case-management -am test
```

Expected: 每个 Evidence Fact 均可追溯，不存在无来源确认性结论。

## Task 10: 审计 ada-core、CLI、日志和单会话顺序

**Files:**
- Inspect/Modify when proven: `ada-core/src/main/java`
- Inspect/Modify when proven: `algorithm-debug-cli/src/main/java`
- Inspect/Modify when proven: `ada-core/src/test/java`
- Inspect/Modify when proven: `algorithm-debug-cli/src/test/java`
- Inspect: `bin/ada.cmd`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 单一 ToolResponse stdout、完整 cause、正确日志和无重叠目标执行。

- [ ] 验证每个 CLI 命令只进入一个明确 Application Service。
- [ ] 验证 stdout 只包含一个可解析 ToolResponse，日志只写文件。
- [ ] 验证错误代码、英文消息、cause 和执行阶段没有丢失。
- [ ] 验证目标异常不会被 CLI 启动异常包装。
- [ ] 验证 Java、Maven、Launcher、Collector 缺失时给出可执行恢复信息。
- [ ] 验证单 OpenCode Runtime 中 Run、CodePath、JDWP 的重叠请求在启动第二个进程前被拒绝。
- [ ] 验证拒绝码为交互顺序错误，Skill 不会自动重试。
- [ ] 验证正常串行调用不被误拒绝，不增加文件锁或跨进程协调。
- [ ] 对所有模糊 catch、吞异常和错误归因先增加回归测试后修复。

Run:

```powershell
mvn -pl ada-core,algorithm-debug-cli -am test
```

Expected: 模型可仅依赖 ToolResponse 区分目标结果、Agent 故障和恢复动作。

## Task 11: 审计 OpenCode Tool、Agent Prompt 和 Skill

**Files:**
- Inspect/Modify when proven: `integrations/opencode/tools/algorithm-debug.ts`
- Inspect/Modify when proven: `integrations/opencode/lib/ada-cli.mjs`
- Inspect/Modify when proven: `integrations/opencode/lib/tool-runtime.mjs`
- Inspect/Modify when proven: `integrations/opencode/agents/algorithm-debug.md`
- Inspect/Modify when proven: `skills/algorithm-debug/SKILL.md`
- Inspect/Modify when proven: `integrations/opencode/test`
- Inspect/Modify when proven: `agent-evals`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 不写死轮数、不会误判错误、能根据证据缺口选择最小工具动作的模型工作流。

- [ ] 逐条比较 Skill、Agent Prompt、Tool description 和 Java 校验规则。
- [ ] 将权限和最小入口规则限定在 Agent Prompt。
- [ ] 将假设、证据选择、充分性和停止条件限定在 Skill。
- [ ] 将参数结构和单次调用语义限定在 Tool Schema。
- [ ] 将可确定性执行的约束保留在 Java，不依赖提示词保证。
- [ ] 验证首次分析、可直接回答追问、新 Analysis 和代码已修改四种对话路径。
- [ ] 验证输入边界、UT 不存在和工具失败会停止错误方向分析。
- [ ] 验证动态采集只针对明确证据缺口，不存在固定轮数和无理由重复。
- [ ] 验证 CodePath 与 JDWP 的选择、串行切换和 Evidence lineage。
- [ ] 验证模型不会把静态候选、输入事实、截断数据或不可用值提升为运行事实。
- [ ] 验证最终回答包含完整 Case/Analysis 路径、实际使用能力、Claim 分类和完整 Evidence ID。
- [ ] 为每个已确认误导点增加 Tool 契约测试或 Eval Case 后再修改文本。

Run:

```powershell
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

Expected: 每个模型可见状态都对应唯一合理的下一步动作或停止条件。

## Task 12: 审计构建、安装、卸载和配置

**Files:**
- Inspect/Modify when proven: `scripts/build-agent.ps1`
- Inspect/Modify when proven: `scripts/install-opencode.ps1`
- Inspect/Modify when proven: `scripts/uninstall-opencode.ps1`
- Inspect/Modify when proven: `scripts/verify-opencode-installer.ps1`
- Inspect/Modify when proven: `scripts/verify-ada-launcher.ps1`
- Inspect/Modify when proven: `scripts/verify-jdwp-loopback.ps1`
- Inspect/Modify when proven: `config/agent-settings.json`
- Inspect/Modify when proven: `schemas/config/agent-settings-v1.schema.json`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 可重复安装、卸载、重新安装且不污染其他 OpenCode 配置的 Windows 11 安装链。

- [ ] 检查脚本、生产配置和测试是否写死本机或 Demo 绝对路径。
- [ ] 检查默认路径打印与用户可修改配置一致。
- [ ] 检查构建产物在安装前完整验证。
- [ ] 检查重复安装和重复卸载幂等。
- [ ] 检查卸载只删除 ownership manifest 中仍匹配安装 Hash 的文件。
- [ ] 检查 OpenCode 既有 package.json、插件、模型和 Workspace 不被删除。
- [ ] 检查缺失 `@opencode-ai/plugin` 时的确定性补齐和既有版本保留。
- [ ] 检查安装后按能力发现 Agent、Skill、Command 和 13 个 Tool，不绑定 OpenCode 版本。
- [ ] 对确认问题先扩展隔离 Profile 测试，再修改脚本。

Run:

```powershell
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

Expected: 隔离环境和当前真实 OpenCode 配置均能完成卸载、安装、重复安装和 Check。

## Task 13: 执行横向缺陷扫描和最小化重构

**Files:**
- Inspect: all production and test source under repository Maven modules
- Inspect: `integrations/opencode`
- Inspect: `scripts`
- Modify only files referenced by confirmed Findings
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 跨模块审计不容易发现的文件系统、进程、JSON、预算、异常和安全缺陷清单。

- [ ] 搜索静态可变全局状态、空 catch、未保留 cause、调试输出和无上下文 TODO。
- [ ] 搜索未限制集合、字符串、字节、事件、命中、耗时和对象深度的采集路径。
- [ ] 搜索直接文件覆盖、非原子写入、路径拼接和未校验相对路径。
- [ ] 搜索外部进程缺少超时、退出码、stdout/stderr 消费或 finally 清理。
- [ ] 搜索 Schema 常量、枚举和 Tool 类型的重复定义。
- [ ] 搜索没有生产者、没有消费者、仅测试引用或仅文档引用的字段和类。
- [ ] 搜索中文程序输出、敏感绝对路径和未脱敏日志。
- [ ] 将每个真实问题登记 Finding；纯风格问题不得触发大范围修改。
- [ ] 为 P0/P1/P2 行为问题先写失败测试并逐项修复。
- [ ] 只删除已证明没有运行时、兼容、测试和文档用途的代码。

Expected: 所有删除和重构都能指向明确 Finding，不出现无关代码重写。

## Task 14: 执行完整 Java、Node 和工具验证

**Files:**
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 修复后完整自动化验证记录。

- [ ] 运行完整 Java Reactor 和可选 CodePath Profile。
- [ ] 运行 OpenCode Adapter 与 Eval Harness 测试。
- [ ] 运行构建和隔离安装器验证。
- [ ] 在可独立 Maven 执行目标 UT 的算法模块运行 Launcher doctor。
- [ ] 运行真实 JDWP loopback attach。
- [ ] 记录每条命令、退出码、测试总数、失败数和阻塞原因。

Run:

```powershell
mvn -Pcodepath-launcher test
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\verify-jdwp-loopback.ps1
```

Expected: 所有可执行检查通过；不可执行检查在报告中保留具体环境原因和剩余风险。

## Task 15: 执行 Smoke、Quality 和真实 OpenCode E2E

**Files:**
- Inspect/Modify when a regression is proven: `agent-evals`
- Inspect/Modify when a regression is proven: `integrations/opencode`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 模型真实使用 Tool 的顺序、判断和 Workspace 闭环证据。

- [ ] 执行 Smoke Suite 的 10 个场景。
- [ ] 执行 Quality Suite 的 50 个场景。
- [ ] 覆盖 UT 不存在、零输入、多输入、输入缺失、成功 Gantt、业务异常、断言失败和工具失败。
- [ ] 覆盖静态不完整、CodePath 截断、投影缺失、JDWP 条件未匹配、引用值和动态失败指纹变化。
- [ ] 覆盖证据充分时停止、第二轮增量 Plan、重叠执行拒绝、普通追问和代码修改后的新 Analysis。
- [ ] 对每个 Case 检查 Eval 结果、Tool 顺序、执行次数、Plan intent、Evidence lineage 和最终回答。
- [ ] 对任何失败先判断 Agent 缺陷、Eval 缺陷、模型不稳定或外部环境，再决定修改位置。
- [ ] 修改 Agent 或 Eval 后重跑受影响 Case 和完整 Suite，不接受只修 golden 文本。

Run:

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
.\scripts\run-agent-evals.ps1 -Suite Quality
```

Expected: Harness 能发现错误工具顺序、错误证据引用、遗漏产物、重叠执行和不合格最终回答。

## Task 16: 逐 Case 审计 Workspace 与日志

**Files:**
- Inspect: configured Agent Workspace generated by Task 15
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 自动审计与人工产物检查一致的 E2E 证明。

- [ ] 检查 `case.json` 和每个 `analysis-request.json`。
- [ ] 检查输入只复制一次、保留原名且 Artifact 注册有效。
- [ ] 检查每个普通 Run 的 request、outcome、fingerprint 和 Gantt。
- [ ] 检查每个 CodePath/JDWP Plan、Collection、Raw、Manifest、Validation、Derived 和 Evidence。
- [ ] 检查所有 ArtifactReference 的路径、大小和 SHA。
- [ ] 检查 `interaction.jsonl` 中的 Tool 顺序和关联 ID。
- [ ] 检查 `logs/agent-YYYY-MM-DD.log` 中的未解释异常和工具故障。
- [ ] 检查空文件、空目录、未注册文件和历史覆盖。
- [ ] 比较 `case_audit` 与人工检查结果；不一致时登记 P1 Finding 并回到对应模块修复。

Expected: 每个存在的文件都有生产者、用途和消费方；未发生阶段不生成空目录。

## Task 17: 收敛 Skill、文档和最终能力声明

**Files:**
- Modify when behavior changed: `skills/algorithm-debug/SKILL.md`
- Modify when behavior changed: `integrations/opencode/agents/algorithm-debug.md`
- Modify when behavior changed: `integrations/opencode/README.md`
- Modify: `README.md`
- Modify: `docs/current-capabilities.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/architecture/tool-validation-baseline.md`
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 与最终代码和验证结果一致的唯一当前文档集。

- [ ] 更新当前流程、Schema、Workspace、错误闭环和能力边界。
- [ ] 删除被当前设计吸收且不再有历史决策价值的阶段性草稿。
- [ ] 保留 ADR，并为被替代决策写明取代关系。
- [ ] 删除失效命令、旧 Tool 数量、旧路径和未经验证能力声明。
- [ ] 确保示例绝对路径明确标为示例，并同时给出配置方式。
- [ ] 在最终审计报告列出全部 Finding、修复提交、测试、E2E、剩余风险和未执行项。
- [ ] 执行文档链接、Schema 引用、Tool 名称和版本引用检查。

Expected: 新用户只阅读 README、安装手册、Skill 和当前能力文档即可得到与代码一致的行为。

## Task 18: 最终双重审计与交付

**Files:**
- Modify: `docs/audits/2026-09-04-full-repository-consistency-audit.md`

**Produces:** 可签收的全仓库审计结论。

- [ ] 第一轮按 13 个 Tool 纵向复查所有链路，确认每项输入、错误、产物和下一步闭环。
- [ ] 第二轮按 Maven 模块横向复查所有 Finding，确认没有遗漏测试、文档或兼容影响。
- [ ] 确认所有 P0/P1 已关闭。
- [ ] 确认每个保留 P2/P3 都有明确原因、影响和后续条件。
- [ ] 确认没有生成空模块、空目录、未使用接口或推测性功能。
- [ ] 确认最终报告回答可靠能力、证据可信边界、Agent/目标失败区分和模型决策依据。
- [ ] 执行最终完整验证，不以编译通过替代行为和 E2E 验证。

Run:

```powershell
mvn -Pcodepath-launcher test
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\verify-jdwp-loopback.ps1
.\scripts\run-agent-evals.ps1 -Suite Smoke
.\scripts\run-agent-evals.ps1 -Suite Quality
```

Expected: 最终审计报告中的每项完成声明都有命令输出、测试结果、Workspace 产物或代码证据支持。

- [ ] Commit final audit and verified repairs.

```powershell
git add README.md docs skills integrations schemas scripts ada-contracts adapter-sdk case-management debug-harness static-analysis method-path-spi method-path-codepathtracer debug-plan-engine jdwp-collector-core jdwp-collector-adapter tools trace-normalizer trace-validator evidence-engine ada-core algorithm-debug-cli adapters integration-tests agent-evals
git commit -m "fix: complete repository consistency audit"
```

