# OpenCode integration

这是当前唯一客户端适配层，调用链为 OpenCode Model → canonical Skill → OpenCode Custom Tool →
`ada` CLI → Java Core。该目录不复制或改写事实，也不包含算法业务语义。

- `tools/algorithm-debug.ts`：将当前 `context.directory` 作为目标项目目录调用 CLI，原样返回成功 JSON；非零退出只包装进程事实。
- `agents/algorithm-debug.md`：显式 Debug Agent 回退入口。
- `commands/debug-case.md`：`/debug-case` 回退命令。

规范 Skill 位于 `skills/algorithm-debug/SKILL.md`。日常目标体验仍是进入算法仓库后直接执行
`opencode` 并自然语言提问；安装/注册命令必须把该外部 Skill 和本目录注册到 OpenCode 用户配置，
不得把规范 Skill 复制成另一份可漂移内容。当前不提供 Agent MCP Server。
