# Algorithm Debug Agent 工具单点验证基线

> 2026-08-19 更新：下方 2026-08-10 的包级 CodePath 数据保留为历史基线，不代表当前实现。
> 当前 v2 Launcher 从归档 Plan 读取精确 `className + methodName + descriptor`，写出单一 Raw JSONL，
> 并在事件数、字节数和时间预算内运行。对 `D:\javacode\hellomvn` 的
> `SimpleWaferScheduler#schedule` 真实 smoke 连续三次均通过：测试体耗时 4.376 s、4.481 s、4.411 s，
> 中位数 4.411 s；每次 2 个事件、509 bytes，逐行身份均与 Plan 相等。旧样本与当前选择器不同，
> 因此不能据此计算性能提升百分比。上游仍可能对未选方法执行 Advice 回调，这是当前已知限制。

- 状态：已验证事实基线
- 版本：1.0
- 日期：2026-08-10
- 适用范围：Algorithm Debug Agent 架构、模块设计、实施计划和验收

## 1. 文档作用

本文只记录已经在真实晶圆调度Demo上执行并观察到的事实，防止架构文档把目标设计误写成现有能力。产品目标仍是零算法源码侵入、易用、可迁移并适应大型复杂算法。

状态词统一定义：

| 状态 | 含义 |
|---|---|
| `VERIFIED` | 已在指定commit、命令和UT上得到产物 |
| `PROTOTYPE` | 主链路已验证，但性能、Schema或产品封装未完成 |
| `DESIGNED` | 已有详细设计，尚未证明实现 |
| `NOT_STARTED` | Agent仓库内尚未编码 |

## 2. 共同验证对象

```text
target repository : D:\javacode\hellomvn
Java              : 21
build/test         : Maven + JUnit 5
test selector      : org.example.scheduler.wafer.SimpleWaferSchedulerTest
                     #complexParallelModeSchedulesThreeJobsAcrossFiveChambers
input              : input/cases/complex-parallel-three-jobs-five-chambers.json
result             : output/complex-parallel-five-chambers-result.json
operations         : 165
wafer count        : 15
Gantt SHA-256      : CD09CDB200821C47E6FB464274BD36C317245B4026E37999D27ED9614DC4CB4D
```

目标项目当前POM不包含CodePathTracer或JDWP Collector依赖，原始UT不包含采集API调用。

## 3. CodePathTracer验证

### 3.1 工具身份

```text
repository          : D:\mcpcode\code-path-tracer
git commit          : f8be120
library coordinate  : io.github.takahirom.codepathtracer:code-path-tracer:0.1.0-SNAPSHOT
launcher bundle     : integrations/external-junit-launcher/target/code-path-tracer-junit-launcher.jar
observed bundle size: 11,607,236 bytes
observed bundle hash: 4B1E9E0651C924BA4BED4BBB3A9EDF6225C4EDEA5B8E6847C83A28BDAE9638C4
```

该Hash只标识本次构建产物；正式发行需要可复现构建或发布时重新生成并锁定Hash。

### 3.2 已验证链路

```text
Maven test-compile + test runtime classpath
  -> 干净Java子JVM
  -> ExternalJUnitTraceLauncher
  -> CodePathTracerAgent.ensureInstalled()
  -> JUnit Platform按class#method执行原始UT
  -> 原始Gantt JSON + method-path JSONL
```

结果：

| 项目 | 结果 |
|---|---:|
| 测试发现/成功/失败 | 1 / 1 / 0 |
| 方法进入/退出事件 | 41,436 |
| Trace文件 | 7,010,648 bytes |
| 关键方法 | `schedule`、15次`scheduleWafer`等 |
| Gantt Hash | 与共同基线一致 |

### 3.3 已证明能力

- 不修改算法源码、原始UT或目标POM；
- 外部JUnit Launcher在测试发现前安装Instrumentation；
- 按单一package prefix过滤事件；
- 输出方法进入/退出、调用深度、线程、类名和方法名JSONL；
- 原始UT保持正常输出和断言行为；
- Agent维护者可以在CodePath仓库独立构建Bundle。

### 3.4 尚未证明或尚未实现

- JSON Plan驱动的多include/exclude和method catalog；
- 参数/返回值业务摘要；
- `maxEvents/maxOutputBytes/timeout`硬预算；
- 达到预算后的优雅截断；
- 流式写盘；当前原型在内存保存事件后写文件；
- Method Path Summary、Manifest和调用图Viewer；
- 多模块Maven自动classpath解析；
- 大型算法压力测试和长期内存上限。

因此CodePath状态为：底层和外部运行方式`VERIFIED`，Agent适配器为`PROTOTYPE/NOT_STARTED`。

## 4. JDWP Batch Collector验证

### 4.1 工具身份

```text
repository           : D:\mcpcode\mcp-jdwp-java
git commit           : 1ef7d22
modules              : jdwp-core + jdwp-batch-collector
collector version    : 1.0.0
collector JAR        : jdwp-batch-collector/target/jdwp-batch-collector.jar
observed JAR size    : 2,376,510 bytes
observed JAR hash    : E75C813695F11BE52803CB62B4667E0BCCE9E83832D61AE1873EADAFBC448E7B
```

该Hash同样只标识本次构建产物。

### 4.2 已验证链路

```text
Maven Surefire fork
  -> JDWP server=y,suspend=y,address=127.0.0.1:5005
  -> Batch Collector读取debug-plan.json并attach
  -> 安装SimpleWaferScheduler.scheduleWafer():120
  -> resume目标JVM
  -> 命中时短暂停EVENT_THREAD并读取JDI事实
  -> raw-trace.jsonl + collection-manifest.json
  -> VM death和UT正常退出
```

