# Runtime Output English Contract Design

## 目标

保持 Skill、Prompt、文档、Javadoc、注释和用户内容为中文，同时确保机器生成并可能进入
stdout、stderr、Surefire、CLI `message` 或 OpenCode 工具错误的文本为英文。

## 边界

- Demo 的异常和断言消息使用英文，避免 Windows 默认代码页产生非 UTF-8 归档。
- CLI 参数、输入文件、序列化和 Doctor 的外部消息使用英文。
- `AnalysisResult` 契约拒绝原因使用英文。
- OpenCode 在启动 CLI 前校验确认性结论的非空证据引用，并返回字段级英文错误。
- 中文问题、最终回答、结论 statement、rationale 和 missingEvidence 属于用户或模型内容，不翻译。
- 内部未暴露的不变量消息不做全仓机械翻译，避免 194 个文件的无价值扰动。

## AnalysisComplete 契约

`CONFIRMED_FACT`、`VALIDATOR_CONCLUSION` 和 `SOURCE_INFERENCE` 必须至少包含一个
`evidenceReferenceIds`。OpenCode Runtime 在创建临时 JSON 和启动 CLI 前执行同一规则；Java
契约保留最终防线。

## 验证

- Node 测试证明非法 conclusion 在 CLI 调用前被拒绝。
- 三个 Demo 故障 UT 的 Surefire 诊断消息不含中文。
- Maven 根项目、OpenCode Node 测试和真实断言失败 OpenCode 会话通过。
