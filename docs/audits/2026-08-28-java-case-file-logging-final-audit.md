# Java Case 文件日志最终审计

## 1. 审计结论

Java Agent 执行日志已作为诊断能力接入现有 Case 生命周期，不引入后台线程或外部日志框架。
存在 Case 身份时，日志写入对应 Case；Case 尚未建立的 CLI 错误只写入 DFX bootstrap 日志。
日志不进入 ToolResponse stdout，不参与 Evidence 充分性判断，也不改变目标 UT 的原始 stdout/stderr 归档。

## 2. 落盘契约

- Case 日志：`<workspace>/projects/<projectId>/cases/<caseId>/logs/agent-YYYY-MM-DD.log`
- Bootstrap 日志：`<dfxDirectory>/java/agent-bootstrap-YYYY-MM-DD.log`
- 无 Case 的正常命令不创建日志；无 Case 的错误在 DFX 已配置时创建 bootstrap 日志。
- 日志按行追加，使用文件锁避免同一 JVM 外的并发写入互相覆盖。
- 日志内容为英文 ASCII；绝对路径、凭据和疑似 secret 在写盘前脱敏。
- 传播到 CLI 边界的异常记录完整异常类型、cause 和调用栈；ToolResponse stderr 保持为空。

## 3. 已覆盖执行阶段

- Case 创建、续接、Context 与 Analysis 创建。
- 单一算法输入定位、复制和同 Case 多轮输入比较。
- 目标 UT 启动、进程结束、结果分类、Gantt 捕获与 Run Artifact 归档。
- 静态方法目录、CodePath 计划和 JDWP 计划。
- CodePath/JDWP Collection 创建、目标进程或 Collector 结束、Baseline、Normalizer、Validator、Evidence 与充分性评估。
- Analysis 完成、Artifact 读取、Case inspect/audit 和 CLI 成功或失败边界。

## 4. 自动化验证

2026-08-28 执行并通过：

- `mvn test`：根 Reactor 19 个生产/集成模块全部成功。
- `mvn -pl trace-validator,ada-core -am test`：受英文 Validation detail 修复影响的 13 个模块全部成功。
- `node --test integrations/opencode/test/*.test.mjs`：38 个 OpenCode Adapter 测试通过。
- `scripts/build-agent.ps1`：20 个构建模块成功，CLI、CodePath Launcher 和 JDWP Collector 均重新打包。
- `scripts/install-opencode.ps1 -Mode Install` 与 `-Mode Check`：安装和检查成功，DFX 配置启用。

## 5. 真实 OpenCode Eval

完整 Smoke 报告：

`C:/Users/zhao1k/AppData/Local/algorithm-debug-agent/evals/20260827163952-0883f356`

结果为 9 PASS、0 FAIL，覆盖：

- 通过 UT 与 Gantt 归档。
- 目标 UT 不存在并停止动态采集。
- 不符合单一算法输入约束并停止执行。
- 算法运行异常。
- JUnit 断言失败。
- 当前源码静态分析。
- CodePath 独立动态采集。
- JDWP 独立动态采集。
- Artifact 被篡改后的 Case audit 拒绝。

完整 Smoke 的 9 个 Agent Case 均存在一个非空日期日志；日志总行数按 Case 为 32 至 82 行，均为
ASCII，未匹配本机绝对路径、Home 路径、Bearer、password 或 token 泄漏模式。9 个 Case 均无空目录。
零字节文件仅为成功进程的 `stderr.log`、JDWP Collector stderr 或 target stderr，它们是用于明确表达
“该进程没有错误输出”的契约产物，不是无意义占位文件。

英文 Validation detail 修复后，重新构建、安装并单独执行 `jdwp-independent`：

`C:/Users/zhao1k/AppData/Local/algorithm-debug-agent/evals/20260827165925-5b5273da`

结果为 1 PASS、0 FAIL。Collection 为 `SUCCESS`，`evidenceUsable=true`，Validation 为 `VALID`；
`NORMALIZATION_PARTIAL` 明确输出英文预算/上游截断说明。Case 日志 75 行，ASCII、无路径/凭据泄漏，
Case 内无空目录。

## 6. Bootstrap 边界验证

使用无效 CLI 命令在 Case 创建前触发错误，结果为：

- 退出码 `2`。
- stdout 是可解析的 ToolResponse `2.0`，错误码 `CLI_INVALID_ARGUMENTS`。
- stderr 为 0 字节。
- DFX 下生成非空 `agent-bootstrap-2026-08-28.log`。
- bootstrap 日志包含 5 行调用栈，内容为 ASCII。

## 7. 发现并修复的问题

- Smoke Suite 曾包含损坏的乱码正则且无法解析；已收敛为等价英文正则并去除 UTF-8 BOM。
- JDWP `NORMALIZATION_PARTIAL` 的确定性 detail 曾输出中文；已改为英文并增加精确回归断言。
- Run 无法形成可信进程结果时的异常消息曾为中文；已改为英文，避免进入文件调用栈。
- README 新增日志章节曾误用一级标题；已修正为二级标题。

## 8. 剩余非阻断项

- 构建仍显示现有 SLF4J NOP、Byte Buddy 动态 Agent 和 shaded JAR 重叠资源警告；它们不是本次
  Java 文件日志的依赖或运行失败，不影响当前 E2E 结果。
- Java 日志是诊断数据，不是算法事实。LLM 只能用它定位 Agent 执行环节，不得把日志事件直接作为
  算法根因证据。
