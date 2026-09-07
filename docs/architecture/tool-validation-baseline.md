# 工具验证基线

更新日期：2026-09-06。本文件定义交付前必须执行的检查，不保存易失效的历史通过次数。

## 1. Java Reactor

```powershell
mvn -Pcodepath-launcher test
```

必须覆盖：

- Case 输入复制、复用和变化拒绝。
- 普通 UT、失败指纹、Gantt 原名捕获和动态 Collection 不复制 Gantt。
- CodePath Plan v6 的 TRACE/AGGREGATE、精确方法插桩、Scope 条件和 ordinal 窗口。
- TRACE 调用顺序与投影；AGGREGATE 方法、边、深度、投影分布、值基数和输出预算。
- AGGREGATE 不创建 Invocation 文件，并能通过注册 Artifact ID 使用 `evidence_query SUMMARY/COUNT`。
- JDWP 条件、投影、观察/匹配/采集计数、采样和预算。
- Artifact、Normalizer、Validator、Evidence、失败基线和 Case 审计闭环。

## 2. OpenCode Adapter 与 Eval Harness

```powershell
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

必须覆盖 Tool 参数和响应契约、目标执行串行、AGGREGATE 到 TRACE 的模型指引、错误 next action、临时请求清理和 Eval 的证据 lineage 检查。

## 3. 构建、安装与卸载

```powershell
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

必须生成 Java CLI、CodePath Launcher 和 JDWP Collector。安装验证不得写死目标算法模块路径；卸载只删除 ownership manifest 中仍匹配安装 Hash 的文件并保留 Workspace。

## 4. 本地动态工具

在可独立用 Maven 执行目标 UT 的算法模块目录运行：

```powershell
D:\path\to\algorithm-debug-agent\scripts\verify-ada-launcher.ps1
```

在 Agent 仓库运行：

```powershell
.\scripts\verify-jdwp-loopback.ps1
```

CodePath 必须证明未选择方法不会被插桩、高频 AGGREGATE 输出有界、TRACE 可保持调用顺序。JDWP 必须证明 loopback attach、条件快照和三类命中计数。

## 5. 真实 OpenCode E2E

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
```

逐 Case 检查 PASS/FAIL、`case_audit`、应有和不应有文件、Artifact 完整性、`interaction.jsonl` 工具顺序及 Case Java 日志。环境无法执行的检查必须记录命令、阻塞原因和剩余风险，不能以编译通过替代行为验证。
