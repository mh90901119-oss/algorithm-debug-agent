# 公司环境源码安装与验证

## 前提

- 从 GitHub 下载 Algorithm Debug Agent 的普通源码 ZIP。
- 公司电脑已有供算法模块使用的 JDK 17 和 Maven。
- 另行解压 JDK 21，不设置系统 `JAVA_HOME`，不修改系统 `PATH`。
- 公司 Maven 镜像能提供 Maven 插件、JUnit、Jackson 等主流依赖。

CodePathTracer 的固定 JAR、Sources、POM 和 Apache-2.0 License 已保存在 Agent 源码仓的
`third-party` 目录，不要求公司 Maven 镜像提供该制品。JDWP Collector 直接从本仓源码构建。

## 配置

只修改 Agent 仓的 `config/agent-settings.json`：

```json
{
  "agentJavaHome": "D:\\tools\\jdk-21",
  "targetJavaHome": "D:\\tools\\jdk-17",
  "mavenExecutable": "D:\\tools\\apache-maven\\bin\\mvn.cmd"
}
```

以上片段仅说明三个字段，实际文件还必须保留 Workspace、OpenCode、Gantt、DFX 和 Eval 字段。
若 Maven 已经能从终端直接运行，`mavenExecutable` 可以保留为空。

## 构建和安装

在 Agent 仓执行：

```powershell
.\scripts\build-agent.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
.\scripts\verify-jdwp-loopback.ps1
```

脚本只修改自身进程的 Java 环境。公司已有 JDK 17 和其他终端的 `JAVA_HOME` 不会被改变。

## 在公司算法仓使用

```powershell
cd D:\path\to\company-algorithm-module
opencode
```

OpenCode 的普通文件工具可以按用户要求修改公司算法源码和 UT。Algorithm Debug Custom Tool
运行目标 UT、归档控制台和 Gantt，并按证据需要执行静态分析、CodePath 或 JDWP。Agent 不要求
在公司算法仓创建自己的配置文件。

## JDWP 安全软件检查

`verify-jdwp-loopback.ps1` 使用目标 JDK 启动只监听 `127.0.0.1` 的 Probe JVM，再使用 Agent JDK
运行仓库内真实 Collector。验证断点、局部变量 `marker=42`、Raw Trace、Manifest 和目标恢复执行。

成功输出 `JDWP_LOOPBACK_OK`。失败输出精确阶段和证据目录。若失败发生在端口监听或 attach，需由
公司安全管理员确认终端防护策略；脚本不会关闭或绕过安全软件。

## Git 提交边界

- 提交 Eval Harness 源码、Suite/Case 定义、固定 Fixture 和断言，因为它们是回归能力的一部分。
- `agent-evals/suites/smoke.json` 是 Smoke Suite 定义，应提交。
- 不提交 Eval 报告、Workspace Case、DFX 日志、Raw Trace、Gantt 运行副本和临时会话文件。
