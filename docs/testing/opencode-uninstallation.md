# OpenCode Algorithm Debug Agent 卸载与重新安装

## 卸载内容

```powershell
.\scripts\uninstall-opencode.ps1
```

脚本读取安装时生成的 ownership manifest，只删除由本仓库安装且当前 Hash 仍与安装记录一致的文件。它处理：

- `algorithm-debug` Agent、Skill 和 Command。
- Algorithm Debug Custom Tool 与 JS Runtime。
- `bin/ada.cmd`、Java CLI 和 Collector 运行资产。
- 本次安装生成的 manifest 和可安全删除的空父目录。

## 不删除内容

- `workspaceDirectory` 下的 Case、Trace、Evidence 和日志。
- `dfxDirectory` 和 `evalDirectory`。
- 目标算法仓库、源码、POM、Maven 仓库或算法 Gantt。
- OpenCode 中其他 Agent、Skill、Command、Tool 或用户配置。
- 安装后被用户修改、Hash 已变化的受管文件；脚本会报告并保留。

## 为什么修改代码后需要卸载

安装采用文件复制，不是符号链接。直接再次 Install 可能遇到“已存在且内容不匹配”，这是防止覆盖未知用户修改。标准过程：

```powershell
.\scripts\build-agent.ps1
.\scripts\uninstall-opencode.ps1
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

如果卸载报告受管文件已被修改，先人工确认该文件来源。不要强行删除整个 OpenCode 配置目录。

## 验证卸载与恢复

卸载后运行 Check 应明确报告能力缺失；重新安装后 Check 应发现 Agent、Skill 和关键 Tool。需要验证安装生命周期而不影响本机配置时，运行：

```powershell
.\scripts\verify-opencode-installer.ps1
```

该脚本使用临时配置目录执行安装、检查和卸载，不使用固定目标算法路径。
