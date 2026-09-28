# Schema 与契约

当前公共 JSON 契约由 `ada-contracts` 中的不可变 Java 模型、严格 Jackson 配置和契约测试定义；17 个宿主无关 MCP Tool 输入以 `schemas/mcp/tools/` 为唯一 Schema 源，构建时复制到 MCP Server JAR，不在宿主 Adapter 中维护副本；Eval Suite Schema 位于 `agent-evals/schemas/eval-suite-v1.schema.json`。

规则：

- 每个持久化顶层对象包含 `schemaVersion` 或受版本化父契约约束。
- JSON 反序列化拒绝未知关键字段和非法枚举。
- JSONL 每行是独立对象，支持流式读取和截断报告。
- Schema 演进默认向后兼容；破坏性变化必须升级主版本并增加兼容性测试。
- Raw Trace 不由派生流程改写；Derived、Validation 和 Evidence 保留来源 ID。
- Artifact SHA 只校验归档文件完整性，不代表业务等价。
- MCP Tool 顶层参数一律拒绝未知字段；预算同时引用 Java 公共限制常量并由契约测试校验一致性。

新增独立机器消费方时，应从 Java 契约生成或维护对应 JSON Schema，并在同一变更中增加 round-trip 和兼容性测试；不得在本目录放置与代码不一致的手写占位 Schema。