结果：

| 项目 | 结果 |
|---|---:|
| Core/Collector单元测试 | 7 passed |
| 目标UT | 1 passed |
| 安装位置 | 1 |
| tracepoint命中 | 165 |
| 总JSONL事件 | 167 |
| Raw Trace | 2,246,165 bytes |
| 完成原因 | `vm_death` |
| Gantt Hash | 与共同基线一致 |

165次命中等于15片wafer乘每片11个操作。实际读取到`context.job.jobId`、`context.wafer.waferId`、`planned`、`requiredResources`、`readyAt`、`resourcesReadyAt`、调用栈和有界对象字段。

### 4.3 已证明能力

- JDI/JDWP外部attach且不修改算法源码；
- JSON计划、已加载/未加载类绑定、line breakpoint；
- `SUSPEND_EVENT_THREAD`命中采集和可靠恢复；
- stack、locals、`this`、primitive/String/enum、数组和有界对象字段；
- `maxFrames/maxDepth/maxItems/maxStringLength/maxHits/maxEvents/idleTimeout`基础限制；
- 不调用目标业务方法；
- JSONL和Manifest落盘；
- VMDeath正常收尾。

### 4.4 尚未证明或尚未实现

- Source Anchor到最终行号/方法描述符/hash的编译和校验；
- local variable allowlist；
- 字段路径projection和领域集合视图；
- firstN/everyNth/maxCaptured采样；
- 总Trace字节、JDI read、对象节点、队列等硬预算；
- 有界异步Writer和目标线程暂停指标；
- Plan cost estimator和collection preview；
- Agent侧动态端口、进程树监管、失败恢复和语义Hash自动校验；
- Raw Trace到Domain Trace的Normalizer；
- 大计划/高频命中/100k事件压力测试。

因此JDWP Core/Collector MVP状态为`VERIFIED`，P0性能加固为`DESIGNED`，Agent Adapter为`NOT_STARTED`。

### 4.5 Agent P3 集成验证（2026-08-18）

上面的 `Agent Adapter=NOT_STARTED` 是外部 Collector 原型验证时的历史结论；当前状态已更新为
`P3 VERIFIED`。当时验证使用 Collector `1.0.0`。自 2026-08-19 起，Agent 将 JDWP Collector
视为一个通过路径配置的本地 JAR，仅记录版本，不再锁定或校验 JAR 数字指纹；该调整不改变下述
功能验证结果。

真实 Wafer 指定 UT 的无采集 Baseline 与单点 JDWP Collection 均成功。采集点为
`SimpleWaferScheduler.scheduleWafer:81`，最大命中 3；实际得到 3 个 `tracepoint_hit`、5 个总事件、
4,610 bytes Raw JSONL，目标 Maven/UT 与 Collector 退出码均为 0。采集运行的规范化 Gantt Hash 与
同一 Context 无采集参考一致，Baseline 为 `MATCHED`，`evidenceUsable=true`，结束后无 Java/Maven
遗留进程。归档同时包含 Agent Plan、运行时 Collector Plan、Raw Trace、外部/Agent Manifest、
四份日志、Gantt 和 Baseline Check。

审计中修复了两项只在真实 Surefire/Collector 组合下出现的问题：Surefire 在 suspended 阶段不稳定
转发 listening banner，因此就绪检测改为不建立连接的 loopback 端口绑定探测；Agent 严格读取器按
Collector 1.0 完整 Manifest 字段校验 endpoint、时间和计数。失败路径仍追加保存，且后处理失败不会
丢失已经观察到的目标/Collector 进程事实。

## 5. 两工具的职责边界

| 问题 | CodePathTracer | JDWP Collector |
|---|---|---|
| 实际走过哪些方法 | 主要工具 | 采集点stack可辅助 |
| 方法进入/退出顺序 | 是 | 否 |
| 局部变量和对象字段 | 否 | 是 |
| 大范围侦察 | 是，但必须过滤 | 不推荐全量深快照 |
| 聚焦业务值 | 否 | 是 |
| Domain Trace | 否 | 否，由Normalizer派生 |

默认分轮：Baseline → Static Analysis → CodePath → JDWP broad/shallow → JDWP focused/projected。每轮必须与Baseline语义Hash一致。

## 6. 对Agent实施顺序的约束

1. 先完成Case、Baseline、Gantt语义Hash和Artifact身份；
2. 静态分析至少先提供Source Anchor和Method Catalog，避免大模型自由写行号；
3. CodePath适配器使用计划文件和干净子JVM，不能生成采集UT；
4. JDWP适配器只执行Compiled Plan，不能把LLM原始JSON直接交给Collector；
5. 大型算法接入前完成CodePath流式预算和JDWP P0关键加固；
6. Raw Trace不直接进入LLM上下文；先摘要、索引、规范化和Evidence Query；
7. MCP Server不是默认依赖，只用于独立重跑后的交互式聚焦深挖。

## 7. 易用性目标

最终用户不拼classpath、不手工开两个终端、不选择JDWP端口，也不直接编辑行号计划。目标CLI：

```text
ada analyze --project <root> --test <class#method> --question <text>
```

Harness内部自动完成doctor、构建、Baseline、计划编译、子JVM、Collector、Hash校验和Artifact索引。当前手工命令只用于开发验证，不是产品交互契约。
