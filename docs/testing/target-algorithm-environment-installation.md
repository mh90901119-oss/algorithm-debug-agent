# 目标环境源码 ZIP 安装与验证

本手册适用于 Windows 目标环境电脑：OpenCode 和大模型已可用，算法项目使用 JDK 17，
Agent 通过 GitHub 源码 ZIP 安装，目标环境 Maven 只能从受限环境镜像获取主流依赖。

## 1. 安装结果和边界

完成后的调用链为：

```text
用户在算法 Maven 模块启动 OpenCode
→ OpenCode 加载 algorithm-debug Agent 和 Skill
→ 大模型调用 Custom Tool
→ JS Adapter 调用 Agent 仓的 bin\ada.cmd
→ Java CLI 运行 Maven/JUnit、静态分析、CodePath 或 JDWP
→ 证据写入 Workspace
→ 大模型基于证据返回结论
```

Agent 不会重装 OpenCode、修改模型 Provider、更改系统 JDK，也不要求在目标算法仓库
创建 Agent 专用配置文件。本方案是源码 ZIP 安装，不是包含 JDK、Maven 和 OpenCode
的完全离线二进制发行包。

## 2. 环境前提

| 项目 | 要求 |
|---|---|
| OpenCode | 已安装，已配置可用的大模型 |
| JDK 21 | 已下载或解压，专供 Agent 和 JDWP Collector 使用 |
| JDK 17 | 目标算法实际运行 JDK，供 Maven/JUnit 和 CodePath 使用 |
| Maven | 可从受限环境镜像解析主流 Maven 插件、JUnit 和 Jackson |
| 目标 UT | 能在包含它的 Maven 模块中独立运行 |
| Gantt 输出 | UT 运行后向固定目录写入新的时间戳 `.json` |

CodePathTracer 的固定 JAR、Sources、POM 和 Apache-2.0 License 位于 `third-party`，
不要求受限环境 Maven 镜像提供该制品。JDWP Collector 核心源码和可执行 JAR 也在本仓内维护。

## 3. 下载和解压 ZIP

在 GitHub 选择需要安装的目标分支、Tag 或发布版本，下载对应的 Source code ZIP。手册不绑定临时
开发分支或提交号；下载后以 ZIP 中的 README、配置 Schema 和安装脚本为同一版本基线。

将 ZIP 解压到不会随意移动的目录，例如：

```text
D:\tools\algorithm-debug-agent
```

安装器会把解析后的 `bin\ada.cmd` 绝对路径写入 OpenCode 安装契约。安装后如果移动仓库，
需要在新目录重新运行安装器。ZIP 没有 `.git` 不影响构建和使用。

## 4. 确认 JDK

JDK 21 可以只解压，无需写入系统 `JAVA_HOME` 或 `PATH`。假设它位于：

```text
D:\tools\jdk-21
```

目标环境现有 JDK 17 例如：

```text
C:\Program Files\Java\jdk-17
```

分别验证：

```powershell
& "D:\tools\jdk-21\bin\java.exe" -version
& "C:\Program Files\Java\jdk-17\bin\java.exe" -version
```

配置项填写 JDK 根目录，不填 `bin` 目录。运行时的职责分离为：

| 进程 | JDK |
|---|---|
| Agent Java CLI | JDK 21 |
| JDWP Collector | JDK 21 |
| Maven/JUnit 目标 UT | JDK 17 |
| CodePath Launcher | JDK 17 |

脚本只在自身进程和子进程中设置 Java 环境，不改变目标环境电脑全局配置。

## 5. 理解 IDEA 和 Maven

Maven 负责读取 `pom.xml`、解析依赖、编译代码、通过 Surefire 运行 JUnit，并生成退出码、
stdout、stderr 和 Surefire XML。Agent 使用这些确定性产物判断 UT 执行结果。

IDEA 中点击测试方法左侧的运行图标，创建的通常是 `JUnit` Run Configuration。这条链路由
IDEA 直接构造 Classpath 并启动 JUnit，执行 UT 时可能没有调用 `mvn.cmd`。因此：

> IDEA 中 UT 成功，不等于命令行 Maven 一定能成功。

IDEA 可以使用三种 Maven：

