# Bundled JDWP Collector

`jdwp-batch-collector.jar` 是 `bin\ada.cmd` 使用的仓库内置运行产物。普通用户不配置该
JAR 的路径，也不需要保留外部 `mcp-jdwp-java` 仓库。

源码由本仓库统一维护：

- `jdwp-collector-core`：JDI attach、栈帧和有界值快照。
- `tools/jdwp-batch-collector`：Plan 解析、断点安装、事件循环、Raw Trace 和外部 Manifest。

开发者或 CI 在源码变更后运行：

```powershell
.\scripts\package-jdwp-collector.ps1
```

脚本没有路径参数。它使用当前 Agent 仓库，构建 fat JAR，并复制到本目录。安装器和目标算法
仓库不负责构建 Collector。

运行时通过 Manifest v2 的版本与能力声明校验兼容性，不校验 JAR SHA。Plan SHA、Raw Trace
Artifact SHA、provenance、预算和无采集基线一致性仍由 Agent 校验。

迁入源码源自 MIT 许可的 `mcp-jdwp-java`，许可证见 `LICENSE-MIT`。
