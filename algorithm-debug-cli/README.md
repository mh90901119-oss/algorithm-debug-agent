# Algorithm Debug CLI

CLI 是当前 Java 后端的稳定 JSON 控制面。stdout 每次只输出一个 `ToolResponse 2.0` 文档；参数错误退出 2，
确定性领域错误退出 3，未预期 Agent 错误退出 10。目标 Maven 日志只作为 Run Artifact 保存，不回显到
最终 stdout。

打包：

```powershell
mvn -pl algorithm-debug-cli -am package
```

当前命令：

```text
workspace init --root <workspace>
project register --workspace <workspace> --project <maven-module> [--project-id <id>]
doctor --workspace <workspace> [--project <maven-module>]
case open --workspace <workspace> --project-id <id> --test <class#method> --question-file <utf8-file> [--case-id <id>] [--adapter <id>] [--context-mode reuse|new]
case inspect --workspace <workspace> --project-id <id> --case-id <id>
run execute --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id>
static analyze --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id>
plan codepath create --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id> --request-file <utf8-json>
collection codepath execute --workspace <workspace> --project-id <id> --case-id <id> --plan-id <id>
plan jdwp create --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id> --request-file <utf8-json>
collection jdwp execute --workspace <workspace> --project-id <id> --case-id <id> --plan-id <id>
artifact read --workspace <workspace> --project-id <id> --case-id <id> --artifact-id <id> [--offset-bytes <n>] [--max-bytes <1..65536>]
analysis complete --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id> --result-file <utf8-json>
```

`question-file` 必须是非符号链接的 UTF-8 普通文件，最大 64 KiB。`case open` 只追加 Analysis，不会自动运行 UT；
新 Case 自动创建首个 Context，已有 Case 默认 `reuse`，只有显式 `new` 才追加 Context。Agent 不扫描源码、输入或 POM
推断 Context 变化。同一 Analysis 可以显式执行多次 Run。fat JAR 使用 `ServiceLoader` 发现 Adapter，当前发布包
包含 Wafer Demo Adapter。OpenCode 一次性安装器尚未实现。

`artifact read` 不接受任意文件路径，只读取该 Case 中由 Agent 登记过的 Artifact。每次最多返回
64 KiB 严格 UTF-8 文本，并用 `nextOffsetBytes` 和 `truncated` 支持后续分段读取；读取前会重新校验
Case 路径、文件大小和 SHA-256。`analysis complete` 的结果文件最大 256 KiB，同一 `analysisId`
只能完成一次；文件只允许最终回答、分级结论和显式 Run/Collection/Evidence/Artifact 引用，不保存
模型思维过程。
