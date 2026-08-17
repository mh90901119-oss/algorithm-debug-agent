# Wafer Demo Adapter

`wafer-demo-adapter` 是 `algorithm-debug-agent` 的第一个真实目标项目适配器。它把
`hellomvn` Wafer Scheduling Demo 的项目结构、目标 UT、输入 JSON 和甘特图结果 JSON
转换为 `adapter-sdk` 的稳定契约，但不依赖或修改 Demo 的算法代码。

## 当前能力

- 识别 Reference Demo Maven 项目；
- 支持 `WaferSchedulingReproductionTest` 中的专用复现 UT；
- 生成 Maven 测试启动规格；
- 定位复现 UT 的输入，并描述算法公共结果目录；
- 严格解析调度结果为不可变快照；
- 通过 Java `ServiceLoader` 发布 `WaferDemoAdapter`。

Adapter 是无状态描述层，不启动 Maven、不注入 CodePath/JDWP，也不直接执行采集。后续
`debug-harness` 会消费 `TestLaunchSpec`，在不可变复制结果后统一计算原始文件 SHA-256 和
JSON Token 内容 SHA-256；Collector Adapter 会按采集计划扩展子 JVM 参数。

## 支持的复杂 Case

```text
org.example.scheduler.wafer.WaferSchedulingReproductionTest
#reproduceComplexSchedulingFromTimestampedInput
```

对应输入：

```text
input/cases/20260810101501.json
```

对应结果目录与文件模式：

```text
output/algorithm-results/<由目标算法决定的文件名>
```

## 验证命令

模块测试：

```powershell
mvn -pl adapters/wafer-demo-adapter -am test
```

先在 Demo 仓库生成真实结果，再执行真实项目冒烟测试：

```powershell
cd D:\javacode\hellomvn
mvn "-Dtest=org.example.scheduler.wafer.WaferSchedulingReproductionTest#reproduceComplexSchedulingFromTimestampedInput" test

cd D:\javacode\algorithm-debug-agent
mvn -pl adapters/wafer-demo-adapter -am test `
  "-Dwafer.demo.projectRoot=D:\javacode\hellomvn"
```

未提供 `wafer.demo.projectRoot` 时，真实项目冒烟测试会跳过，普通单元测试仍全部运行。

Adapter 不选择最新文件，也不约束文件名。Debug Harness 比较运行前后的目录清单，只接受本次 UT
新增或修改且能被 Parser 验证的唯一结果，避免 UT 失败时误读历史文件。

## 设计文档

完整约束、类设计和测试证据见：

```text
docs/designs/2026-08-10-wafer-demo-adapter-design.md
```
