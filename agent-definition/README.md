# Canonical Algorithm Debug Agent package

本目录是各宿主共用的 Agent 语义来源，不属于任何一个 CLI：

- `algorithm-debug-agent-v1.json` 固定身份、输入、MCP 能力、工具权限、模型提示和资产哈希。
- `system-prompt-v1.md` 是唯一规范系统指令；宿主 Adapter 只能原样嵌入并记录哈希。
- `completion-contract-v1.schema.json` 是唯一完成契约正文，其他目录不得复制该 Schema。
- `capability-manifest-v1.json` 冻结构建时 MCP Tool、Resource 和 Prompt 清单，并由 Java 打包测试与运行时目录核对。

知识目录不是此包的必需组成。知识只能以 `KNOWLEDGE_HINT` 影响模型的候选假设和源码搜索选择，不能进入
Evidence、Predicate Evaluation 或 Conclusion Gate。

修改 Prompt、完成契约或能力清单后，必须更新 Agent Definition 对应 SHA-256，并运行：

```powershell
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs
mvn -pl algorithm-debug-mcp-server -am test
```
