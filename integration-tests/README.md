# Integration tests

本模块使用临时目录生成独立的 Maven/JUnit Fixture，不依赖 Wafer Demo、本机算法仓库或固定绝对路径。

`CaseRunArchiveIntegrationTest` 覆盖：

- UT 通过并产生 JSON；
- 断言失败；
- 目标代码抛异常；
- 编译失败；
- 指定测试不存在；
- UT 超时；
- Maven 工具缺失；
- 同 Context 结果一致/变化和跨 Context 对照；
- stdout、stderr、Surefire、JSON、Run fingerprint 与 reproduction reference 的不可变归档。

Fixture 中的失败类型用于证明原始结果都能归档，不构成生产失败分类白名单。未知失败仍由同一运行和证据
链路处理。

```powershell
mvn -pl integration-tests -am test
```

Maven executable 未显式提供时，从当前 Maven 进程的 `maven.home/bin` 推导；也可通过
`ada.maven.executable` 提供路径。
