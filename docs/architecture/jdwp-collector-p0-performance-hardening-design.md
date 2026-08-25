> STATUS UPDATE (2026-08-23): Exact descriptors, code indexes, typed values, projections, duplicate-install prevention and request-group hit limits are implemented in the Agent repository. Remaining proposals require evidence from real target UTs before implementation.

# JDWP Collector P0 性能加固详细设计与开发方案

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档状态 | 设计完成，尚待实施；2026-08-10再次用真实UT确认必要性 |
| 优先级 | P0：接入真实大型算法前建议完成 |
| 目标仓库 | `D:\mcpcode\mcp-jdwp-java` |
| 主要模块 | `jdwp-core`、`jdwp-batch-collector` |
| 兼容模块 | `jdwp-mcp-server` |
| 目标场景 | 大型 Java/JUnit 离线算法的无源码侵入运行时事实采集 |
| 非目标 | 不重写整个 JDWP-MCP，不修改调度算法，不在本阶段实现 Domain Trace |

关联文档：

- `docs/architecture/algorithm-debug-agent-complete-design.md`
- `docs/architecture/jdwp-mcp-collector-refactoring-design-and-usage.md`
- `docs/architecture/tool-validation-baseline.md`

---

## 2. 执行摘要

现有 JDWP Batch Collector MVP 已经打通：

```text
Debug Plan
  -> JDWP attach
  -> 自动安装 tracepoint
  -> 命中后读取 stack/locals/this/object
  -> 自动恢复线程
  -> raw-trace.jsonl
  -> collection-manifest.json
```

该架构方向正确，不需要推翻。

P0 加固的目标，是把当前“功能可用的 Collector MVP”提升为：

```text
面对较大采集计划、高频命中和大型对象时，
仍然具有可预测开销、明确硬限制、可观测性能和安全退化能力的 Collector。
```

P0 主要解决六类问题：

1. 大计划查找和安装效率；
2. JDI/JDWP 往返次数；
3. 深对象快照体积；
4. 目标算法线程暂停时间；
5. JSON 序列化和磁盘写入吞吐；
6. 内存、文件、时间和事件数量失控。

---

## 3. 当前性能基线

### 3.1 测试对象

UT：

```text
SimpleWaferSchedulerTest
  #complexParallelModeSchedulesThreeJobsAcrossFiveChambers
```

采集点：

```text
SimpleWaferScheduler.scheduleWafer():120
```

命中次数：

```text
165
```

### 3.2 实测结果

| 模式 | UT 时间 | 相对基线 | Trace 大小 | 单次命中增量 |
|---|---:|---:|---:|---:|
| 不启用 Collector | 平均 0.174s | 1.0x | 0 | — |
| 只采位置 | 0.277s | 1.59x | 61 KB | 约 0.62ms |
| 位置 + 8 层调用栈 | 0.310s | 1.78x | 235 KB | 约 0.82ms |
| 栈 + 全部局部变量 + 深度 2 对象 | 2.425s | 13.9x | 2.25 MB | 约 13.6ms |

完整快照在另一轮运行中为 2.853s，说明当前环境下完整采集存在一定抖动，但主要结论稳定：

```text
断点和调用栈开销相对可控；
局部变量及深对象展开是主要性能热点。
```

性能测试产物：

```text
output/jdwp-performance/location-only/
output/jdwp-performance/stack-only/
output/jdwp-performance/full-snapshot/
```

### 3.3 2026-08-10独立复验

使用当前`mcp-jdwp-java` commit `1ef7d22`重新构建`jdwp-core`与`jdwp-batch-collector`，以相同复杂UT和`scheduleWafer():120`计划采集，结果为：

| 项目 | 复验结果 |
|---|---:|
| UT | 1 passed |
| tracepoint命中 | 165 |
| 总JSONL事件 | 167 |
| Raw Trace | 2,246,165 bytes |
| Surefire测试时间 | 2.284s |
| 完成原因 | `vm_death` |
| Gantt SHA-256 | `CD09CDB200821C47E6FB464274BD36C317245B4026E37999D27ED9614DC4CB4D` |

该结果与原性能基线的“完整快照约2.25MB、约2.4秒”一致。原始事件中大量空间来自全部locals、HashMap/List内部字段和深度2对象图，而业务分析实际只需要少量字段。这直接确认Local allowlist、字段路径投影、采样、总字节限制和Writer解耦仍是大型复杂算法接入前的P0，而不是可推迟的美化项。

本次复验没有证明本文后续P0设计已经实现。本文所有`CompiledTracePlan/localVariables/paths/sampling/async writer/performance metrics`内容仍是目标设计，只有Definition of Done全部通过后才能修改此状态。

---

## 4. 当前实现的复杂度分析

定义：

```text
P = Debug Plan 中 tracepoint 数量
C = tracepoint 涉及的不同 class 数量
H = 所有 tracepoint 总命中次数
V = 每次采集的局部变量数量
F = 单个对象最多读取的字段数
D = 对象最大展开深度
B = 平均单事件 JSON 字节数
```

当前近似复杂度：

```text
计划安装：
O(P × JDI classesByName)

ClassPrepare：
O(P) / 每个 ClassPrepareEvent

命中查找：
O(P) / 每次命中

对象快照：
O(V × F^D)

写盘：
O(H × B)，并且每个事件 flush
```

整体可粗略表示为：

```text
O(P) 初始化
+ O(H × P) tracepoint 查找
+ O(H × V × F^D) JDI 对象快照
+ O(H × B) 序列化与写盘
```

P0 目标复杂度：

```text
计划编译：
O(P)

按 ID 查找：
O(1)

ClassPrepare 路由：
O(1) 找到该类的采集点集合

对象快照：
O(声明式投影字段数量)

目标线程等待：
只包含必要 JDI 读取，不包含磁盘 IO
```

