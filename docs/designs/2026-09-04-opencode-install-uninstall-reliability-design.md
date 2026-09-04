# OpenCode 安装与卸载可靠性设计

日期：2026-09-04

## 1. 目标

在 Windows 11 和 Windows PowerShell 5.1 中支持反复执行以下调试循环：

```text
修改 Java 代码 -> clean build -> 卸载 -> 安装 -> 重启 OpenCode
修改 Skill/Tool -> 卸载 -> 安装 -> 重启 OpenCode
```

保持现有架构：OpenCode 目录只保存 Agent、Skill、Command、Custom Tool 和 JS Runtime 副本；
Java CLI、CodePath Launcher 和 JDWP Collector 仍从稳定的 Agent 仓库路径启动。

## 2. 非目标

- 不实现安装失败回滚。
- 不安装或修改 OpenCode 模型配置。
- 不删除 Workspace、DFX、Eval、Agent 仓库或目标算法文件。
- 不自动删除无法确认归属的历史随机文件。
- 不固定 OpenCode 版本；以实际能力发现结果为准。

## 3. 安装契约

安装写入任何 Agent 资产前必须确认：

1. OpenCode 命令可执行并能返回版本。
2. `bin/ada.cmd` 存在。
3. Java CLI shaded JAR 恰好存在一份。
4. CodePath Launcher JAR 恰好存在一份。
5. JDWP Collector JAR 存在且非空。
6. 所有 OpenCode 源资产存在。

Custom Tool 依赖 `@opencode-ai/plugin`。安装器只在配置目录的 `package.json` 缺少该依赖时
追加声明，保留已有字段和已有版本。优先复用已经安装的插件版本，否则使用当前 OpenCode
版本作为依赖版本。卸载不删除该依赖声明和 `node_modules`，以免影响其他 Tool，并保证频繁
重装不重复下载。

安装每次都先调用同一卸载逻辑清理当前受管文件，再写入最新副本和 Manifest。因此重复
Install 与显式 Uninstall 后再 Install 的结果一致。

## 4. 卸载契约

有 Manifest 时，先校验所有路径属于 Agent 命名空间并检查受管文件未被修改；全部通过后
删除。无 Manifest 时，只删除当前版本明确列举的 Agent 专属路径，用于迁移早期安装；未知
历史文件只保留并由用户确认。

卸载后删除 Agent 专属空目录，但保留共享的 OpenCode 目录、`package.json`、`node_modules`、
Workspace 和仓库运行资产。重复卸载返回已卸载状态，不报错。

## 5. 验证矩阵

| 场景 | 预期 |
|---|---|
| 空 Agent 配置安装 | 自动补依赖声明并发现全部 Tool |
| 无 Manifest 的已知旧文件 | 卸载并可重新安装 |
| 连续两次 Install | 第二次得到相同受管文件集合 |
| 连续两次 Uninstall | 第二次返回已卸载 |
| 受管文件被修改 | 删除前整体拒绝，不部分删除 |
| Manifest 越界路径 | 拒绝，不删除用户文件 |
| 缺少 CLI/CodePath/JDWP JAR | 安装前失败 |
| 完整生命周期 | Install -> Check -> Uninstall -> Install -> Check |

## 6. 已知边界

如果配置目录既没有可用 `@opencode-ai/plugin`，又无法访问配置的包源，OpenCode 无法加载
依赖该包的 Custom Tool。安装器会保留明确的依赖声明并由能力发现返回错误；这是外部依赖
可用性问题，不通过修改模型或目标算法规避。

实施与验证结果见[安装与卸载可靠性审计](../audits/2026-09-04-opencode-install-uninstall-reliability-audit.md)。