| 类型 | 特点 | Agent 建议 |
|---|---|---|
| IDEA Bundled Maven | 路径与 IDEA 版本绑定 | 不作为首选 |
| 电脑独立安装 Maven | 路径稳定，可使用受限环境镜像 | 推荐 |
| 项目 `mvnw.cmd` | 锁定 Maven 版本，可能下载 Distribution | 只在目标环境已适配 Wrapper 时使用 |

在 IDEA 中检查 `Settings -> Build Tools -> Maven`，记录 Maven Home、User settings file、
Local repository 和 Maven 使用的 JDK。再检查目标 UT 的 Run Configuration 类型是 `JUnit` 还是 `Maven`。

## 6. 确认可用 Maven

先在 PowerShell 查找电脑 Maven：

```powershell
where.exe mvn
mvn -version
```

如果 Maven 没有进入 `PATH`，直接使用绝对路径：

```powershell
& "D:\tools\apache-maven\bin\mvn.cmd" -version
```

重点核对 Maven 版本、Maven Home、Java Version 和 Java Home。Maven 可以从两处读取镜像配置：

```text
<Maven安装目录>\conf\settings.xml
%USERPROFILE%\.m2\settings.xml
```

如果 IDEA 使用自定义 `settings.xml`，而命令行 Maven 不读取它，可能出现“IDEA 能下载依赖，
终端无法解析依赖”。优先使用目标环境统一 Maven 配置，将受限环境镜像保存到上述标准位置之一。

## 7. 脱离 IDEA 验证目标 UT

这是安装 Agent 前最重要的业务验收。进入真正包含目标 UT 和 `pom.xml` 的 Maven 模块：

```powershell
cd D:\path\to\target-environment-algorithm-module
```

在当前 PowerShell 临时让 Maven 使用 JDK 17，执行后恢复环境：

```powershell
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path

try {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:Path = "$env:JAVA_HOME\bin;$oldPath"

    & "D:\tools\apache-maven\bin\mvn.cmd" -version
    & "D:\tools\apache-maven\bin\mvn.cmd" `
        "-Dtest=org.example.targetalgorithm.scheduler.TargetAlgorithmTest#targetCase" `
        test
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
```

验收标准：

- Maven 显示 Java 17。
- 目标 UT 真实运行，不是 `No tests matching pattern`。
- `target\surefire-reports` 中生成对应报告。
- 进程返回与 UT 结果一致的成功或失败状态。
- 阳光场景在统一 Gantt 目录生成新的时间戳 JSON。

如果这一步失败，应先修复 Maven、Profile、镜像、JDK 或项目 POM 问题。Agent 本质上也是调用这条链路，
不能替代不可运行的 Maven UT 环境。

## 8. 修改统一配置

只修改 Agent 仓的 `config/agent-settings.json`。示例：

```json
{
  "schemaVersion": "1.0",
  "openCodeConfigDirectory": "%USERPROFILE%\\.config\\opencode",
  "workspaceDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\workspace",
  "dfxDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\diagnostics",
  "evalDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\evals",
  "resultJsonDirectory": "D:\\log\\scheduler\\${runDate}\\gant",
  "agentJavaHome": "D:\\tools\\jdk-21",
  "targetJavaHome": "C:\\Program Files\\Java\\jdk-17",
  "mavenExecutable": "D:\\tools\\apache-maven\\bin\\mvn.cmd",
  "dfxEnabled": true
}
```

| 字段 | 作用 |
|---|---|
| `openCodeConfigDirectory` | OpenCode 全局配置目录 |
| `workspaceDirectory` | Case、Run、Trace、Evidence 和交互日志根目录 |
| `dfxDirectory` | Case 尚未建立时的兜底诊断目录 |
| `evalDirectory` | Eval Harness 报告目录 |
| `resultJsonDirectory` | 算法 UT 生成 Gantt JSON 的统一绝对目录 |
| `agentJavaHome` | Agent 和 JDWP Collector 使用的 JDK 21 根目录 |
| `targetJavaHome` | 算法 UT 和 CodePath 使用的 JDK 17 根目录 |
| `mavenExecutable` | 已通过手工 UT 验证的 `mvn.cmd` |
| `dfxEnabled` | 是否记录 Case 交互 DFX |