---

## 5. P0 设计目标

### 5.1 功能目标

- 大计划在执行前编译为高效索引；
- 同一目标类只查询一次；
- 同一未加载类只注册一个 ClassPrepareRequest；
- 命中后 O(1) 找到 Tracepoint；
- 支持局部变量白名单；
- 支持字段路径投影；
- 支持命中采样；
- JDI 字段批量读取；
- JSON 序列化和写盘移出目标线程暂停区间；
- Writer 使用有界队列；
- 支持批量/定时 flush；
- 支持事件数、字节数、持续时间、队列大小和对象节点硬限制；
- 输出完整性能指标；
- 达到限制时安全恢复目标线程并生成 Manifest。

### 5.2 兼容目标

- 现有 Debug Plan 继续可用；
- 现有 `raw-trace.jsonl` 核心字段保持兼容；
- 现有 `collection-manifest.json` 只新增字段；
- 现有 MCP Server 行为保持不变；
- Collector 仍然不依赖 Spring/MCP；
- 不要求算法项目添加依赖或埋点。

### 5.3 非目标

本阶段不实现：

- 调度 Domain Trace；
- Trace Normalizer；
- Trace Validator；
- Agent 自动分析；
- 任意 Java 表达式执行；
- 分布式 Collector；
- 生产在线 JVM 持续监控；
- 通用 Java Profiler；
- 方法级全量录制。

---

## 6. 加固后的总体架构

```text
debug-plan.json
       │
       ▼
DebugPlanParser
       │
       ▼
PlanValidator
       │
       ▼
TracePlanCompiler
├── tracepointById
├── tracepointsByClass
├── classPrepareSpecs
├── compiled projections
├── compiled sampling rules
└── estimated cost
       │
       ▼
BreakpointInstaller
├── loaded class binding
├── one ClassPrepareRequest per class
└── installation diagnostics
       │
       ▼
JDI Event Loop
├── O(1) tracepoint lookup
├── sampling decision
├── batch locals read
├── projected field read
├── object node budget
└── immutable RawSnapshot
       │
       ▼
Bounded Snapshot Queue
├── max events
├── max estimated bytes
├── backpressure policy
└── queue metrics
       │
       ▼
AsyncJsonlWriter
├── JSON serialization
├── buffered write
├── batch/time/byte flush
├── file byte limit
└── final drain
       │
       ├── raw-trace.jsonl
       └── collection-manifest.json
```

---

## 7. 代码组织设计

建议新增或调整：

```text
jdwp-core/
└── src/main/java/one/edee/mcp/jdwp/core/
    ├── snapshot/
    │   ├── SnapshotLimits.java
    │   ├── SnapshotBudget.java
    │   ├── SnapshotProjection.java
    │   ├── ProjectionPath.java
    │   ├── JdiValueSnapshotter.java
    │   ├── JdiFieldBatchReader.java
    │   ├── FrameSnapshotter.java
    │   └── ImmutableSnapshot.java
    └── JdiSocketAttacher.java

jdwp-batch-collector/
└── src/main/java/one/edee/mcp/jdwp/collector/
    ├── CollectorMain.java
    ├── plan/
    │   ├── DebugPlan.java
    │   ├── DebugPlanValidator.java
    │   ├── CompiledTracePlan.java
    │   ├── CompiledTracepoint.java
    │   ├── TracePlanCompiler.java
    │   └── PlanCostEstimator.java
    ├── breakpoint/
    │   ├── BreakpointInstaller.java
    │   ├── BreakpointBinding.java
    │   └── ClassPrepareRegistry.java
    ├── runtime/
    │   ├── TracePlanExecutor.java
    │   ├── HitSampler.java
    │   ├── CollectionLimits.java
    │   ├── CollectionCounters.java
    │   └── CollectionStopReason.java
    ├── output/
    │   ├── AsyncJsonlWriter.java
    │   ├── BoundedSnapshotQueue.java
    │   ├── FlushPolicy.java
    │   └── CollectionManifestWriter.java
    └── model/
        ├── RawTraceEvent.java
        ├── TracepointHitEvent.java
        ├── LimitReachedEvent.java
        └── CollectionMetrics.java
```

第一阶段不强制立即移动现有包；可以先增加类，测试稳定后再整理包结构。

---

## 8. P0-A：Trace Plan 编译与索引

### 8.1 当前问题

命中断点时当前实现会遍历：

```java
plan.tracepoints.stream()
    .filter(candidate -> candidate.id.equals(tracepointId))
    .findFirst();
```

复杂度：

```text
O(P) / hit
```

同一个类中的多个 tracepoint 还会重复调用：

```java
vm.classesByName(className)
```

### 8.2 设计

增加不可变的 `CompiledTracePlan`：

```java
public record CompiledTracePlan(
    String sessionId,
    Map<String, CompiledTracepoint> tracepointById,
    Map<String, List<CompiledTracepoint>> tracepointsByClass,
    CollectionLimits limits,
    WriterSettings writerSettings,
    PlanCostEstimate estimate
) {
}
```

编译后保证：

```text
tracepoint ID 唯一
className 非空
line/method anchor 合法
projection path 已解析
sampling rule 已校验
所有集合不可变
```

### 8.3 Tracepoint ID 索引

命中时：

```java
CompiledTracepoint tracepoint = plan.tracepointById().get(tracepointId);
```

复杂度：

```text
O(1)
```

### 8.4 按类分组

编译期生成：

```java
Map<String, List<CompiledTracepoint>> tracepointsByClass;
```

安装时：

```text
每个 className 调用一次 classesByName
每个未加载 className 创建一个 ClassPrepareRequest
类加载后一次性安装该类全部 tracepoint
```

