# Agent Eval Harness

Eval Harness 是开发和发布质量门禁，不是用户运行算法分析时的在线模块。它启动真实
OpenCode 会话，解析 JSONL Tool Trace，并用确定性规则审计 Agent 行为。

## Suites

- `suites/smoke.json`: 10 个快速能力场景。
- `suites/quality-50.json`: 50 个唯一真实场景，覆盖成功、目标缺失、输入边界、异常、断言、
  静态分析、CodePath、JDWP、Artifact 完整性和组合因果 refinement。

组合场景要求 CodePath Collection 先完成，JDWP Plan 再引用其完整 Evidence ID。Harness
还检查动态目标执行区间没有重叠。

## 运行

从目标算法 Maven 模块目录执行：

```powershell
& "<AgentRepository>\scripts\run-agent-evals.ps1" -Suite Smoke
```

运行 50 场景质量套件：

```powershell
& "<AgentRepository>\scripts\run-agent-evals.ps1" -Suite Quality-50 -TimeoutSeconds 900
```

只运行一个 Case：

```powershell
& "<AgentRepository>\scripts\run-agent-evals.ps1" `
  -Suite Quality-50 `
  -Case causal-05 `
  -TimeoutSeconds 900
```

需要使用另一个已配置模型时：

```powershell
& "<AgentRepository>\scripts\run-agent-evals.ps1" `
  -Suite Quality-50 `
  -Case causal-05 `
  -Model "provider/model"
```

脚本不接收目标模块路径。当前工作目录就是目标模块；Suite 不写死任何目标仓库路径。

## 报告

报告写入 `config/agent-settings.json` 的 `evalDirectory`。每次运行包含：

- `environment.json`: Suite、模型、OpenCode、Java、Maven 和安装资产版本。
- `summary.json`、`summary.md`: 运行汇总。
- `cases/<caseId>/request.json`: Case 输入。
- `stdout.jsonl`、`stderr.log`: 原始 OpenCode 进程输出。
- `parsed-trace.json`: 去重后的 Tool 调用、Run、Collection 和最终回答。
- `grade.json`: 正确性、证据和效率结果。
- `workspace-audit.json`: Case 必需文件与 Artifact 完整性。
- `interaction-audit.json`: DFX JSONL 和目标执行重叠检查。
- `expected-vs-actual.json`: Workspace 应有和实有文件差异。
- `case-review.md`: 人工复盘入口。

## 确定性评分

Grader 检查实际 ToolResponse 和 Artifact ID，不调用第二个 LLM 做主观评分。模型服务余额、
限流、不可用或低能力模型未完成工作流会作为外部/模型失败保留，不能通过放宽规则隐藏。

## 单元测试

```powershell
$tests = Get-ChildItem agent-evals/test -Filter *.test.mjs
node --test $tests.FullName
```