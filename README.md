# Algorithm Debug Agent

Algorithm Debug Agent 是一个面向本地 Java/Maven 算法 UT 的离线问题定位 Agent。用户指定一个
JUnit 测试和问题，OpenCode 中的大模型负责选择证据和解释；Agent 负责运行 UT、归档 Gantt、
静态源码目录、CodePath、JDWP、校验结果和 Case 历史。

当前只正式适配 OpenCode。目标算法模块不需要依赖本 Agent，也不要求修改生产算法源码来插桩。

## 当前可用能力

- 运行指定的 Maven/JUnit `class#method`，归档退出码、Surefire、stdout、stderr 和结构化失败事实。
- 从配置的算法结果目录捕获本次 UT 新增或变化的顶层 JSON，并归档为 Gantt Artifact。
- 使用有界 Javac AST Method Catalog 查找当前源码方法和直接调用边。
- 独立执行 CodePath 方法路径采集，或独立执行 JDWP 局部变量、字段和栈采集。
- 对动态失败重跑比较结构化失败指纹；成功重跑不要求 Gantt 完全相同。
- 将 Raw、Derived、Validation、Evidence 和最终答案追加保存到 Case Workspace。
- 使用 `ArtifactReference` 的相对路径、大小和 SHA-256 防止归档文件被静默替换。
- 启用 DFX 且经 OpenCode Tool 执行时，在 Case 根目录写入 `interaction.jsonl` 查看真实 Tool/CLI 顺序。
- 使用 `case_audit` 检查缺失文件、孤儿文件、Artifact 完整性、无效 JSONL 和空 Case 目录。
- 使用真实 OpenCode Eval Harness 回归 9 个成功、失败、静态、动态和完整性场景。

不提供独立 Gantt 语义引擎。Agent 只提供有界 JSON 结构读取，业务含义由大模型结合问题、源码和证据解释。

## 安装与路径

目标环境电脑使用源码 ZIP、独立 JDK 21、现有 JDK 17 和目标环境 Maven 的完整流程，见
[目标环境源码 ZIP 安装与验证](docs/testing/target-algorithm-environment-installation.md)。

所有需要用户调整的路径都在仓库文件 [config/agent-settings.json](config/agent-settings.json)：

```json
{
  "schemaVersion": "1.0",
  "openCodeConfigDirectory": "%USERPROFILE%\\.config\\opencode",
  "workspaceDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\workspace",
  "dfxDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\diagnostics",
  "evalDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\evals",
  "resultJsonDirectory": "D:\\log\\scheduler\\${runDate}\\gant",
  "agentJavaHome": "",
  "targetJavaHome": "",
  "mavenExecutable": "",
  "dfxEnabled": true
}
```

`workspaceDirectory`、`dfxDirectory` 和 `evalDirectory` 有可直接使用的默认值，也可以改成绝对路径。
`resultJsonDirectory` 与业务算法相关，安装前应修改为目标算法统一输出 Gantt JSON 的绝对目录。
不在算法项目中创建额外配置文件，也不通过 OpenCode Tool 或安装命令参数传路径。

构建并安装：

```powershell
.\scripts\build-agent.ps1
.\scripts\install-opencode.ps1 -Mode Install
```

安装器会打印解析后的 OpenCode、Workspace、Gantt、DFX 和 Eval 路径。修改配置或仓库内 Agent、
Skill、Tool 后重新运行安装器，再重启正在运行的 OpenCode 会话。重复安装是幂等覆盖受管资产，
不会删除已有 Workspace Case。

检查安装：

```powershell
.\scripts\install-opencode.ps1 -Mode Check
```

安装器不保存目标算法模块路径。需要验证 Java CLI 与目标 Maven 模块的适配时，进入该模块目录后调用
Agent 仓库中的 `scripts\verify-ada-launcher.ps1`；脚本将当前工作目录作为目标模块，不接受项目路径参数。

安装器不绑定 OpenCode 版本号。只要当前 OpenCode 能发现 Agent、Skill、Command 和 Custom Tools
并支持所需 CLI 行为即可；不兼容时返回明确错误。

卸载和重新安装：