### 8.5 BreakpointRequest 反向索引

建议不要只使用字符串 Property，再二次查 Map。

可以在 Request 上放不可变绑定：

```java
request.putProperty(
    TRACEPOINT_PROPERTY,
    new BreakpointBinding(tracepointId, locationIndex)
);
```

如果 JDI Property 序列化或类型边界存在兼容疑虑，则保留字符串 ID + Map。

### 8.6 验收标准

- 命中查找无 stream 遍历；
- 同一类只调用一次 `classesByName`；
- 同一未加载类只有一个 ClassPrepareRequest；
- 1000 个 tracepoint 编译时间可测量且无明显非线性增长；
- 原有 Plan 不修改也能编译。

---

## 9. P0-B：ClassPrepare 合并

### 9.1 当前问题

同一个未加载类有多个 tracepoint 时，当前可能创建多个：

```text
ClassPrepareRequest
```

这会增加：

- JDI Request 数量；
- EventQueue 事件处理；
- 重复断点安装风险；
- 清理复杂度。

### 9.2 设计

增加：

```java
final class ClassPrepareRegistry {
    private final Map<String, ClassPrepareRequest> requestByClass;
    private final Map<String, List<CompiledTracepoint>> pendingByClass;
}
```

安装规则：

```text
目标类已加载：
  -> 直接绑定该类全部 tracepoint

目标类未加载：
  -> computeIfAbsent 创建一个 request
  -> pendingByClass 保存该类全部 tracepoint
```

收到 `ClassPrepareEvent`：

```text
按 className O(1) 取 pending tracepoints
  -> 安装全部 breakpoint
  -> 删除/disable ClassPrepareRequest
  -> 记录 installedLocations
```

### 9.3 幂等性

需要防止：

- 同一个 class loader 多次加载同名类；
- ClassPrepareEvent 重复；
- 同一 Location 重复创建 BreakpointRequest。

绑定键建议：

```text
tracepointId
+ referenceType.classLoader.uniqueID
+ method signature
+ codeIndex
```

Bootstrap class loader 需要使用特殊 ID。

---

## 10. P0-C：JDI 批量字段读取

### 10.1 当前问题

当前普通对象字段读取近似：

```java
for (Field field : fields) {
    object.getValue(field);
}
```

每个 `getValue` 都可能导致一次 JDWP 通信。

### 10.2 设计

改为：

```java
List<Field> selected = selectFields(object.referenceType(), projection, budget);
Map<Field, Value> values = object.getValues(selected);
```

然后从本地 Map 递归构造快照。

### 10.3 Field Metadata 缓存

同一个类型会被反复采集。

增加 Session 级缓存：

```java
Map<ReferenceTypeKey, List<FieldDescriptor>> fieldsByType;
```

缓存内容：

- 字段名；
- declaring type；
- static/instance；
- synthetic；
- typeName；
- Field JDI Mirror。

缓存生命周期必须和当前 `VirtualMachine` Session 一致，VM 重连后清空。

### 10.4 字段选择顺序

建议：

1. Projection 明确要求的字段；
2. 当前类型本身声明的实例字段；
3. 父类字段；
4. 排除 synthetic 和静态缓存字段；
5. 达到 `maxItems` 后停止。

### 10.5 异常隔离

批量读取失败时：

```text
先记录 batch error
可选降级为逐字段读取
降级次数受限
不能无限重试
```

建议默认：

```text
batch 失败 -> 当前对象记录 $error -> 不逐字段降级
```

避免异常对象放大暂停时间。

---

## 11. P0-D：局部变量白名单

### 11.1 当前问题

当前 `locals=true` 表示捕获顶层栈帧所有可见局部变量。

真实调度方法可能包含：

- 完整输入对象；
- 所有候选；
- 资源 Map；
- 已生成操作列表；
- 大量临时集合；
- 缓存；
- 上下文对象。

大部分变量与某个具体问题无关。

### 11.2 Plan Schema

新增：

```json
{
  "capture": {
    "locals": true,
    "localVariables": [
      "context",
      "readyAt",
      "resourcesReadyAt",
      "requiredResources"
    ]
  }
}
```

兼容规则：

```text
locals=false：
  不采局部变量

locals=true 且 localVariables 缺失/空：
  保持旧行为，采集全部可见变量

locals=true 且 localVariables 非空：
  只采集指定变量
```

### 11.3 编译

Plan Compiler 将变量名编译成：

```java
Set<String> localVariableNames;
```

运行时：

```java
List<LocalVariable> selected = visibleVariables.stream()
    .filter(v -> names.contains(v.name()))
    .toList();

Map<LocalVariable, Value> values = frame.getValues(selected);
```

JDI `frame.getValues` 已经支持批量读取。

### 11.4 缺失变量

如果 Plan 指定变量不存在，不应终止：

```json
{
  "$missingLocals": [
    "resourcesReadyAt"
  ]
}
```

Manifest 增加缺失次数统计。

---

## 12. P0-E：字段路径投影

### 12.1 目标

把：

```text
拍摄整个 context 对象
```

变成：

```text
只读取回答问题所需的事实
```

### 12.2 Plan Schema

新增：

```json
{
  "capture": {
    "localVariables": [
      "context",
      "readyAt",
      "resourcesReadyAt"
    ],
    "paths": [
      "context.wafer.waferId",
      "context.job.jobId",
      "context.sequence.sequenceId",
      "readyAt",
      "resourcesReadyAt",
      "requiredResources[*]"
    ]
  }
}
```

### 12.3 第一版支持语法

P0 只支持安全的字段路径：

```text
local
local.field
local.field.nestedField
local[index]
local[*]
local.mapField
```

