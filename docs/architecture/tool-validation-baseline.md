# Algorithm Debug Agent 工具验证基线

- 状态：Verified
- 日期：2026-08-25
- 目标 Demo：验证机上的 `hellomvn` Maven/JUnit 5 算法项目
- 注意：验证机路径只是本次证据示例，实际路径由 `config/agent-settings.json` 和安装器配置。

## 1. 本文边界

本文只记录实际执行并观察到的事实。产品职责和交互以
[当前模块详细设计](algorithm-debug-agent-module-detailed-design-v1.md) 为准。

## 2. Java 和协议测试

根 Reactor 包含 18 个模块。2026-08-25 执行根 `mvn test`，19 个 Reactor project（包含根项目）
全部成功；跨模块 integration tests 为 9/9。

OpenCode JS Adapter、ToolResponse、DFX 和 Eval Grader 的 Node 测试为 44/44。

已经覆盖：

- 目标 UT 不存在的稳定错误码 `TARGET_TEST_NOT_FOUND`；
- ArtifactReference 路径、size 和 SHA 完整性拒绝；
- Run 成功、异常和断言失败；
- Gantt 新文件定位和不可变归档；
- CodePath/JDWP 独立采集；
- JDWP budget、completionReason、Manifest 和派生摘要；
- 失败目标动态重跑的结构化失败指纹；
- Case 状态驱动的应有文件审计；
- OpenCode JSONL 重复 tool snapshot 去重。

## 3. CodePath 验证事实

- 目标项目不需要增加 CodePath 依赖或修改原 UT。
- Agent 通过外部 JUnit Launcher 在新的 JVM 中运行同一目标 UT。
- Plan 使用精确 `className + methodName + descriptor`。
- Raw JSONL 保存方法 enter/exit、深度、线程、类和方法。
- Normalizer 输出有界 Method Path Summary。
- Validator 输出完成、预算和失败复现状态。
- CodePath 与 JDWP 相互独立。
- 成功采集 E2E：46 个 Case 文件，审计 expected=actual，Collection `SUCCESS`，
  baseline `NOT_COMPARED`，`evidenceUsable=true`。

已知限制：上游 Instrumentation 对未选择方法可能仍产生 Advice 调用开销；大型目标算法尚未完成专项
压力基线。

## 4. JDWP 验证事实

JDWP 源码由本仓库模块维护：

- `jdwp-collector-core`
- `jdwp-collector-adapter`
- `tools/jdwp-batch-collector`

已验证：

- Agent 启动 suspended Surefire JVM，Collector attach 后恢复；
- 已加载和 ClassPrepare 后加载类的精确断点安装；
- frame、locals、`this`、primitive/String/enum、数组和有界对象字段；
- hit、frame、depth、item、string、event、byte 和 timeout 预算；
- Raw JSONL、Collector Manifest、进程日志、Normalizer、Validator 和 Evidence；
- TARGET_FAILED 时的失败复现比较；
- Collector JAR 不需要 SHA，运行文件从 Agent 安装目录解析；
- Agent Plan 只登记一次，`collector-plan.json` 是 Collector 的独立运行时输入，不是重复 Plan
  Artifact。

成功采集 E2E：54 个 Case 文件，审计 expected=actual，Collection `SUCCESS`，
baseline `NOT_COMPARED`，`evidenceUsable=true`，无重复 Plan Artifact。

JDWP 使用 `SUSPEND_EVENT_THREAD`。断点命中时对应线程会短暂停顿用于读取状态，随后恢复；因此是
低影响而不是零影响。多线程算法需要额外一致性评估，当前产品不承诺全局原子快照。

## 5. 真实 OpenCode E2E

所有用例都由真实 OpenCode 会话调用已安装 Agent，而不是直接伪造 ToolResponse：

| Case | 结果 | Workspace 审计 | 交互审计 |
|---|---|---|---|
| passing-ut | PASS | 15/15 | PASS |
| missing-ut | PASS | 5/5 | PASS |
| missing-input | PASS | 18/18 | PASS |
| algorithm-loop-guard | PASS | 18/18 | PASS |
| assertion-failure | PASS | 20/20 | PASS |
| static-current-source | PASS | 17/17 | PASS |
| codepath-independent | PASS | 46/46 | PASS |
| jdwp-independent | PASS | 54/54 | PASS |
| artifact-integrity-rejection | PASS | 15/15；预期发现损坏 | PASS |

`artifact-integrity-rejection` 的 Workspace audit 按设计返回 false，且只报告
`ARTIFACT_SIZE_MISMATCH`；Eval 将“正确拒绝损坏文件”判为 PASS。

九个最终选定 Case 都没有空目录。零字节 stderr 文件表示对应进程确实没有 stderr，是原始流证据，
不是占位文件。

## 6. 安装验证

安装器可重复执行。它从仓库配置读取路径，复制 OpenCode Agent、Skill、Tools、JS Adapter 和 Java
发布物，并执行加载检查。OpenCode 版本不锁定；不兼容命令或插件加载失败时返回明确安装错误。

本次验证使用 OpenCode 1.18.x；这只记录验证环境，不构成版本绑定。

## 7. 尚未证明

- Gradle、TestNG 或一次选择多个 UT。
- 目标算法规模下的 CodePath/JDWP 长时间压力、吞吐和可接受扰动。
- 完整 Maven test classpath 下的全项目静态调用图。
- 多线程算法的跨线程一致快照。
- 自动领域知识生成。
- Java 侧 Gantt 业务语义解释。
