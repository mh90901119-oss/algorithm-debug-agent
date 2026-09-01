# Agent Eval Harness

Eval Harness 不是面向用户回答的在线模块，而是开发和发布门禁。它启动真实 OpenCode 会话，记录 JSONL Tool Trace，并用确定性规则判断 Agent 是否按预期取证。

## Smoke Suite

`suites/smoke.json` 当前包含 10 个 Case：成功运行、输入异常、算法异常、断言失败、工具失败、静态分析、CodePath、JDWP、Workspace 完整性和跨实体因果。

跨实体因果 Case 不只匹配答案文字，还要求：

- 所有要求的 Collection 成功。
- 动态 Plan 包含问题、假设和预期观察。
- Plan 引用已有 Evidence。
- JDWP 使用结构化条件，且预期值匹配目标实体。
- 目标 UT 执行次数不突破预算。

## 运行

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
```

只运行一个 Case：

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke -Case cross-wafer-causal -TimeoutSeconds 600
```

`-Project` 只指定 OpenCode 会话工作目录，不等于“指定 Demo 类型”。产品中目标项目就是用户启动 OpenCode 的 Maven 算法模块。

报告写入 `config/agent-settings.json` 的 `evalDirectory`。每次运行包含 Suite 摘要、Case 报告、OpenCode Trace 以及关联 Workspace 信息。

## 单元测试

```powershell
node --test agent-evals/test/*.test.mjs
```

测试覆盖 Suite Schema、Runner、JSONL Parser、Deterministic Grader 和 Report Writer。Grader 只验证可确定事实，不尝试用第二个 LLM 对回答做主观评分。