第一版不支持：

- 方法调用；
- 任意 Java 表达式；
- 条件表达式；
- Stream；
- Getter；
- 正则字段匹配；
- 任意 Map key 表达式。

### 12.4 编译模型

```java
public record ProjectionPath(
    String rootLocal,
    List<PathSegment> segments
) {
}

sealed interface PathSegment {
    record FieldSegment(String name) implements PathSegment {}
    record ArrayIndexSegment(int index) implements PathSegment {}
    record ArrayWildcardSegment() implements PathSegment {}
}
```

编译期完成：

- 语法解析；
- 重复路径合并；
- 公共前缀构建 Projection Tree；
- 深度校验；
- wildcard 数量限制。

### 12.5 Projection Tree

以下路径：

```text
context.wafer.waferId
context.wafer.currentLocation
context.job.jobId
```

编译为：

```text
context
├── wafer
│   ├── waferId
│   └── currentLocation
└── job
    └── jobId
```

这样 `context.wafer` 只读取一次。

### 12.6 输出

```json
{
  "projection": {
    "context.wafer.waferId": "W1",
    "context.wafer.currentLocation": "LP1",
    "context.job.jobId": "JOB-1",
    "readyAt": "7",
    "resourcesReadyAt": "9"
  }
}
```

建议同时保留结构化树和扁平路径中的一种，不要两份都输出。

P0 推荐扁平路径，便于：

- JSONL 体积控制；
- Normalizer 查找；
- 大模型引用；
- Schema 稳定。

---

## 13. P0-F：命中采样

### 13.1 目标

对于高频循环：

```text
不是每次命中都需要执行完整快照。
```

采样判断必须发生在任何昂贵 JDI 快照之前。

### 13.2 Plan Schema

```json
{
  "sampling": {
    "firstN": 100,
    "everyNthHit": 100,
    "maxCapturedHits": 1000,
    "hitRange": {
      "from": 1,
      "to": 100000
    }
  }
}
```

### 13.3 语义

```text
rawHitCount：
  断点真实命中次数

capturedHitCount：
  实际生成快照次数

firstN：
  前 N 次全部采集

everyNthHit：
  firstN 之后，每 N 次采集一次

maxCapturedHits：
  最多输出多少次快照

hitRange：
  只考虑某个命中区间
```

### 13.4 判断顺序

```text
rawHitCount++

如果不在 hitRange：
  skip

如果 rawHitCount <= firstN：
  capture

否则如果 everyNthHit > 0 且满足取模：
  capture

否则：
  skip

如果 capturedHitCount 达到 maxCapturedHits：
  disable request 或只计数不采集
```

### 13.5 并发

同一个 tracepoint 可能由多个线程命中。

计数器必须使用：

```java
LongAdder
```

或者：

```java
AtomicLong
```

需要明确采样是：

- 全局按 tracepoint；
- 还是按 tracepoint + thread。

P0 默认全局按 tracepoint，保证可预测输出量。

---

## 14. P0-G：目标线程暂停区间最小化

### 14.1 当前流程

```text
EventSet 到达
  -> 目标线程暂停
  -> 读取 JDI 数据
  -> 构造 Map
  -> JSON 序列化
  -> 写文件
  -> flush
  -> eventSet.resume()
```

问题：

- JSON 序列化占用暂停时间；
- 磁盘写入占用暂停时间；
- flush 占用暂停时间；
- 慢磁盘会直接拖慢算法线程。

### 14.2 目标流程

```text
EventSet 到达
  -> 采样判断
  -> 读取必要 JDI 数据
  -> 转换为不持有 JDI Mirror 的 ImmutableSnapshot
  -> offer 到有界队列
  -> eventSet.resume()

Writer Thread
  -> take Snapshot
  -> JSON 序列化
  -> BufferedWriter 写入
  -> 按策略 flush
```

### 14.3 为什么 Snapshot 不能保留 JDI 对象

线程恢复后：

- StackFrame 可能失效；
- LocalVariable Value 可能变化；
- ObjectReference 可能被 GC；
- JDI Mirror 访问可能再次发生远程调用。

队列中的对象必须只包含：

- String；
- Number；
- Boolean；
- null；
- 不可变 Map/List；
- Collector 自己的 Record。

不能包含：

- StackFrame；
- ThreadReference；
- ObjectReference；
- ReferenceType；
- Field；
- Location。

### 14.4 暂停时间指标

每次命中记录：

```text
eventReceivedAtNanos
snapshotStartedAtNanos
snapshotCompletedAtNanos
resumedAtNanos
```

聚合：

```text
snapshot count
total snapshot nanos
max snapshot nanos
p50/p95/p99 snapshot nanos
total suspended nanos
max suspended nanos
```

P0 可以先用固定直方图区间，避免引入大型指标依赖。

---

## 15. P0-H：有界异步 Writer

### 15.1 Queue 设计

```java
BlockingQueue<QueuedSnapshot>
```

但仅限制事件数量不够，因为单事件大小差异很大。

需要同时限制：

```text
maxQueueEvents
maxQueueEstimatedBytes
```

`QueuedSnapshot`：

```java
public record QueuedSnapshot(
    RawTraceEvent event,
    long estimatedBytes
) {
}
```

### 15.2 默认参数

建议：

```json
{
  "writer": {
    "queueMaxEvents": 1000,
    "queueMaxBytes": 67108864,
    "flushEveryEvents": 100,
    "flushEveryMillis": 500,
    "flushEveryBytes": 1048576
  }
}
```

即：

```text
最多 1000 个排队事件
最多约 64 MB 排队快照
每 100 个事件 / 500ms / 1MB 任一满足就 flush
```

### 15.3 Backpressure Policy

支持：

