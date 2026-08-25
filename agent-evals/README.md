# Agent Eval Harness

Agent Eval Harness 是 Agent 外部的真实 OpenCode 回归工具。它不参与普通用户分析，也不对单次回答
进行在线二次模型审计。

## 运行

先构建并安装当前仓库，然后从目标算法 Maven 模块目录执行：

```powershell
D:\path\to\algorithm-debug-agent\scripts\run-agent-evals.ps1 -Suite Smoke
```

当前目录就是目标模块。脚本不接受 `-Project`、Workspace、Gantt 或输出目录路径参数；这些路径来自
`config/agent-settings.json` 安装结果。

运行单个 Case：

```powershell
D:\path\to\algorithm-debug-agent\scripts\run-agent-evals.ps1 -Suite Smoke -Case codepath-independent
```

`-TimeoutSeconds` 是每个 OpenCode Case 的上限，不是整个 Suite 的上限。

## Harness 做什么

1. 读取版本化 Suite 和当前目标模块。
2. 为每个 Case 创建隔离 Eval Workspace。
3. 启动真实 `opencode run --format json`，不使用伪造 ToolResponse。
4. 解析 OpenCode JSONL，并按 `callID` 合并同一 Tool 的重复状态快照。
5. 检查必需/禁止 Tool、目标结果、答案模式、证据引用和场景执行预算。
6. 调用 Java `case_audit` 并保存 Workspace Audit。
7. 读取 Case-local `interaction.jsonl` 并保存 Interaction Audit。
8. 比较审计推导的 expected/actual 文件。
9. 写入每个 Case 的 PASS/FAIL 和 Review。

目标 UT 失败可以是通过的 Eval，前提是 Agent 正确理解、归档和解释了该失败。

## 每个 Eval Case 的报告

```text
<evalDirectory>/<evalRunId>/cases/<evalCaseId>/
  request.json
  stdout.jsonl
  stderr.log
  parsed-trace.json
  final-answer.md
  grade.json
  workspace-audit.json
  interaction-audit.json
  expected-vs-actual.json
  case-review.md
```

这些是 Eval 报告，不会复制到算法源码仓库。真实 Case 副本在同一 Eval Run 的
`agent-workspace/projects/<projectId>/cases/<caseId>` 下，便于逐文件复盘。

## 当前 Smoke Case

| Case | 目的 |
|---|---|
| `passing-ut` | 成功 UT 和 Gantt |
| `missing-ut` | 不存在目标，禁止强行运行 |
| `missing-input` | 最早输入异常 |
| `algorithm-loop-guard` | 算法运行时异常根因 |
| `assertion-failure` | 断言预期与算法异常区分 |
| `static-current-source` | 当前源码有界直接调用 |
| `codepath-independent` | CodePath 不依赖 JDWP |
| `jdwp-independent` | JDWP 不依赖 CodePath |
| `artifact-integrity-rejection` | 篡改 Artifact 必须被拒绝 |

执行次数限制是 Eval Case 的回归预算，不是生产 Agent 的全局采集次数。生产工作流按证据是否足够停止。
