# OpenCode Algorithm Debug Agent 卸载与重新安装

## 1. 用途

修改或更新 Agent 仓库后，可以先移除当前 OpenCode 中由 Algorithm Debug Agent 管理的资产，再执行构建和
安装。卸载不删除 Agent 源码仓、目标算法仓库、Workspace、DFX、Eval、历史 Case 或其他 OpenCode 配置。

## 2. 首次从旧安装迁移

旧版本没有安装所有权清单。先在 Agent 仓库执行一次新版本 Install：

```powershell
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

Install 会生成 `<openCodeConfigDirectory>\.algorithm-debug-agent\install-manifest.json`。之后才能安全卸载。

## 3. 卸载

```powershell
.\scripts\uninstall-opencode.ps1
```

脚本先检查全部受管文件。任何文件在安装后被修改时，卸载在删除前整体失败并列出冲突文件；它不会执行
部分删除。确认冲突内容后，可重新执行 Install 恢复受管资产，再执行卸载。

重复执行卸载是安全的，已卸载时返回 `OPENCODE_ADAPTER_ALREADY_UNINSTALLED`。

## 4. 重新安装

```powershell
.\scripts\build-agent.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

重新启动 OpenCode 会话后，从目标算法模块目录使用 `algorithm-debug` Agent。

## 5. 保留内容

卸载保留 OpenCode 本体、Provider、模型、共享运行依赖、其他 Agent/Skill/Command/Tool、Workspace、DFX、
Eval 和全部历史证据。旧版本遗留的 `.ada-backup-*` 不自动删除，因为其中可能包含安装前的用户文件。
