# adapter-sdk

Algorithm Debug Agent 的目标项目适配 SPI。它把通用 Agent 与具体算法仓库的 Maven 启动方式、
输入位置和结果格式隔离开。调度结果内容指纹由 Debug Harness 统一计算，不属于目标项目 Adapter。

## 当前能力

- Adapter 身份、版本和能力声明；
- Maven 目标项目描述；
- BASELINE、CODE_PATH、JDWP 运行模式；
- 结构化 `TestLaunchSpec`；
- `InputLocator`；
- `ScheduleResultLocator`；
- 泛型 `ScheduleResultParser<T>`；
- 无状态组合接口 `TargetProjectAdapter<T>`；
- 带稳定错误码和 cause 的 `AdapterException`。

## 设计边界

- 只依赖 `ada-contracts` 和 JDK；
- 不启动 Maven/JUnit 进程；
- 不拼接 Shell 命令；
- 不包含晶圆调度业务语义；
- 不修改目标算法源码；
- 具体算法实现位于独立 Adapter 模块。

## 实现示意

```java
public final class MyAlgorithmAdapter
        implements TargetProjectAdapter<MyScheduleSnapshot> {
    // 实现 inspect、启动规格、输入定位、结果源和结果解析。
}
```

Adapter 应保持无状态。一次 inspect 得到的 `ProjectDescriptor` 必须显式传给后续方法，不能保存在
“当前项目”字段中。

## 测试

```powershell
mvn -pl adapter-sdk -am test
```

详细设计见 `docs/designs/2026-08-10-adapter-sdk-design.md`。
