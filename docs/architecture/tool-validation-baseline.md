# 工具验证基线

更新日期：2026-09-01。本文定义交付前必须验证的事实，不保存容易失效的历史通过次数；具体结果记录在 [最终审计](../audits/2026-09-01-input-first-conditional-runtime-evidence-final-audit.md)。

## 1. Java Reactor

```powershell
mvn -Pcodepath-launcher test
```

要求：

- 根 Reactor 与 CodePath Launcher Profile 全部测试通过。
- Case 输入首次复制、后续复用和变化拒绝有回归测试。
- Gantt 原名捕获且动态 Collection 不复制 Gantt。
- CodePath/JDWP Plan 意图和 Evidence 谱系校验通过。
- JDWP 条件路径、类型比较、观察/匹配/捕获计数和预算通过。
- Workspace 不创建无用目录，Case 审计能发现空目录和未跟踪文件。

## 2. OpenCode Adapter 与 Eval Harness

```powershell
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

要求：

- 13 个 Tool 的参数、调用和响应契约通过。
- 安装器能力发现包含 `algorithm_input_capture`。
- 文档不存在已删除链接、旧归档路径或旧 Eval 数量。
- Eval Grader 检查 Plan 意图、Evidence 谱系和条件化 JDWP，而不是只检查答案文本。

## 3. 构建、安装与卸载

```powershell
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

要求：

- 构建产出 Java CLI、CodePath Launcher 和 JDWP Collector。
- 临时安装验证不写死开发机算法模块路径。
- 卸载只删除 ownership manifest 中仍匹配安装 Hash 的文件，保留 Workspace 和无关配置。
- 重新安装后 OpenCode 能发现 Agent、Skill 和全部关键 Tool；不校验固定 OpenCode 版本。

## 4. 本地 Launcher 与 JDWP

在一个可独立 Maven 执行目标 UT 的算法模块目录运行：

```powershell
D:\path\to\algorithm-debug-agent\scripts\verify-ada-launcher.ps1
```

在 Agent 仓库运行：

```powershell
.\scripts\verify-jdwp-loopback.ps1
```

JDWP 验证要求 loopback attach 成功，并确认条件匹配快照以及 `observedHitCounts`、`matchedHitCounts`、`capturedHitCounts` 分离记录。

## 5. 真实 OpenCode E2E

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
```

10 个 Case 必须逐项检查：

- Eval PASS/FAIL 与失败原因。
- Case `case_audit` 结果。
- 预期控制文件、输入、Run、Collection、Derived 和 Evidence 是否存在。
- 不应存在的可选文件和空目录是否未生成。
- `interaction.jsonl` 是否包含真实 Tool 顺序和关联 ID。
- Case Java 日志是否存在未解释异常或工具故障。
- 跨实体因果 Case 是否使用结构化 Plan 意图、Evidence 谱系和条件化 JDWP。

若环境无法执行某项，审计必须记录命令、阻塞原因和剩余风险，不能以编译通过替代。
