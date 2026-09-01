# 当前能力与边界

更新日期：2026-09-01。

## 已实现

### OpenCode 集成

- 安装 `algorithm-debug` Agent、Skill、Command 和一个包含 13 个能力的 Custom Tool。
- 不绑定 OpenCode 版本号；安装和检查阶段以 Agent、Skill、Tool 的实际发现结果判断兼容性。
- JS Adapter 将 Tool 请求转换为 `bin/ada.cmd` 的 Java CLI 调用，并将结构化结果返回 LLM。
- Case 内交互写入 `interaction.jsonl`；Java 日志写入 Case 的 `logs/`。

### Case 与算法输入

- 根据规范化的目标算法模块路径生成稳定 `projectId`。
- 同一个目标 UT 可在同一 Case 下追加多个 `analysisId`、Run、Collection 和 Evidence。
- 只分析 UT 第一层源码中的 `String` 字面量或可确定拼接值。
- 唯一输入必须以 `input.json` 或 `input_.json` 结尾；零个、多个、无法解析或文件不存在时停止并返回结构化错误。
- 首次捕获按原名复制到 `case/input/`，注册 Artifact；后续分析复用并校验内容未变化。

### UT 与 Gantt

- 通过 Maven Surefire 精确执行一个 JUnit 5 类或方法，捕获退出码、stdout、stderr 和 Surefire 结果。
- 目标 UT 不存在时明确返回，不强行采集。
- 目标代码异常与断言失败均保留结构化失败指纹；Agent/环境故障不会伪装成算法结论。
- 成功的普通 Run 从 `resultJsonDirectory` 捕获本次新增或变化的 JSON，保留原名并注册 Artifact。
- `${runDate}` 支持 `yyyy-MM-dd` 日期目录。
- 动态采集 Run 不捕获 Gantt；成功 Gantt 不做跨运行 SHA 一致性门禁。

### 静态和动态证据

- 静态分析生成当前源码的方法目录、调用边和未解析边界；产物有界，不声称等价于完整 Maven test classpath 的全程序调用图。
- CodePath 采集方法进入/退出和调用路径，适合验证真实分派与执行顺序。
- JDWP Collector 由 Agent 仓源码维护，支持断点、栈帧、局部变量、`this`、有界字段展开和结构化值路径条件。
- JDWP 分别限制观察命中、条件匹配和实际快照数量，并报告预算或不可用原因。
- 每个动态 Plan 必须携带 `questionToAnswer`、`hypothesis`、`expectedObservations` 和 `basedOnEvidenceIds`。
- Raw Trace 只读保存；Normalizer、Validator 与 Evidence Engine 确定性地产生摘要、校验和证据充分性结果。

### Eval

- Harness 启动真实 OpenCode 会话并解析 JSONL Tool Trace。
- 确定性 Grader 检查 Tool 顺序、调用次数、归档产物、Plan 意图、Evidence 谱系、条件化 JDWP 和答案模式。
- Smoke Suite 共 10 个 Case，包括成功、输入异常、算法异常、断言失败、工具失败、静态分析、CodePath、JDWP、完整性和跨实体因果场景。

## 有意保留的边界

- 当前只支持一个 UT 对应一个算法输入文件。
- Java 工具不解释 Gantt 业务语义；`gantt_inspect` 只提供有界 JSON 结构访问，因果解释由 LLM 完成。
- 静态分析基于源码与可解析类型信息；复杂反射、运行时生成和外部依赖分派可能标记为未解析，需要 CodePath/JDWP 验证。
- JDWP 条件只能读取命中栈顶帧中可见的局部变量、`this` 及其有界实例字段路径；不执行任意表达式或方法。
- 动态证据受超时、命中、对象深度、字节数和事件数预算约束。超限返回 `PARTIAL` 或明确原因，不伪装为完整证据。
- 领域术语与策略知识不内置在 Java Collector。用户可通过 OpenCode 上下文或额外 Skill 提供知识，且必须与运行证据区分。
- Agent 不修改目标算法生产源码，不接管生产调度决策。

## 当前可靠性原则

- Artifact SHA 只验证已注册文件读取期间未被替换或损坏，不证明业务结果相同。
- 失败 UT 的动态复现只比较结构化失败指纹；`MATCHED` 可确认同类失败，`CHANGED/INCOMPARABLE` 仅作为线索。
- 确认性结论必须通过 Evidence Sufficiency 检查；证据截断、冲突或缺失必须显式呈现。
- Case 审计拒绝缺失控制文件、无效 Artifact、非法交互日志、未跟踪文件和空目录。
