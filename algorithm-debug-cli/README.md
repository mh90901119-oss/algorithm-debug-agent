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
case open --workspace <workspace> --project-id <id> --test <class#method> --question-file <utf8-file> [--case-id <id>] [--adapter <id>]
case inspect --workspace <workspace> --project-id <id> --case-id <id>
run execute --workspace <workspace> --project-id <id> --case-id <id> --analysis-id <id>
```

`question-file` 必须是非符号链接的 UTF-8 普通文件，最大 64 KiB。`case open` 只创建 Analysis 和 Context，
不会自动运行 UT；同一 Analysis 可以显式执行多次 Run。fat JAR 使用 `ServiceLoader` 发现 Adapter，当前发布包
包含 Wafer Demo Adapter。OpenCode 一次性安装器尚未实现。