```text
BLOCK
DROP_NEWEST
STOP_COLLECTION
```

P0 默认推荐：

```text
STOP_COLLECTION
```

原因：

- `BLOCK` 会重新把磁盘压力传导到目标线程；
- `DROP_NEWEST` 会产生不完整证据；
- `STOP_COLLECTION` 可以明确记录证据不完整，并安全停止。

达到队列限制：

```text
记录 collection_limit_reached
停止创建新快照
禁用 Collector 创建的 requests
恢复所有 EventSet
Writer 排空队列
写 Manifest
退出
```

### 15.4 Writer 生命周期

```text
NEW
  -> RUNNING
  -> DRAINING
  -> CLOSED

异常：
RUNNING
  -> FAILED
  -> DRAINING/ABORTED
```

Writer 失败必须通知 Event Loop，不能让 Event Loop 继续无限采集。

---

## 16. P0-I：批量 Flush

### 16.1 FlushPolicy

```java
public record FlushPolicy(
    int everyEvents,
    long everyBytes,
    Duration everyDuration
) {
}
```

满足任一条件：

```text
eventsSinceFlush >= everyEvents
bytesSinceFlush >= everyBytes
now - lastFlushAt >= everyDuration
```

执行 flush。

### 16.2 强制 Flush

以下场景必须强制 flush：

- VMDeath；
- VMDisconnect；
- idle timeout；
- max events；
- max bytes；
- 用户中断；
- JVM shutdown hook；
- Writer 正常关闭。

### 16.3 数据安全

批量 flush 意味着异常断电时可能丢失最后一个 flush 窗口的数据。

因此 Manifest 应记录：

```text
lastSuccessfulFlushSequence
lastSuccessfulFlushAt
```

---

## 17. P0-J：采集硬限制

### 17.1 Plan Schema

```json
{
  "limits": {
    "maxEvents": 100000,
    "maxCapturedHits": 50000,
    "maxTraceBytes": 536870912,
    "maxDurationMillis": 600000,
    "idleTimeoutMillis": 120000,
    "maxQueueEvents": 1000,
    "maxQueueBytes": 67108864,
    "maxObjectNodesPerEvent": 1000,
    "maxJdiReadsPerEvent": 2000
  }
}
```

### 17.2 限制语义

| 限制 | 作用 |
|---|---|
| `maxEvents` | 生命周期事件和 trace 事件总数 |
| `maxCapturedHits` | 所有 tracepoint 实际快照总数 |
| `maxTraceBytes` | JSONL 最大字节数 |
| `maxDurationMillis` | 整个 Session 最大持续时间 |
| `idleTimeoutMillis` | 无事件最大等待时间 |
| `maxQueueEvents` | Writer 队列最大事件数 |
| `maxQueueBytes` | Writer 队列估算最大字节数 |
| `maxObjectNodesPerEvent` | 单事件最多访问对象节点数 |
| `maxJdiReadsPerEvent` | 单事件最多执行 JDI 读取次数 |

### 17.3 SnapshotBudget

```java
final class SnapshotBudget {
    private int remainingNodes;
    private int remainingJdiReads;
    private int remainingItems;
}
```

所有递归读取都必须消耗 Budget。

Budget 耗尽时输出：

```json
{
  "$truncated": "maxObjectNodesPerEvent",
  "$visitedNodes": 1000
}
```

### 17.4 停止原因

统一枚举：

```text
VM_DEATH
VM_DISCONNECT
IDLE_TIMEOUT
MAX_EVENTS
MAX_CAPTURED_HITS
MAX_TRACE_BYTES
MAX_DURATION
QUEUE_LIMIT
WRITER_FAILURE
INTERRUPTED
COLLECTOR_ERROR
```

---

## 18. P0-K：性能指标与 Manifest

### 18.1 新增总体指标

```json
{
  "metrics": {
    "rawHits": 100000,
    "capturedHits": 1100,
    "sampledOutHits": 98900,
    "eventsWritten": 1102,
    "traceBytes": 12582912,
    "maxQueueEvents": 230,
    "maxQueueBytes": 8388608,
    "totalSnapshotMillis": 4120,
    "maxSnapshotMillis": 18,
    "totalSuspendedMillis": 4350,
    "maxSuspendedMillis": 21,
    "totalWriteMillis": 980,
    "flushCount": 14,
    "missingLocalCount": 8,
    "truncatedSnapshotCount": 13
  }
}
```

### 18.2 Tracepoint 级指标

```json
{
  "tracepointMetrics": {
    "candidate-selected": {
      "installedLocations": 1,
      "rawHits": 50000,
      "capturedHits": 600,
      "sampledOutHits": 49400,
      "bytesWritten": 6291456,
      "avgSnapshotMicros": 3200,
      "maxSnapshotMicros": 15000,
      "missingLocals": {
        "candidate": 2
      }
    }
  }
}
```

### 18.3 指标实现

热路径使用：

- `LongAdder`；
- `AtomicLong`；
- 固定桶 histogram。

不要在每次命中创建复杂 Metric 对象。

---

## 19. Debug Plan Schema 兼容设计

### 19.1 旧计划

旧计划：

```json
{
  "maxEvents": 10000,
  "idleTimeoutMillis": 120000,
  "tracepoints": [
    {
      "maxHits": 500,
      "capture": {
        "locals": true,
        "stack": true,
        "maxDepth": 2,
        "maxItems": 20
      }
    }
  ]
}
```

必须继续工作。

### 19.2 新计划

