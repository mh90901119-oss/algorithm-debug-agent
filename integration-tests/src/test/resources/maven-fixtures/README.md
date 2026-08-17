# Isolated Maven fixtures

`CaseRunArchiveIntegrationTest` 在 JUnit `@TempDir` 中以字符串生成最小 Maven 工程，不在仓库中保存构建产物
或机器绝对路径。Fixture 锁定 JUnit 5.10.3、Maven Compiler Plugin 3.13.0 和 Surefire 3.2.5，并以 Maven
离线参数运行，因此所需坐标必须已存在于执行环境的本地 Maven 仓库。

覆盖场景：测试通过、断言失败、业务异常、编译失败、目标测试未发现和进程超时。断言基于归档后的
`run-request.json`、`run-outcome.json` 与 Artifact SHA-256，不依赖 Maven 控制台的完整文本。