Workspace、DFX 和 Eval 有可用默认值，也可改为绝对路径。`resultJsonDirectory` 属于业务约定，
必须手工确认。不通过安装命令、OpenCode Tool 或用户问题传递这些路径。

## 9. 处理 PowerShell 执行策略

如果目标环境策略允许直接执行脚本，跳过本步。如果当前会话禁止脚本，可以只对当前 PowerShell 进程设置：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

关闭当前 PowerShell 后该设置失效，不修改机器级策略。

## 10. 构建 Agent

在 Agent 仓执行：

```powershell
.\scripts\build-agent.ps1
```

脚本读取统一配置，使用 JDK 21 启动配置的 Maven，编译 Agent、Java 17 CodePath Launcher 和
JDWP Collector，并将 Collector JAR 归档到 `tools\jdwp-collector`。同一个 Maven 可以在构建 Agent 时
使用 JDK 21，在运行目标 UT 时使用 JDK 17。

## 11. 从目标算法模块验证 Java CLI

```powershell
$agentRoot = "D:\path\to\algorithm-debug-agent"
cd D:\path\to\target-algorithm-module
& "$agentRoot\scripts\verify-ada-launcher.ps1"
```

验证链路为 `ada.cmd -> run-ada.ps1 -> JDK 21 -> Java CLI -> ToolResponse JSON`。这一步在进入 OpenCode
之前区分 JDK、Maven、目标模块、构建产物、脚本或 CLI 问题。脚本不接受项目路径参数，也不推断
Agent 同级 Demo；当前工作目录就是待验证的目标 Maven 模块。

## 12. 安装到现有 OpenCode