```json
{
  "schemaVersion": "1.1",
  "sessionId": "wafer-order-investigation",
  "target": {
    "host": "127.0.0.1",
    "port": 5005
  },
  "limits": {
    "maxEvents": 100000,
    "maxTraceBytes": 536870912,
    "maxDurationMillis": 600000,
    "idleTimeoutMillis": 120000,
    "maxQueueEvents": 1000,
    "maxQueueBytes": 67108864,
    "maxObjectNodesPerEvent": 1000,
    "maxJdiReadsPerEvent": 2000
  },
  "writer": {
    "flushEveryEvents": 100,
    "flushEveryMillis": 500,
    "flushEveryBytes": 1048576,
    "backpressurePolicy": "STOP_COLLECTION"
  },
  "tracepoints": [
    {
      "id": "operation-start-calculated",
      "className": "org.example.scheduler.wafer.SimpleWaferScheduler",
      "methodName": "scheduleWafer",
      "line": 120,
      "sampling": {
        "firstN": 100,
        "everyNthHit": 50,
        "maxCapturedHits": 1000
      },
      "capture": {
        "stack": true,
        "maxFrames": 5,
        "locals": true,
        "localVariables": [
          "context",
          "readyAt",
          "resourcesReadyAt",
          "requiredResources"
        ],
        "paths": [
          "context.wafer.waferId",
          "context.job.jobId",
          "readyAt",
          "resourcesReadyAt",
          "requiredResources[*]"
        ],
        "maxDepth": 1,
        "maxItems": 10,
        "maxStringLength": 1000
      }
    }
  ]
}
```

### 19.3 默认值

为了兼容旧行为：

```text
sampling.firstN = unlimited
sampling.everyNthHit = 1
sampling.maxCapturedHits = tracepoint.maxHits

capture.localVariables 缺失
  -> locals=true 时读取全部

capture.paths 缺失
  -> 使用传统有界对象快照
```

但新生成的计划应默认采用更保守策略。

---

## 20. Plan Cost Estimator

### 20.1 目标

Collector 执行前对计划做静态风险判断。

输入：

- tracepoint 数量；
- class 数量；
- maxHits；
- sampling；
- locals 数量；
- paths 数量；
- maxDepth；
- maxItems；
- maxFrames。

输出：

```json
{
  "risk": "HIGH",
  "reasons": [
    "estimatedCapturedHits exceeds 50000",
    "12 tracepoints use full locals with maxDepth=2",
    "estimated output exceeds 512MB"
  ],
  "estimated": {
    "tracepoints": 320,
    "classes": 48,
    "maxRawHits": 1000000,
    "maxCapturedHits": 120000,
    "maxObjectNodes": 24000000,
    "outputBytes": 1600000000
  }
}
```

### 20.2 风险等级

```text
LOW：
  可直接执行

MEDIUM：
  输出警告，允许执行

HIGH：
  默认拒绝，必须 --allow-high-cost
```

### 20.3 估算局限

静态计划不知道真实命中次数和对象结构，因此估算应：

- 偏保守；
- 明确是上限/估算；
- 不作为唯一安全机制；
- 与运行时硬限制共同使用。

---

## 21. Collection Preview

### 21.1 目标

在执行完整计划前，小规模试采：

```text
每个 tracepoint 最多采 1～3 次
运行到 preview 时间上限
估算平均事件大小和暂停时间
```

### 21.2 CLI

```powershell
java --add-modules jdk.jdi `
  -jar jdwp-batch-collector.jar preview `
  --plan debug-plan.json `
  --output output/preview
```

### 21.3 输出

```json
{
  "tracepointId": "candidate-selected",
  "previewHits": 3,
  "avgEventBytes": 14200,
  "avgSuspendedMillis": 12.5,
  "projectedBytesAtMaxHits": 1420000000,
  "recommendation": "reduce maxHits or add projection paths"
}
```

Preview 属于 P0 后半段。如果开发周期有限，先完成运行时硬限制。

---

## 22. 并发与线程安全设计

### 22.1 线程模型

建议：

```text
Main Thread
  -> 启动组件
  -> 等待 Event Loop 完成

JDI Event Loop Thread
  -> 独占消费 vm.eventQueue()
  -> 快照
  -> enqueue
  -> resume

Writer Thread
  -> dequeue
  -> serialize
  -> write/flush

Shutdown Hook
  -> 发出停止信号
  -> 不直接并发访问 JDI EventQueue
