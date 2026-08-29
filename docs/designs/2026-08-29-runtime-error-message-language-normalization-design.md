# 生产运行时错误消息英文化设计

## 1. 问题与目标

当前文件日志事件名已经使用英文，但历史生产 Java 代码仍包含中文异常、失败摘要和诊断字符串。这些文本可能进入 `ToolResponse`，也可能在 ASCII 日志脱敏时退化为 `?`，导致用户输出和 DFX 日志不可稳定检索。

本次修复目标：

- 所有 `src/main/java` 中可能成为运行时值的字符串和文本块不得包含汉字。
- 保留稳定错误码、异常类型、控制流、Schema 和现有日志路由。
- 中文 Javadoc、行内注释、设计文档和 Skill 保持不变。
- 不引入日志框架、翻译依赖或新的运行时依赖。

## 2. 实施边界

需要修改：

- 构造异常的消息。
- `ToolResponse`、失败诊断、Manifest、Summary 和 Validator Finding 的运行时文本。
- 由生产代码写入 stdout、stderr、Workspace 或 DFX 日志的常量文本。
- 因生产消息变化而失效的测试断言。

不修改：

- 错误码和枚举值。
- 中文注释、Javadoc 和团队文档。
- 目标 UT 自身输出；Agent 只保证自身生产代码生成的文本。
- 第三方进程原始 stdout/stderr；这些内容必须原样归档作为证据。

## 3. 确定性门禁

在 `integration-tests` 增加源码级测试，扫描仓库全部生产 Java 文件。扫描器识别普通字符串和文本块，跳过行注释、块注释和字符字面量；任何运行时字符串中的汉字都会导致测试失败，并报告文件和行号。

该门禁不尝试判断文本是否最终可达，因为不可达的中文生产字符串同样会造成后续回归风险。测试不使用正则直接扫描整行，避免把中文注释误判为运行时消息。

## 4. 兼容性与风险

- JSON 字段、错误码和异常类型不变，因此没有 Schema 破坏。
- 只改变人类可读消息，依赖中文消息全文的调用方需要改为依赖稳定错误码；这也是既有契约要求。
- 测试期望文本同步更新为英文，禁止为了通过门禁删除异常校验。
- 原始目标算法输出不翻译，避免修改证据语义。

## 5. 验证

- 门禁测试在修复前失败、修复后通过。
- 执行根 Maven 全量测试。
- 执行 OpenCode Adapter 和 Eval Harness Node 测试。
- 重新构建 Agent，并验证一个失败命令的 `ToolResponse` 和落盘异常日志不含汉字且保留调用栈。


## Implementation and verification record

- Normalized 741 unique production Java runtime literals to English while preserving error codes, control flow, schemas, and Chinese documentation comments.
- Added `ProductionRuntimeMessagesEnglishTest` to reject Han characters in production Java string literals while excluding comments and character literals.
- Updated matching test assertions, including one historical mojibake assertion in `BoundedDocumentMapperTest`.
- `mvn test`: passed for all 19 reactor modules.
- `scripts/build-agent.ps1`: passed for all 20 build modules and produced `AGENT_BUILD_OK`.
- Built CLI negative-path check: returned `CLI_INVALID_ARGUMENTS` with an English message and no Han characters.
- Unknown-command parsing completes before Java logging context creation; file logging and stack persistence remain covered by `JavaExecutionLoggingTest`.
