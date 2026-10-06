# 目标契约与语义夹具

这些文件用于说明目标设计，不是当前服务已经接受的新协议。实施时须把它们转成测试资源，并补完整 Schema round-trip；不能直接发给现有 v1 MCP 参数当成可运行请求。

- binding-discovery.json：完整目标 Binding v2 例子，展示探索不需要伪造假设/条件。对应 Gap 必须另外声明不可变 observationNeeds。
- observation-semantics.json：Truth/Effect/资格的语义子集夹具，不是完整 ObservationEvaluation wire 文档。
- tool-rejection.json：目标统一反馈例子。全零 snapshotToken 是教学占位哈希，真实服务必须计算；恢复工具与参数沿用当前本地已核查名称。
- local-file-map.json：本地已有文件/类的审计快照，不是公司文件表，也不要求公司拥有相同工具数量。

validate-docs.mjs 检查语法与选定不变量，不代替实际产品的 JSON Schema/序列化/错误闭环测试。
