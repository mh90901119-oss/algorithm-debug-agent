# OpenCode 安装与卸载可靠性审计

日期：2026-09-04

## 1. 审计结论

当前实现支持 Windows 11、Windows PowerShell 5.1 下反复卸载、安装和检查。OpenCode 侧文件
按 Manifest 管理，Java 后端继续从 Agent 仓库启动。安装器不固定 OpenCode 版本，以真实
Agent、Skill、Command 和 Tool 能力发现作为兼容性门禁。

## 2. 已解决问题

- 空 OpenCode 配置未声明 `@opencode-ai/plugin`，导致 Custom Tool 无法加载。
- 安装器未检查 Java CLI 和 CodePath Launcher 构建产物，却可能提前报告安装成功。
- 无 Manifest 的已知旧 Agent 文件无法通过卸载脚本清理。
- 重复 Install 与显式 Uninstall 后 Install 使用不同清理路径。
- Maven 配置示例把相对命令 `mvn` 错写为可配置路径。
- 文档错误声称 Java JAR 被复制到 OpenCode 配置并由卸载器删除。
- 非 clean 构建可能保留旧 `target` 内容。

## 3. 验证结果

| 验证 | 结果 |
|---|---|
| 四个 PowerShell 脚本 AST 解析 | PASS |
| `build-agent.ps1` clean package，20 模块 | PASS |
| Java/Maven/CodePath/JDWP 与集成测试 | PASS |
| OpenCode/Eval Node 契约测试，66 项 | PASS |
| 安装相关 Node 契约测试，16 项 | PASS |
| 临时配置旧文件卸载和重复卸载 | PASS |
| 临时配置自动补充插件依赖声明 | PASS |
| 临时配置连续 Install/Check | PASS |
| 受管文件篡改时整体拒绝卸载 | PASS |
| 越界 Manifest 不删除用户文件 | PASS |
| Workspace 和共享 package.json 保留 | PASS |
| 临时配置卸载后重装 | PASS |
| 真实用户配置连续两次 Install 后 Check | PASS |
| 本地算法模块 Launcher Doctor | PASS |

## 4. 保留边界

- 按需求不实现安装失败回滚；失败修复后可直接重新执行 Install。
- OpenCode 配置既无插件缓存又无法访问包源时，外部依赖无法安装。
- 无 Manifest 时只删除明确列举的 Agent 路径，不猜测随机历史文件的归属。
- GitHub 源码 ZIP 不包含 `target`，目标电脑必须先成功执行 `build-agent.ps1`。
- 安装或卸载后需要重新打开 OpenCode，使当前会话不再使用缓存定义。
- OpenCode 1.18.23 由目标电脑执行同一能力 Check 验证，不按版本号预判兼容性。