```powershell
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

`Install` 把 Agent 定义、Skill、Command、Custom Tool 和 JS Adapter 部署到配置的 OpenCode 目录，
并生成包含解析后仓库和运行路径的 `installation.mjs`。它不修改 OpenCode 模型 Provider 或凭据。

`Check` 检查 OpenCode、Agent、Skill、Command、Custom Tool、Launcher 和最终生效路径。安装器不绑定
OpenCode 版本号；所需 CLI 行为不兼容时返回明确错误。

安装器是文件复制而不是符号链接。修改 Skill、OpenCode Tool、安装配置或移动 Agent 仓后，
需要重新执行 `Install`。只修改 Java 源码时至少重新执行 `build-agent.ps1`。

## 13. 验证 JDWP 和安全软件

```powershell
.\scripts\verify-jdwp-loopback.ps1
```

脚本使用目标 JDK 17 启动只监听 `127.0.0.1` 的 Probe JVM，再使用 Agent JDK 21 运行仓库内
真实 Collector，验证断点、局部变量 `marker=42`、Raw Trace、Manifest 和目标恢复执行。

成功输出 `JDWP_LOOPBACK_OK`。证据位于：

```text
<workspaceDirectory>\environment-checks\jdwp-loopback\<runId>
```

如果失败发生在端口监听或 attach，需要由目标环境安全管理员确认终端防护策略。脚本不会关闭或绕过安全软件。

## 14. 在目标算法模块使用

进入包含目标 UT 和 `pom.xml` 的 Maven 模块：

```powershell
cd D:\path\to\target-environment-algorithm-module
opencode
```

在 OpenCode 选择 `algorithm-debug` Agent，直接提问：

```text
请分析 org.example.targetalgorithm.scheduler.TargetAlgorithmTest#targetCase，
说明本次调度结果异常的根因。
```

无需在问题中说明 Workspace、JDK、Maven、Gantt 目录、CodePath JAR 或 JDWP Collector 路径。

正常分析顺序是：

```text
analysis_begin 创建或续接 Case
→ 检查目标 UT
→ run_test 使用 JDK 17 和 Maven 运行 UT
→ 归档 stdout、stderr、Surefire 和本次变化的 Gantt JSON
→ 根据问题和现有证据选择静态分析、CodePath 或 JDWP
→ case_audit 检查产物完整性
→ analysis_complete 归档答案和证据引用
```

UT 不存在时直接返回 `TARGET_TEST_NOT_FOUND`。UT 失败时保留真实异常、断言、Surefire 和进程输出，
大模型再根据具体结果决定是否需要动态采集。

## 15. Workspace 和日志

默认 Case 位于：

```text
%LOCALAPPDATA%\algorithm-debug-agent\workspace\projects\<projectId>\cases\<caseId>
```

Workspace 与 Agent 源码仓和目标算法仓库分离，所以不会污染业务 Git 仓库。每个 Case 根目录的
`interaction.jsonl` 可以直接打开，查看 Tool、Java CLI、Run、Collection 和 Evidence 的时序。

## 16. 常见问题

| 现象 | 检查方向 |
|---|---|
| `Agent Java executable not found` | 确认 `agentJavaHome\bin\java.exe` 存在 |
| Maven 显示错误 Java 版本 | 确认 `targetJavaHome` 是 JDK 17 根目录 |
| `Maven executable not found` | 确认 `mavenExecutable` 指向存在的 `mvn.cmd` |
| `Could not resolve artifact` | 确认命令行 Maven 读取目标环境 `settings.xml` 和镜像 |
| `No tests matching pattern` | 确认当前目录、测试类/方法和 Surefire 配置 |
| IDEA 成功但 Maven 失败 | 比较 JDK、Maven Home、settings、Profile、注解处理和手工 Library |
| UT 成功但没有 Gantt | 手工确认 `resultJsonDirectory` 在 UT 期间生成新 `.json` |
| OpenCode 找不到 Agent/Tool | 重跑 `Install` 和 `Check`，然后重启 OpenCode 会话 |
| JDWP 连接失败 | 检查 loopback 证据和目标环境终端安全软件策略 |

如果目标算法只能在特定旧 Maven 下运行，而 Agent 必须使用另一 Maven，应先用真实 UT 证明冲突。
当前只配置一个 `mavenExecutable`；在没有真实冲突前不引入双 Maven 过度设计。

## 17. 更新 ZIP

使用新 ZIP 时：

1. 下载包含目标提交的新 ZIP。
2. 解压到稳定目录。
3. 在旧 Agent 仓执行 `scripts\uninstall-opencode.ps1`；Workspace 和历史证据会保留。
4. 重新填写新仓的 `config/agent-settings.json`。
5. 重跑 `build-agent.ps1`。
6. 重跑 `install-opencode.ps1 -Mode Install`。
7. 重跑 `Check` 和 JDWP loopback 验证。

首次从没有安装清单的旧版本升级时，先用新版本执行一次 Install 生成清单，再执行卸载。详细边界见
[OpenCode Algorithm Debug Agent 卸载与重新安装](opencode-uninstallation.md)。

Workspace 默认在 `%LOCALAPPDATA%` 中，更换 Agent 源码 ZIP 不会删除历史 Case。

## 18. Git 提交边界

- 提交 Eval Harness 源码、Suite/Case 定义、固定 Fixture 和断言，它们是回归能力。
- `agent-evals/suites/smoke.json` 是 Smoke Suite 定义，应提交。
- 不提交 Eval 报告、Workspace Case、DFX 日志、Raw Trace、Gantt 运行副本和临时会话文件。

## 附录：安装成功后的端到端调试

`install-opencode.ps1 -Mode Check` 只验证 OpenCode 能发现安装的 Agent、Skill、Command 和 Custom
Tools，不会执行 Java CLI、Maven、目标 UT、CodePath 或 JDWP。端到端失败时不要重复安装或直接修改
目标算法 POM，应先按 [OpenCode Algorithm Debug Agent 调试指南](opencode-agent-debugging.md) 区分
OpenCode、JS Adapter、Launcher、Java Agent 和 Maven/UT 五层故障，再根据修改类型决定 Build、
Install 和是否重启 OpenCode。

## 19. 最简执行清单

```powershell
cd D:\tools\algorithm-debug-agent
Set-ExecutionPolicy -Scope Process Bypass
$agentRoot = (Get-Location).Path

# 先修改 config\agent-settings.json
.\scripts\build-agent.ps1

cd D:\path\to\target-algorithm-module
& "$agentRoot\scripts\verify-ada-launcher.ps1"

cd $agentRoot
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
.\scripts\verify-jdwp-loopback.ps1

cd D:\path\to\target-environment-algorithm-module
opencode
```
