# 当前能力与边界

更新日期：2026-09-06。

## 已实现

### OpenCode 与执行入口

- 安装 `algorithm-debug` Agent、Skill、Command 和 13 个 Custom Tool，不绑定固定 OpenCode 版本号。
- Custom Tool 经 JS Adapter 调用 `bin/ada.cmd`，Java CLI 只向 stdout 输出结构化 `ToolResponse`，运行日志写入 Workspace。
- 同一 OpenCode Runtime 内的普通 UT、CodePath 和 JDWP 目标执行严格串行；不提供跨会话锁。
- Tool 成功和失败均返回结构化状态、原因及下一步建议，模型结论直接返回用户，不默认归档。

### Case、算法输入、UT 与 Gantt

- 一个目标算法 UT 对应一个 Case；需要新确定性工作时在原 Case 中追加 Analysis。
- 从目标测试方法第一层源码定位唯一的 `String` 输入路径，文件名支持 `input.json` 或 `input_.json` 结尾。
- 输入首次按原名复制到 `case/input/`，后续 Analysis 复用并校验同一 Artifact。
- Maven Surefire 精确执行一个 JUnit 类或方法，归档退出码、stdout、stderr、Surefire XML 和结构化失败指纹。
- 成功普通 Run 从配置的 `${runDate}` Gantt 目录捕获一次新增 JSON，保留原文件名。动态 Collection 不复制 Gantt，也不比较 Gantt SHA。

### 静态分析

- 生成当前源码的有界 Method Catalog、源码锚点、直接调用边和多态候选边。
- Catalog 用于帮助模型选择动态采集点，不宣称是完整 JVM 调用图。
- Maven test classpath 无法解析时明确标记覆盖不完整，由 CodePath 或 JDWP 验证运行时事实。

### CodePath Plan v6

- Launcher 由本仓库维护，使用 Byte Buddy 仅对 Plan 中精确 `class#method(descriptor)` 安装 Advice；不再依赖第三方 CodePathTracer Snapshot 或 Kotlin 运行库。
- `TRACE` 保存按顺序的 enter/exit 事件并派生 `codepath-invocations.jsonl`，用于调用顺序和同次调用值关联。
- `AGGREGATE` 不保存逐调用明细，只保存方法计数、最近已选择祖先边、已选择方法深度、Scope 计数和显式投影值分布，适合高频、大型算法的第一轮发现。
- 参数和返回值投影只读取 Plan 明确声明的参数位置与有限字段路径；不猜测 wafer、job、step 等业务含义，不扫描容器或完整对象图。
- Scope 最多四个精确 `EQUALS` 条件；`scopeStartOrdinal` 与 `maxMatchedScopes` 可选择条件匹配后的连续调用窗口。
- 默认预算仍为 100000 个事件、16 MiB、5 分钟，硬上限为 1000000 个事件、50 MiB、20 分钟。优化依赖精确 Plan、Scope 和聚合模式，而不是盲目提高预算。
- AGGREGATE 每个投影最多保留 256 个不同值，全 Collection 最多 10000 个不同值；其余只计入 `otherCount` 并显式标记局限。
- 方法路径表示“最近已选择祖先”，深度表示“已选择方法深度”，不冒充完整 JVM 调用栈。

### JDWP

- 支持精确方法/行断点、显式局部变量、`this` 和有限普通实例字段路径。
- 条件与投影共用栈帧值路径规则，支持观察、匹配、采集三类计数和有界连续采样。
- 事件线程只在断点命中时短暂停止；值复制完成后恢复，再由同一 Collector 线程顺序写 JSONL。
- 不递归展开完整对象图、不执行方法、不猜测字段业务含义。

### Evidence 与模型读取

- Raw Trace 只读保存；Normalizer 确定性生成 Method Path Summary 或 JDWP Snapshot Summary。
- `evidence_query` 只读取已注册且 SHA 校验通过的动态 Artifact，并返回覆盖状态、局限、剩余结果和下一步动作。
- TRACE 支持 `SUMMARY/FILTER/WINDOW/COUNT`；JDWP 额外支持 `CHANGES`；AGGREGATE Summary 支持 `SUMMARY/COUNT`。
- AGGREGATE Summary 的 `COUNT` 可按方法、投影值或值状态查询。`otherCount` 表示未逐值保留的观测，不可据此证明某值不存在。
- 小字节预算下 Summary 会保留总数并显式返回省略数量，不会因明细过多丢失整个查询结果。
- Artifact SHA 只校验归档文件读取时未被替换或损坏，不证明业务结果相同。

## 保留边界

- 当前只支持一个目标 UT 对应一个算法输入文件。
- Java 工具不解释 Gantt 或参数的业务语义；LLM 结合算法输入、源码和动态事实进行解释。
- 反射、运行时生成代码和外部依赖分派可能在静态 Catalog 中保持未解析。
- AGGREGATE 不能证明调用顺序，也不能证明两个投影值属于同一次调用；这类问题必须缩小范围后使用 TRACE。
- 动态证据受事件、字节、时间、Scope、投影和值基数预算约束；超限只能作为部分证据。
- 当前只保证单个 OpenCode Runtime 内的目标执行顺序，不处理多个 OpenCode 会话同时运行同一目标 UT。
- Agent 不修改目标算法生产源码，不接管生产调度决策。

## 可靠性原则

- 失败 UT 的动态复现只比较结构化失败指纹；`MATCHED` 才能确认同类失败，`CHANGED/INCOMPARABLE` 只能作为线索。
- 成功 Gantt 独立归档，不作为动态采集门禁。
- 截断、零命中、投影不可读、值基数超限和结构不完整都必须显式返回，不伪装成完整证据。
- `case_audit` 校验控制 JSON、Artifact 身份、SHA 和目录结构，并报告损坏文件及空目录。
