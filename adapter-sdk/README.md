# adapter-sdk

Algorithm Debug Agent 的目标项目适配 SPI。Adapter 只隔离项目识别和 Maven/JUnit 启动方式，
不承担算法输入定位、结果目录配置或领域 JSON 解析。

## 当前 SPI

`TargetProjectAdapter` 只有三个职责：

- 声明 Adapter 身份、版本和采集能力；
- 检查目标 Maven 模块并返回 `ProjectDescriptor`；
- 为 BASELINE、CODE_PATH 或 JDWP 创建结构化 `TestLaunchSpec`。

结果 JSON 目录由外部 Workspace 的 `ProjectRegistration.resultJsonDirectory` 配置，通用 Harness
负责捕获、校验、哈希和归档。UT 输入由 UT 自己准备，Agent 不再定义 `InputLocator`。

## 设计边界

- 只依赖 `ada-contracts` 和 JDK；
- 不启动 Maven/JUnit 进程，不拼接 Shell 命令；
- 不包含晶圆调度或其他算法领域语义；
- 不修改目标算法、UT 或 POM；
- Adapter 必须无状态，项目状态通过方法参数显式传递。

## 实现示意

```java
public final class MyMavenAdapter implements TargetProjectAdapter {
    // 实现 descriptor、inspect 和 createLaunchSpec。
}
```

## 测试

```powershell
mvn -pl adapter-sdk -am test
```

当前通用实现见 `adapters/maven-junit-adapter`。
