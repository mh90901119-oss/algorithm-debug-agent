# 当前能力与边界

更新日期：2026-09-29。

## 已实现

### 可移植子 Agent 与 MCP 入口

- Canonical Agent Definition Profile 2.0、Canonical Prompt 和 Capability Manifest 共同定义宿主无关子 Agent。
- Java 原生 stdio MCP Server 暴露 17 个基线 Tool；4 个 Resource 和 3 个 Prompt 是可选宿主增强。
- Qwen CLI Adapter 生成一个子 Agent 和一个 `algorithm-debug` MCP Server 配置；OpenCode 保留为兼容入口。
- Capability Manifest 是宿主工具权限唯一快照，构建测试强制其与 Java Catalog 一致。
- 每个 Tool 都经过 Dispatcher 与 Analysis Coordinator；模型跳步、重复、越权或使用过期修订时得到结构化拒绝。
- CLI 与 MCP 共用 `algorithm-debug-runtime`；CLI 主要用于人工、CI、诊断和迁移。

### Coordinator、调查状态与完成门禁

- `analysis_begin` 原子建立 Problem Frame、Case/Analysis 身份和控制状态。
- Investigation Journal 追加保存竞争假设、Evidence Gap、冻结 Predicate、三值 Observation 与支持/反证关系。
- `analysis_status` 从归档状态投影 `allowedActions`、义务和当前允许的最高结论等级。
- 写动作使用 operationId 实现幂等，并通过 Project/Case 文件锁实现跨 MCP/CLI 进程互斥。
- `analysis_finalize` 校验 CausalChain、引用资格、反证、竞争假设、覆盖、截断和失败指纹，返回服务端
  `ConclusionFinalization(candidate, decision)`。
- Gate 状态只有 `CONFIRMED`、`BOUNDED_HYPOTHESIS` 和 `MISSING_EVIDENCE`；工具/环境失败与预算耗尽作为过程限制记录。

### 可选算法知识

- `knowledgeDirectory` 可以未配置、不存在或为空，不影响完整调查闭环。
- Host Adapter 只读取非符号链接目录中的 UTF-8 Markdown，按 Agent Definition 的文件数、深度、单文件和总字节预算加载。
- 注入顺序按相对路径稳定排序；每段记录相对路径、SHA-256 和字节数，正文只进入生成的子 Agent Prompt。
- 知识只能帮助术语理解、候选假设和源码查询选择，不能成为 Evidence、改变 Predicate 或绕过 Conclusion Gate。

### Case、算法输入、UT 与 Gantt

- 一个目标算法 UT 对应一个 Case；需要新确定性工作时在原 Case 中追加 Analysis。
- 从目标测试方法第一层源码定位唯一的 `String` 输入路径，文件名支持 `input.json` 或 `input_.json` 结尾。
- 输入首次按原名复制到 `case/input/`，后续 Analysis 复用并校验同一 Artifact。
- Maven Surefire 精确执行普通基准 UT，归档退出码、stdout、stderr、Surefire XML 和结构化失败指纹。
- 成功普通 Run 从配置的 `${runDate}` Gantt 目录捕获一次新增 JSON；成功场景不要求额外失败基准。
- 动态 Collection 重跑目标 UT，但不复制 Gantt。失败 UT 只有结构化失败指纹 `MATCHED` 才能确认复现同类失败。

### 静态分析与源码查询

- 生成有界 Method Catalog、源码锚点、直接调用边和多态候选边。
- `source_query` 支持 METHOD、CALLERS、CALLEES、REACHABLE_PATH、SOURCE_WINDOW 和 SEARCH_SYMBOL，并返回预算、完整性与 provenance。
- 静态关系只支持 `SOURCE_INFERENCE`；不能把可达性或源码分支升级成本次运行事实。

### CodePath

- 本仓库 Launcher 使用 Byte Buddy 对 Plan 中精确 `class#method(descriptor)` 安装 Advice，不修改磁盘 class 或生产源码。
- Launcher 在独立受控 JVM 中使用 Maven 解析的测试 classpath 和 JUnit Platform 执行目标测试方法。
- `TRACE` 保存有界 enter/exit 事件，用于顺序与同次调用值关联；`AGGREGATE` 在运行时只保留方法、边、深度、Scope 和投影分布。
- 参数/返回值投影只读取 Plan 声明的有限标量字段路径；Scope 支持最多四个精确条件及匹配后的 ordinal 窗口。
- 方法路径表示最近已选择祖先，深度表示已选择方法深度，不冒充完整 JVM 调用栈。

### JDWP

- 支持精确方法/行断点、显式局部变量、`this` 和有限普通实例字段路径。
- 条件与投影共用值路径规则，区分 observed、matched、captured 与 unavailable 计数并支持有界采样。
- Raw JSONL 归档实际快照；Normalizer 生成稳定摘要并保留 raw Artifact、JSONL 行号和 sequence provenance。
- `sequence` 是 Collector 写出记录的顺序，可能因筛选、采样或预算不连续，不是业务步骤号。
- Collector 不递归展开完整对象图、不执行方法、不猜测字段业务含义。

### Evidence 与有界读取

- Raw Trace 只读保存；Normalizer 和 Validator 确定性生成 Method Path 或 JDWP Snapshot Evidence。
- `evidence_query` 只读取已注册且 SHA 校验通过的动态 Artifact，并报告覆盖、截断、剩余结果和下一步动作。
- TRACE 支持 `SUMMARY/FILTER/WINDOW/COUNT`；JDWP 额外支持 `CHANGES`；AGGREGATE 支持 `SUMMARY/COUNT`。
- `NO_MATCH + COMPLETE` 仅证明查询精确范围内未匹配；`PARTIAL/UNKNOWN` 不能证明不存在。
- Artifact SHA 只证明归档字节未被替换或损坏，不证明业务结果相同。

## 保留边界

- 当前只支持一个目标 UT 对应一个算法输入文件。
- Java 工具不解释 Gantt、字段或参数的业务语义；LLM 必须结合输入、源码锚点和动态事实进行解释。
- 反射、运行时生成代码和外部依赖分派可能在静态 Catalog 中保持未解析。
- AGGREGATE 不证明调用顺序或多个投影属于同一次调用；此类问题需缩小范围后使用 TRACE。
- 动态证据受事件、字节、时间、Scope、投影和值基数预算约束；超限只能作为部分证据。
- MCP Server 不调用模型、不持有模型凭据、不接管生产调度，也不为采集修改目标算法生产源码。
- 当前正式宿主 Adapter 为 Qwen CLI；第二真实宿主仍需以其公开契约完成兼容与同一 Eval 验证。

## 可靠性原则

- 失败 UT 的动态复现只比较结构化失败指纹；`MATCHED` 才确认同类失败，`CHANGED/INCOMPARABLE` 只能作为线索。
- 成功 Gantt 独立归档，不作为动态采集门禁。
- FALSE 是反证；UNKNOWN、截断、零命中、不可读和覆盖不完整不得被解释为支持。
- Candidate、Decision、Operation、Investigation、Run、Collection、Evidence 和 Artifact 追加保存，不覆盖历史。
- `case_audit` 校验控制文档、Artifact 身份、SHA 和目录结构；DFX 日志只用于排障，不是算法 Evidence。
