# Integration tests

Forked-JVM and end-to-end tests against controlled fixtures. The wafer demo adapter initially
targets `D:\javacode\hellomvn` but tests must support a configurable project path.

`WaferBaselineLifecycleSmokeTest` 使用 `debug-harness` 正式 Maven Runner 连续运行目标 UT 两次，验证：

- stdout/stderr 分离归档和退出码 0；
- 每次只捕获运行窗口内唯一合法 Gantt；
- 两个不可变 Run 均包含 165 个操作；
- 两次语义哈希一致并进入 `BASELINE_STABLE`。

```powershell
mvn -pl integration-tests -am test `
  "-Dwafer.demo.projectRoot=D:\javacode\hellomvn" `
  "-Dada.maven.executable=D:\devtools\apache-maven-3.9.16\bin\mvn.cmd"
```

上面的 Maven 路径只是已验证本地示例；实际路径通过 `ada.maven.executable` 配置。未显式提供时，
测试从当前 Maven 进程的 `maven.home/bin` 推导 executable。