```powershell
.\scripts\uninstall-opencode.ps1
.\scripts\build-agent.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

卸载只删除安装清单中经完整性校验的 Agent 资产，不删除 Workspace 和其他 OpenCode 内容。完整说明见
[OpenCode Algorithm Debug Agent 卸载与重新安装](docs/testing/opencode-uninstallation.md)。

## 在 OpenCode 中使用

从包含目标 `pom.xml` 的算法模块目录启动：

```powershell
cd D:\path\to\algorithm-module
opencode
```

选择或调用 `algorithm-debug` Custom Agent，然后直接提问，例如：

```text
分析 org.example.scheduler.wafer.WaferSchedulingReproductionTest#reproduceComplexSchedulingFromTimestampedInput，
说明本次调度结果是否正确，并定位可疑路径。
```

提问必须能确定目标 UT。无需再次说明 Workspace、项目 ID、Collector JAR 或 Gantt 输出路径。
如果 UT 不存在，Agent 返回 `TARGET_TEST_NOT_FOUND` 并停止，不强行运行或采集。

## 算法输入前置捕获

每轮分析固定从 `analysis_begin -> algorithm_input_capture` 开始。第二步由 Java AST 只检查目标 UT
方法第一层直接声明的局部变量，要求类型是 `String` 或 `java.lang.String`、初始化器是单个字符串
字面量、值以 `input.json` 结尾。相对路径按当前 Maven 模块根目录解析，绝对路径直接使用。

当前只支持一个 UT 对应一个算法输入。零个、多个、表达式拼接、helper/分支内路径、文件缺失或文件
超过 256 MiB 都返回稳定错误并停止 `run_test`、CodePath Plan 和 JDWP Plan，不允许模型猜测路径。
成功时写入：

```text
analyses/<analysisId>/input/input-analysis.json
analyses/<analysisId>/input/algorithm-input.json
```

`input-analysis.json` 保存源码位置、路径类型、ArtifactReference，以及相对上一轮成功捕获的
`FIRST_CAPTURE/UNCHANGED/CHANGED/INCOMPARABLE`。输入 SHA 只用于验证归档副本和精确字节变化，
不证明源码版本、Gantt 一致性或算法正确性。

## 谁负责什么

| 组件 | 职责 |
|---|---|
| OpenCode | 会话、模型调用、Custom Agent 和 Tool 执行宿主 |
| 大模型 | 理解问题、决定下一项证据、生成计划、判断是否足够、解释结论 |
| Skill | 约束证据顺序、停止条件、工具选择和回答分类 |
| OpenCode Tools / JS Adapter | 将模型参数映射到 Java CLI，解析仓库配置，记录 DFX |
| Java Agent | 确定性运行、采集、解析、预算、校验、归档和审计 |
| Workspace | Case 的持久化事实、证据、计划、日志和答案 |
| Eval Harness | Agent 外部的回归测试，不参与普通用户问题分析 |

## Workspace 与日志

默认 Case 路径：

```text
%LOCALAPPDATA%\algorithm-debug-agent\workspace\projects\<projectId>\cases\<caseId>
```

启用 DFX 且通过 OpenCode Tool 执行时，Case 的 `interaction.jsonl` 可以直接打开，按时间查看 Tool 调用、CLI 启动/结束、结果码以及
Run、Collection、Evidence、Artifact ID。它不是隐藏思维日志，也不是业务证据。

直接调用 Java CLI、关闭 DFX 或 Recorder 写入失败时可以没有该文件；`case_audit` 不因此判定业务 Case
失败。文件存在时仍严格校验 JSONL，真实 OpenCode Eval 另行要求并审计交互日志。

Case 目录按生产者懒创建。不存在对应行为时，不创建 `runs`、`collections`、`evidence` 或
`plans` 空目录。零字节 `stderr.log` 是有效的进程流捕获，明确表示该进程没有 stderr，不是占位文件。

完整文件说明见 [工作流与产物指南](docs/algorithm-debug-workflow-and-artifacts.md)。

## 构建与评测

```powershell
mvn test
node --test agent-evals/test/*.test.mjs integrations/opencode/test/*.test.mjs
```

从目标算法模块目录运行真实 OpenCode Eval：

```powershell
D:\path\to\algorithm-debug-agent\scripts\run-agent-evals.ps1 -Suite Smoke
```

只运行一个 Case：

```powershell
D:\path\to\algorithm-debug-agent\scripts\run-agent-evals.ps1 -Suite Smoke -Case jdwp-independent
```

目标模块就是脚本启动时的当前目录，不接收项目路径参数。报告写入配置的 `evalDirectory`。

## 当前边界

- 静态分析是单 Maven 模块、有界的当前源码目录，不宣称完整项目调用图。
- CodePath 和 JDWP 都会各自重新运行目标 UT，并可能改变时序；失败目标通过失败指纹校验降低误用风险。
- JDWP 命中断点时会短暂停止事件线程，不是绝对零影响。
- LLM 输出具有模型不确定性；确定性 Tool、Evidence 分类、Case Audit 和 Eval 用于约束，不代表自动证明业务真理。
- 当前不支持多模块跨 Reactor 调用图、在线生产调度决策或自动修改生产算法源码。

当前架构和模块见 [架构索引](docs/architecture/README.md)，最终实施证据见
[运行时精简最终审计](docs/audits/agent-runtime-simplification-final-audit.md)。
