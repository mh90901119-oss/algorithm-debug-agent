# Algorithm Debug Agent

面向本地 Java/Maven 算法 UT 的离线问题定位 Agent。OpenCode 与 LLM 负责理解问题、选择下一步工具和解释因果；Java 代码负责确定性执行 UT、静态分析、CodePath/JDWP 采集、校验和证据归档。

## 当前能力

- 以一个明确的 JUnit 5 测试类或测试方法作为目标。
- 在 UT 第一层源码中识别唯一的 `String` 算法输入路径，支持文件名以 `input.json` 或 `input_.json` 结尾。
- 首次捕获时按原文件名复制输入，后续多轮分析复用并校验同一份输入。
- 执行 Maven/JUnit，区分目标测试不存在、目标代码异常、断言失败和 Agent/环境故障。
- 成功 Run 从配置的日期化结果目录捕获新增或变化的 JSON Gantt，并保留原文件名。
- 生成有界方法目录和源码调用关系，帮助 LLM 找到动态采集边界。
- 按 LLM 提交的结构化意图执行 CodePath 与 JDWP；JDWP 支持栈帧值路径条件过滤。
- 将原始 Trace、派生摘要、校验、证据和最终分析追加归档到 Workspace。
- 以 10 个真实 OpenCode Smoke Case 回归成功、失败、静态、动态、完整性和跨实体因果场景。

## 安装前提

- OpenCode 已安装并可使用大模型，不限制具体 OpenCode 版本；安装器以能力发现判断兼容性。
- Agent 使用 JDK 21 构建和运行。
- 目标算法 UT 可以使用独立的 JDK 17 或 JDK 21。
- Maven 能在目标算法模块目录执行指定 UT；离线环境所需业务依赖应已在内部镜像或本机 Maven 仓库中。

## 配置

安装前编辑 [config/agent-settings.json](config/agent-settings.json)。所有字段都有默认值，用户可直接修改：

| 字段 | 用途 |
| --- | --- |
| `openCodeConfigDirectory` | OpenCode 全局配置目录 |
| `workspaceDirectory` | Case、Run、Trace、Evidence 和日志目录 |
| `dfxDirectory` | 尚未建立 Case 时的 Java 启动诊断日志目录 |
| `evalDirectory` | Eval 报告目录 |
| `resultJsonDirectory` | 算法 Gantt 输出目录，支持 `${runDate}` |
| `agentJavaHome` | Agent 的 JDK 21；空值时从环境发现 |
| `targetJavaHome` | 目标 UT 使用的 JDK；空值时从环境发现 |
| `mavenExecutable` | Maven 可执行文件；空值时使用 `mvn` |

默认 Gantt 配置为 `D:\\log\\scheduler\\${runDate}\\gant`。`${runDate}` 在每次 Run 开始时按本机日期解析为 `yyyy-MM-dd`。绝对路径和相对目标模块目录的路径都受支持。

## 构建与安装

```powershell
.\scripts\build-agent.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

安装器复制 Agent、Skill、Command、Custom Tool、Java CLI 和 Collector 到配置的 OpenCode 目录。仓库源码修改后，已安装副本不会自动变化；先卸载再重新安装：

```powershell
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
```

安装器不会修改目标算法仓库或 POM。完整步骤见 [目标环境安装与验证](docs/testing/target-algorithm-environment-installation.md)。

## 使用

在目标算法 Maven 模块目录启动 OpenCode，然后明确提供 UT 和问题，例如：

```text
使用 algorithm-debug 分析
com.example.scheduler.SchedulerTest#shouldScheduleAllWafers：
为什么 WAFER-2 的 PICK 晚于可用腔室时间？
```

工作流会先建立 Case/Analysis，捕获并读取算法输入，再按证据缺口选择 UT、静态分析、CodePath 或 JDWP。动态采集没有固定轮数，但每个 Plan 都必须说明问题、假设、预期观察和来源 Evidence；同一无效 Plan 不得重复。

## Workspace

默认路径为 `%LOCALAPPDATA%\algorithm-debug-agent\workspace`，可配置。核心结构：

```text
projects/<projectId>/cases/<caseId>/
  case.json
  input/<original-input-name>
  contexts/<contextId>/context.json
  analyses/<analysisId>/
  runs/<runId>/
  collections/<collectionId>/
  evidence/<evidenceId>/
  artifacts/<artifactId>.json
  logs/agent-YYYY-MM-DD.log
```

输入只在 Case 首次捕获时复制一次；成功的非采集 Run 才捕获 Gantt，文件保存在该 Run 的 `raw/` 下并保持原名。CodePath/JDWP 重跑不复制 Gantt，也不使用 Gantt SHA 作为门禁。

完整流程与每种文件说明见 [工作流与产物](docs/algorithm-debug-workflow-and-artifacts.md)。当前边界见 [当前能力](docs/current-capabilities.md)。

## 验证入口

```powershell
mvn -Pcodepath-launcher test
node --test agent-evals/test/*.test.mjs integrations/opencode/test/*.test.mjs
.\scripts\verify-opencode-installer.ps1
```

真实 OpenCode Eval：

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
```

调试安装副本与源码的区别见 [OpenCode Agent 调试](docs/testing/opencode-agent-debugging.md)，删除规则见 [卸载与重新安装](docs/testing/opencode-uninstallation.md)。
