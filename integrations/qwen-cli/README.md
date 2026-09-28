# Qwen CLI Adapter

该目录把宿主无关的 Canonical Agent Definition 映射为 Qwen CLI 0.20.0 Extension。Adapter 只生成
`qwen-extension.json` 和 `agents/algorithm-debug-agent.md`：17 个工具的参数 Schema、证据门禁、Case 路径和
Collector 逻辑仍只存在于 Java MCP Server。

## 构建和安装

先构建 Java CLI、CodePath Launcher、JDWP Collector 和 MCP Server，并生成四类产物的 SHA-256 清单：

```powershell
.\scripts\build-agent.ps1
```

若机器上存在旧实验版 `algorithm-debug-agent` Extension，先使用 Qwen 自带命令卸载；安装器不会覆盖没有当前
ownership manifest 的同名目录：

```powershell
qwen extensions uninstall algorithm-debug-agent
```

安装、校验和卸载当前 Adapter：

```powershell
.\integrations\qwen-cli\install.ps1 -RepositoryRoot . -Scope User
.\integrations\qwen-cli\check.ps1 -RepositoryRoot . -Scope User
.\integrations\qwen-cli\uninstall.ps1 -RepositoryRoot . -Scope User
```

安装先在同一父目录生成 staging，再原子切换 Extension。重装前会校验旧受管文件哈希，避免覆盖用户修改。
卸载在全部文件未变化且没有额外文件时交给 Qwen 清理注册；存在用户修改时先禁用 Extension，只删除 ownership
manifest 中哈希仍匹配的文件，并逐项报告保留内容。Case Workspace 从不属于 Extension，不会被卸载。

CI/本地验证使用 `-Scope TestProfile`，默认写入 `target/qwen-test-profile`，不会接触用户 Qwen 配置。可用
`-ProfileRoot <absolute-path>` 进一步隔离测试位置。

## 配置边界

- Qwen 只看到一个名为 `algorithm-debug` 的 stdio MCP Server。
- MCP command 固定指向本仓库 `bin/ada-mcp.cmd`；宿主只能提供当前 `${workspacePath}` 作为 `--project`。
- Workspace、Java 和 Maven 只在 `config/mcp-agent-settings.json` 配置。
- `knowledgeDirectory` 可为空或不存在；存在时也只代表 `KNOWLEDGE_HINT`，不改变 Evidence 或结论门禁。
- Agent Definition 中的模型参数是非凭据提示；Qwen 0.20.0 子 Agent front matter 不支持的提示不会被伪装成已生效配置。
- 模型凭据继续由 Qwen 管理，不写入 Extension、Agent Definition、Workspace 或 ownership manifest。
