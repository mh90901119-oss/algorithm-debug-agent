# Canonical Algorithm Debug Agent Package

本目录是各宿主共用的 Agent 语义来源，不属于任何一个 CLI：

- `algorithm-debug-agent-v1.json` 是 Profile 2.0 定义，固定身份、输入、必需 MCP Server、规范资产哈希和有界知识策略。
- `system-prompt-v1.md` 是唯一规范系统指令；宿主 Adapter 原样嵌入正文并记录 SHA-256。
- `capability-manifest-v1.json` 是宿主可见能力与工具权限的唯一快照。Java Catalog、打包测试和运行时目录负责验证其没有漂移。

只有 MCP Tools 是必需宿主能力。Resources 与 Prompts 仍由 Server 提供，但宿主不支持它们时，Adapter 可通过嵌入
规范 Prompt 和直接调用 Tools 保持完整主流程。因此 Agent Definition 不复制工具清单，也不声明宿主模型参数。

`analysis_finalize` 的完成结果由 Java 服务生成：`ConclusionFinalization` 同时绑定模型提交的
`ConclusionCandidate` 与确定性的 `ConclusionDecision`。宿主只负责呈现，不再让模型复刻第二套完成 JSON。

知识目录不是必需组成。Adapter 只递归读取有界、非符号链接、有效 UTF-8 的 Markdown，按稳定路径顺序附加为
`KNOWLEDGE_HINT`，并记录相对路径、字节数和 SHA-256。知识只能影响候选假设和源码搜索选择，不能进入 Evidence、
Predicate Evaluation 或 Conclusion Gate；没有知识文件时，调查与门禁语义不变。

修改 Prompt 或能力清单后，必须更新 Agent Definition 对应 SHA-256，并运行：

```powershell
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs
mvn -pl algorithm-debug-mcp-server -am test
```