```

### 22.2 单一 EventQueue Consumer

同一个 Collector 内只能有一个线程消费：

```java
vm.eventQueue()
```

避免事件顺序和 EventSet resume 冲突。

### 22.3 Resume 保证

所有 EventSet 处理必须使用：

```java
try {
    process(eventSet);
} finally {
    eventSet.resume();
}
```

即使以下情况发生也必须恢复：

- Projection 失败；
- 对象已被 GC；
- 队列已满；
- Writer 已失败；
- JSON Snapshot 构造异常；
- 达到全局限制。

### 22.4 停止协调

```java
AtomicReference<CollectionStopReason> stopReason;
```

第一个终止原因胜出：

```java
stopReason.compareAndSet(null, reason);
```

其他线程读取后进入收尾阶段。

---

## 23. 错误处理与安全退化

### 23.1 单事件失败

单个变量或对象读取失败：

```text
记录 $error
继续当前事件
恢复线程
继续 Session
```

### 23.2 Tracepoint 绑定失败

```text
记录 binding error
其他 tracepoint 继续
Manifest 标记 partialBinding=true
```

如果所有 tracepoint 都未绑定：

```text
默认停止并返回 NO_TRACEPOINT_BOUND
```

### 23.3 Writer 失败

```text
设置 WRITER_FAILURE
停止采集新事件
禁用 Collector requests
恢复目标线程
尝试写 fallback manifest
返回非零退出码
```

### 23.4 队列达到上限

默认：

```text
STOP_COLLECTION
```

不静默丢弃证据。

### 23.5 Manifest 写失败

CLI 必须：

- stderr 输出完整原因；
- 非零退出；
- 不影响已经写入的 JSONL；
- 尝试生成最小 `.failure` 文本文件。

---

## 24. 开发分阶段

### Phase P0-A：无 Schema 破坏的低风险优化

内容：

1. `tracepointById`；
2. `tracepointsByClass`；
3. 每类一个 ClassPrepareRequest；
4. 批量 `object.getValues(fields)`；
5. 字段元数据 Session 缓存；
6. Writer 批量 flush；
7. 基础性能指标。

特点：

- 不要求用户修改 Plan；
- 改动可独立测试；
- 快速降低明显热点。

### Phase P0-B：Plan Schema 增强

内容：

1. `localVariables`；
2. `paths`；
3. `sampling`；
4. `limits`；
5. `writer`；
6. schemaVersion；
7. 兼容默认值。

### Phase P0-C：异步 Writer

内容：

1. 不可变 Snapshot Model；
2. 有界双限制队列；
3. Writer Thread；
4. Backpressure；
5. Drain/Shutdown；
6. Writer metrics。

### Phase P0-D：性能验证

内容：

1. synthetic target；
2. 100/500/1000 tracepoint；
3. 1k/10k/100k hits；
4. location/stack/projected/full snapshot；
5. 小/中/大对象；
6. Windows/Linux；
7. JDK 17/JDK 21。

---

## 25. 详细开发任务

### Task 1：CompiledTracePlan

- 新建 Record/immutable model；
- 编译旧 Plan；
- ID 重复校验；
- 按类分组；
- 单元测试 1000 tracepoints；
- 替换事件命中线性查找。

### Task 2：BreakpointInstaller

- 从 Executor 提取安装职责；
- 已加载类批量安装；
- 未加载类合并 request；
- 多 classloader 幂等绑定；
- 记录绑定耗时与位置数。

### Task 3：Batch Field Reader

- 使用 `getValues`；
- 字段选择；
- 类型字段缓存；
- Budget；
- collected/error 测试；
- 与旧 Snapshot JSON 对比。

### Task 4：Local Allowlist

- 扩展 Plan；
- 编译 Set；
- 缺失变量记录；
- 空白名单兼容旧行为；
- AbsentInformationException 测试。

### Task 5：Projection Parser

- 定义语法；
- parser；
- projection tree；
- 字段读取；
- 数组索引/wildcard；
- 非法路径校验；
- 最大路径数量限制。

### Task 6：HitSampler

- firstN；
- everyNthHit；
- maxCapturedHits；
- hitRange；
- 多线程计数；
- raw/captured/skipped 指标。

### Task 7：Immutable Snapshot

- 去除队列中的 JDI Mirror；
- typed event record；
- 大小估算；
- JSON 兼容测试；
- 序列化基准。

### Task 8：Async Writer

- bounded queue；
- byte reservation；
- writer lifecycle；
- flush policy；
- failure propagation；
- drain；
- shutdown hook。

### Task 9：Collection Limits

- duration；
- bytes；
- events；
- queue；
- nodes；
- JDI reads；
- 统一 stop reason；
- LimitReachedEvent。

### Task 10：Manifest Metrics

- 总体指标；
- tracepoint 指标；
- percentiles/histogram；
- incomplete evidence 标志；
- writer/queue 指标。

### Task 11：Benchmark Suite

- 创建专用 benchmark target；
- 生成多规模计划；
- 自动运行和比较；
- 输出 Markdown/JSON 报告；
- 回归阈值。

---

## 26. 测试设计

### 26.1 单元测试

#### Plan Compiler

- 空计划；
- 重复 ID；
- 同类多点分组；
- 1000 点索引；
- 非法 sampling；
- 非法 projection；
- 旧 Schema 兼容。

#### Sampler

- firstN；
- everyNthHit；
- hitRange；
- maxCapturedHits；
- 并发命中；
- 边界值。

#### Projection

- 简单字段；
- 嵌套字段；
- 公共前缀；
- 数组 index；
- wildcard；
- null 中间节点；
- 缺失字段；
- 循环对象；
- Budget 截断。

#### Writer

- 一事件一行；
- 批量 flush；
- 定时 flush；
- byte flush；
- queue full；
- Writer exception；
- drain；
- close 幂等。

### 26.2 JDI 集成测试

启动专用 JVM：

```text
suspend=y
loaded class
delayed loaded class
multiple classloaders
single-thread hits
multi-thread hits
large object
array/list/map
VMDeath
VMDisconnect
```

验证：

- 断点只安装一次；
- EventSet 一定恢复；
- Sampling 正确；
- Projection 正确；
- Manifest 正确；
- 达到限制后正常退出。

### 26.3 性能测试

矩阵：

| 维度 | 值 |
|---|---|
| Tracepoints | 10、100、500、1000 |
| Hits | 1000、10000、100000 |
| Capture | location、stack、projected、full |
| Depth | 0、1、2 |
| Items | 5、10、20 |
| Threads | 1、4、16 |

指标：

- Plan compile time；
- breakpoint install time；
- avg/p95/p99 suspended time；
- events/sec；
- bytes/sec；
- max queue；
- Collector heap；
- target JVM wall time；
- dropped/stopped events。

---

## 27. 验收标准

### 27.1 正确性

- 旧 Plan 全部可运行；
- 同一 UT 的调度结果语义不变；
- JSONL 每事件一行；
- Manifest 与 JSONL 数量一致；
- Sampling 计数正确；
- Projection 输出正确；
- 所有 EventSet 均恢复；
- 限制触发后目标 JVM 不遗留暂停线程。

### 27.2 结构性能

| 指标 | 目标 |
|---|---:|
| Tracepoint ID 查找 | O(1) |
| 同类 ClassPrepareRequest | 1 |
| 同类 `classesByName` | 1 |
| 普通对象字段读取 | 单对象单批次为主 |
| JSON 序列化 | 不位于目标线程暂停区间 |
| 磁盘写入 | 不位于目标线程暂停区间 |

### 27.3 建议基准目标

以下是工程目标，不是当前已达到的结果：

| 指标 | 建议目标 |
|---|---:|
| 500 tracepoint Plan 编译 | < 100ms |
| 500 tracepoint 安装 | < 3s，取决于 JVM/class 状态 |
| location-only 单次增量 | < 1ms |
| stack-only 单次增量 | < 2ms |
| 10 个简单投影字段 | < 5ms/次 |
| Writer queue | 有硬上限且不 OOM |
| 100000 location events | 完成且 Manifest 完整 |
| 达到 maxTraceBytes | 安全停止 |
| Writer 故障 | 目标线程恢复、非零退出 |

### 27.4 内存

- Collector Heap 不随已写事件总量线性增长；
- 内存主要由 Queue 上限决定；
- 超过 Queue Byte Limit 时停止或按策略处理；
- 不长期持有 ObjectReference。

---

## 28. 风险与应对

### 风险 1：异步 Writer 改变事件顺序

应对：

- 单 Event Loop 按序 enqueue；
- 单 Writer 线程按 FIFO 写；
- 每个事件携带单调 `sequence`；
- 不使用多 Writer 并行序列化。

### 风险 2：快照大小估算不准确

应对：

- Queue 使用保守估算；
- Writer 记录实际字节；
- 同时有 `maxQueueEvents` 和 `maxTraceBytes`；
- 估算不是唯一限制。

### 风险 3：Projection 读取集合内部实现

应对：

- P0 只支持数组和普通字段；
- List/Map 领域投影放到后续或专用 Adapter；
- 禁止调用目标方法；
- 不依赖 JDK 私有字段布局作为核心契约。

### 风险 4：Sampling 遗漏关键事件

应对：

- Manifest 明确 `sampledOutHits`；
- 报告不得把采样 Trace 当成完整事件序列；
- 关键 commit 点可以不采样；
- 候选生成等高频点使用采样。

### 风险 5：Writer 队列满

应对：

- 默认停止而不是静默丢弃；
- 输出 `QUEUE_LIMIT`；
- Manifest 标记证据不完整；
- Agent 重新生成低成本计划。

### 风险 6：MCP Core 兼容回归

应对：

- Core 新 API 优先新增而非破坏；
- MCP 定向回归测试；
- Collector 专用逻辑不强行放入 MCP；
- 分 Phase 合并。

---

## 29. 推荐实际使用策略

即使完成 P0，也不建议一次全量记录大型算法所有状态。

在P0完成前，Agent适配器必须采用更保守的兼容策略：tracepoint数量少、`maxHits`低、`maxDepth`最多1～2、`maxItems`小，并优先选择决策完成位置；如果预计输出超过预算，应拒绝执行或拆成多轮，不能假设当前同步Writer和全locals快照可以支撑大型计划。

### Run 1：路径级低成本采集

```text
5～50 个 tracepoint
locals=false
stack=true
maxFrames=5
sampling enabled
```

目标：确定实际执行阶段和疑点位置。

### Run 2：投影字段采集

```text
1～10 个关键 tracepoint
localVariables allowlist
paths projection
maxDepth=0/1
```

目标：获取回答当前问题所需的状态。

### Run 3：少量深快照

```text
1～3 个 tracepoint
maxCapturedHits=10～100
maxDepth=2
maxItems=5～10
```

目标：理解某个复杂策略对象。

### Run 4：JDWP-MCP

证据仍不足时，通过 MCP 做交互式集中调查。

---

## 30. Definition of Done

P0 性能加固完成必须同时满足：

- [ ] CompiledTracePlan 已实现；
- [ ] ID 查找 O(1)；
- [ ] 按 className 分组；
- [ ] ClassPrepare 合并；
- [ ] 对象字段批量读取；
- [ ] Local allowlist；
- [ ] Projection paths；
- [ ] Sampling；
- [ ] Immutable Snapshot；
- [ ] Bounded Async Writer；
- [ ] Batch/Timed Flush；
- [ ] Queue/Event/Byte/Duration/Object Node 硬限制；
- [ ] Manifest 性能指标；
- [ ] Writer Failure 安全处理；
- [ ] 旧 Plan 兼容；
- [ ] MCP 定向回归通过；
- [ ] 真实晶圆 Demo 回归通过；
- [ ] 100k location event 压力测试完成；
- [ ] 性能报告归档。

---

## 31. 最终设计结论

本次 P0 加固不改变现有正确架构：

```text
jdwp-core
├── jdwp-mcp-server
└── jdwp-batch-collector
```

也不重新发明 JDWP 或重写 MCP Server。

核心变化是把 Collector 从：

```text
逐点查找
逐字段读取
完整对象展开
同步序列化
逐事件 flush
有限的运行时保护
```

提升为：

```text
编译后计划索引
按类批量安装
批量 JDI 读取
问题导向字段投影
命中采样
最小化线程暂停
有界异步写盘
完整硬限制
可度量、可停止、可解释的采集过程
```

对于大型晶圆调度算法，最终的正确策略不是“把所有对象全部采下来”，而是：

```text
大模型/静态分析制定问题相关计划
  -> Collector 预估成本
  -> 低成本路径采集
  -> 投影关键业务字段
  -> 必要时少量深快照
  -> MCP 最终聚焦调查
```

P0 加固完成后，Collector 才适合作为真实 Algorithm Debug Agent 的稳定运行时事实采集底座。
