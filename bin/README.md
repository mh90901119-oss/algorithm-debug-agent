# 本地 ADA 启动器

先在仓库根目录构建 CLI 和 CodePath Launcher：

```powershell
mvn -Pcodepath-launcher package
```

随后可从任意目录调用 `D:\path\to\algorithm-debug-agent\bin\ada.cmd`。脚本会自动定位仓库内的
CLI fat JAR 与 CodePath Launcher JAR，并在每次启动时计算 CodePath JAR 的 SHA-256，不需要手工拼
classpath 或设置 CodePath 环境变量。

JDWP Collector 是仓库外的一个本地 JAR。可直接设置 `ADA_JDWP_COLLECTOR_JAR`，也可把
`ada.local.example.cmd` 复制为 `ada.local.cmd` 后填写本机路径。`ada.local.cmd` 已被 Git 忽略，
不得提交公司路径或本机配置。

验证启动器与本地 Demo：

```powershell
.\scripts\verify-ada-launcher.ps1
```

该检查创建临时 Agent Workspace，默认使用 Agent 仓库同级的 `hellomvn` 执行 `doctor`，也可通过
`-DemoProject <path>` 指定其他独立 Maven 模块。它验证 Java、Maven、Maven 模块、CodePath 和 JDWP
配置后删除临时目录；不会运行目标 UT 或启动 JDWP Collector。
