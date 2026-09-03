# Integration tests

本模块在临时目录生成独立 Maven/JUnit Fixture，不依赖算法 Demo、开发机目标仓库或固定绝对路径。

`CaseRunArchiveIntegrationTest` 覆盖：

- UT 通过并产生 JSON Gantt。
- 断言失败。
- 目标代码抛出业务异常。
- 编译失败。
- 指定测试不存在。
- UT 超时。
- Maven 工具缺失。
- 修改源码后复用 Case 并追加新 Analysis。
- 同一 Analysis 的多次普通 Run 追加归档。
- stdout、stderr、Surefire、Gantt 和失败指纹的不可变归档。
- Case 下不生成多余的中间身份目录。

普通失败 Run 只归档结构化失败指纹，不互相比较；`MATCHED/CHANGED/INCOMPARABLE` 由后续动态 Collection 与同一 Analysis 的普通 Run 基线比较产生。

Fixture 中的失败类型只用于证明原始结果均可归档，不构成目标算法失败分类白名单。未知失败仍由相同运行和证据链路处理。

```powershell
mvn -pl integration-tests -am test
```

Maven 未显式配置时从当前环境发现；正式安装可在 `config/agent-settings.json` 的 `mavenExecutable` 中配置。