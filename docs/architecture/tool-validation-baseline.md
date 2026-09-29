# 工具验证基线

更新日期：2026-09-29。本文件定义交付前必须执行的检查，不保存易失效的历史通过次数。

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
- Coordinator 动作策略、三值 Predicate、Conclusion Gate，以及 `ConclusionFinalization` 对 candidate/decision 的同一身份与修订绑定。

## 2. Canonical Agent、Host Adapter 与 Qwen Adapter

```powershell
node --test agent-definition/test/*.test.mjs `
  integrations/host-adapter-kit/test/*.test.mjs `
  integrations/qwen-cli/test/*.test.mjs
```

必须覆盖 Profile 2.0、Prompt/Manifest 哈希、Manifest 工具权限、Java Catalog 一致性、有界知识注入、无知识兼容、
非法知识拒绝、staging 原子切换和 Qwen ownership 安装/卸载。知识测试不得依赖用户目录或真实宿主配置。

## 3. OpenCode 兼容 Adapter 与 Eval Harness

```powershell
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

必须覆盖 Tool 参数和响应契约、目标执行串行、AGGREGATE 到 TRACE 的模型指引、错误 next action、临时请求清理和 Eval 的证据 lineage 检查。OpenCode 是兼容入口，不是 Canonical Agent 的架构依赖。

## 4. 构建、安装与卸载

```powershell
.\scripts\build-agent.ps1
.\scripts\verify-opencode-installer.ps1
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

必须生成 Java CLI、CodePath Launcher、JDWP Collector 和 Java MCP Server，并校验 MCP JAR 内的 Agent Definition、
Prompt、配置 Schema 与 `ConclusionFinalization` Schema。安装验证不得写死目标算法模块路径；卸载只删除 ownership
manifest 中仍匹配安装 Hash 的文件并保留 Workspace。

Qwen 的无副作用安装闭环使用：

```powershell
.\integrations\qwen-cli\install.ps1 -RepositoryRoot . -Scope TestProfile
.\integrations\qwen-cli\check.ps1 -RepositoryRoot . -Scope TestProfile
.\integrations\qwen-cli\uninstall.ps1 -RepositoryRoot . -Scope TestProfile
```

## 5. 本地动态工具

在可独立用 Maven 执行目标 UT 的算法模块目录运行：

```powershell
D:\path\to\algorithm-debug-agent\scripts\verify-ada-launcher.ps1
```

在 Agent 仓库运行：

```powershell
.\scripts\verify-jdwp-loopback.ps1
```

CodePath 必须证明未选择方法不会被插桩、高频 AGGREGATE 输出有界、TRACE 可保持调用顺序。JDWP 必须证明 loopback attach、条件快照和三类命中计数。

## 6. 真实宿主 E2E

```powershell
.\scripts\run-agent-evals.ps1 -Suite Smoke
```

逐 Case 检查 PASS/FAIL、`case_audit`、应有和不应有文件、Artifact 完整性、`interaction.jsonl` 工具顺序及 Case
Java 日志。Qwen 或其他宿主的真实 E2E 还必须确认：宿主只注册一个 `algorithm-debug` MCP Server、生成的子 Agent
只能看到 Capability Manifest 中的工具、`analysis_finalize` 返回完整 `ConclusionFinalization`，并且拒绝结果不会被
宿主包装为确认性根因。

环境无法执行的检查必须记录命令、阻塞原因和剩余风险，不能以编译通过代替行为验证。测试通过数量属于具体构建
结果，不写成长期协议常量。
