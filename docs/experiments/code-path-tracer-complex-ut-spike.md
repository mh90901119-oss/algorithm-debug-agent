# Code Path Tracer 复杂晶圆调度 UT 接入试验

> 历史Spike：本文记录最初通过临时采集UT验证API的过程。该临时UT和目标项目依赖现已删除；正式方向已经验证为Agent自带依赖Bundle + 外部JUnit Platform Launcher。当前使用方式以[模块详细设计第13节](../architecture/algorithm-debug-agent-module-detailed-design-v1.md)为准。

- 日期：2026-08-08
- Code Path Tracer仓库：`D:\mcpcode\code-path-tracer`
- Git基线：`f8be120`
- 发布坐标：`io.github.takahirom.codepathtracer:code-path-tracer:0.1.0-SNAPSHOT`
- 目标UT：`CodePathTracerComplexSchedulerTemporaryTest`
- 状态：端到端打通

## 1. 构建

项目要求JDK 17 Gradle toolchain。本机使用：

```text
D:\mcpcode\toolchains\jdk-17.0.20+8
```

构建、运行核心测试并发布到Maven Local：

```powershell
$env:JAVA_HOME='D:\mcpcode\toolchains\jdk-17.0.20+8'
.\gradlew.bat :code-path-tracer:clean :code-path-tracer:test :code-path-tracer:publishToMavenLocal
```

产物：

```text
D:\mcpcode\code-path-tracer\code-path-tracer\build\libs\code-path-tracer.jar
C:\Users\zhao1k\.m2\repository\io\github\takahirom\codepathtracer\code-path-tracer\0.1.0-SNAPSHOT\code-path-tracer-0.1.0-SNAPSHOT.jar
```

## 2. Demo依赖

`pom.xml`增加test作用域依赖。发布POM会传递引入：

- Kotlin Stdlib 1.9.22；
- Byte Buddy 1.15.11；
- Byte Buddy Agent 1.15.11。

因此该依赖只服务诊断测试，不进入算法主运行时。

## 3. 临时采集UT

文件：

```text
src/test/java/org/example/scheduler/wafer/CodePathTracerComplexSchedulerTemporaryTest.java
```

它执行与复杂五腔Case相同的输入和算法入口：

```text
complex-parallel-three-jobs-five-chambers.json
  -> SimpleWaferScheduler.schedule(input)
```

测试内完成：

1. 在加载调度模型前安装Code Path Tracer Byte Buddy transformer；
2. Filter只保留`org.example.scheduler.wafer`包；
3. Formatter把Enter/Exit事件转换为JSONL；
4. Logger先保存事件，Trace结束后写文件；
5. 写出Trace运行的Gantt结果；
6. 校验165个操作、15片Wafer、CH1~CH5和makespan=300；
7. 校验Trace包含`schedule`、`scheduleWafer`、`buildPlan`、`addTransfer`和`requiredResources`。

## 4. 运行

必须显式启用并单独选择诊断UT：

```powershell
mvn -DargLine=-XX:+EnableDynamicAgentLoading `
    -DcodePathTrace=true `
    -Dtest=CodePathTracerComplexSchedulerTemporaryTest `
    test
```

普通测试：

```powershell
mvn test
```

会跳过该诊断UT，只执行原有算法UT。

## 5. 输出

```text
output/code-path-tracer/complex-scheduler-method-path.jsonl
output/code-path-tracer/complex-parallel-five-chambers-result.json
```

本次成功采集结果：

```text
Trace事件：21438
METHOD_ENTER：10719
METHOD_EXIT：10719
类：14
方法：59
最大深度：4
Trace大小：约2.95 MB
```

Trace运行结果与原始复杂Case结果逐字段规范化比较一致，文件SHA-256也一致：

```text
CD09CDB200821C47E6FB464274BD36C317245B4026E37999D27ED9614DC4CB4D
```

最终接入方式连续运行三个独立测试JVM，三次均得到21438个事件，Trace SHA-256一致：

```text
18634BB15851430EA6BC93BB2B8D5EA10CBFC0A30DC19442B5CFD999A90FAEED
```

## 6. 发现的库行为

### 6.1 它是运行时动态Attach，不是premain Agent

`CodePathTracer.trace()`内部调用`ByteBuddyAgent.install()`，在当前测试JVM动态安装Instrumentation并转换类。

### 6.2 需要隔离测试JVM

当完整JUnit套件在测试发现阶段提前加载`SimpleWaferScheduler`后，该库对已加载外层类的retransform出现过未命中；嵌套Record仍会被采集，但顶层`schedule`缺失。

当前可靠方案：

- 诊断UT独立选择运行；
- 在读取输入和创建Scheduler前显式调用`CodePathTracerAgent.ensureInstalled()`；
- 普通测试默认跳过诊断UT。

这与未来Debug Harness“每一轮在独立子JVM中执行指定UT”的设计一致。

### 6.3 当前Trace体积偏大

包级Filter会采集大量Record accessor，例如`waferId()`、`operationType()`。本次用于证明端到端能力可以接受；正式Agent需要：

- 类/方法include计划；
- accessor排除规则；
- 最大事件数；
- 只保存Enter或调用边摘要的选项；
- Raw Trace与LLM摘要分离。

## 7. 结论

本次已经验证：

- 本地仓库可以构建和发布；
- Maven/Java 21 Demo可以调用Kotlin API；
- 无需修改算法源码；
- 临时JUnit 5 UT可以采集真实复杂调度调用路径；
- 采集运行没有改变Gantt调度结果；
- 未来Agent应通过独立子JVM和计划化Filter封装该能力。
