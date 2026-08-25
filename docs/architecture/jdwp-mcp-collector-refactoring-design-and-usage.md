> HISTORICAL DOCUMENT: This records the original external mcp-jdwp-java refactoring. The current Agent-owned implementation and build instructions are in docs/designs/2026-08-23-agent-owned-jdwp-collector-design.md.

# JDWP-MCP 与 Batch Collector 重构设计、变更说明及使用手册

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档定位 | JDWP-MCP 重构设计说明、实际变更清单和 Collector 使用手册 |
| JDWP 工具仓库 | `D:\mcpcode\mcp-jdwp-java` |
| 算法 Demo 仓库 | `D:\javacode\hellomvn` |
| 目标场景 | Java/JUnit 离线算法问题复现、运行时事实采集和证据分析 |
| 当前状态 | 第一阶段重构与 Batch Collector MVP 已完成，并通过真实晶圆调度 UT 验证 |
| 核心原则 | 算法源码零侵入、采集与分析解耦、同一 UT 可重复采集 |
| 最近复验 | 2026-08-10，工具仓库commit `1ef7d22` |

本文档专门总结已经实施的 JDWP-MCP 重构。完整 Algorithm Debug Agent 的总体设计参见：

- `docs/architecture/algorithm-debug-agent-complete-design.md`
- `docs/architecture/tool-validation-baseline.md`

本次重构解决的是 Agent 架构中的“无侵入运行时事实采集层”，尚不包含完整的
Trace Normalizer、Domain Trace、Evidence Graph 和最终问题解释 Agent。

---

## 2. 为什么需要重构 JDWP-MCP

### 2.1 原始能力适合交互式 Debug

原始 `mcp-jdwp-java` 已经具备比较完整的 JDI/JDWP 调试能力，例如：

- 连接 JDWP 目标 JVM；
- 设置、清除断点；
- 等待和读取断点事件；
- 查看线程和调用栈；
- 读取局部变量；
- 读取对象字段；
- 表达式求值；
- 暂停和恢复线程；
- 处理 ClassPrepare、VMDeath 等事件。

这些能力通过 MCP Tool 暴露后，很适合下面的调查方式：

```text
大模型设置一个断点
  -> 运行到断点
  -> 大模型读取变量
  -> 大模型决定下一步
  -> 再设置断点或恢复
```

这种方式非常适合“已经知道疑点在哪里”的集中调查。

### 2.2 算法执行过程采集需要批处理模式

晶圆调度问题的目标不是偶尔查看一次局部变量，而是：

1. 读取确定的算法输入；
2. 重复运行确定的调度 UT；
3. 在几十或几百个关键决策点采集运行时事实；
4. 把所有事实保存成文件；
5. 在 UT 结束后统一规范化和分析；
6. 证据不足时，再使用 MCP 进行集中调查。

如果仍然让大模型逐个处理断点事件，会出现以下问题：

- 每次事件都需要一次 MCP 往返；
- 大模型会参与采集时序，运行时间不可预测；
- 长调度过程容易丢事件；
- 会话中间状态不适合作为可重复证据；
- 原始数据难以统一保存和复用；
- 调试采集和业务分析耦合在同一轮对话中。

因此需要独立的 Batch Collector：

```text
大模型只生成声明式采集计划
  -> Collector 确定性执行计划
  -> Collector 写入 Raw Trace
  -> 大模型在采集完成后分析证据
```

### 2.3 不在调度算法内部增加 Domain Trace

本方案不要求算法源代码增加：

```java
trace.emit(...);
domainTrace.record(...);
debugSink.accept(...);
```

原因包括：

- 真实算法代码可能不允许修改；
- 埋点可能影响性能和执行时序；
- 埋点设计错误时仍会遗漏关键事实；
- 调度业务代码会依赖调试基础设施；
- 每次分析新问题都可能需要重新修改算法。

Collector 运行在目标 UT JVM 外部，其定位类似“可编程的自动 IntelliJ Debugger”。

---

## 3. 重构前后的总体对比

| 对比项 | 重构前 | 重构后 |
|---|---|---|
| Maven 模块 | `jdwp-mcp-server`、`jdwp-sandbox` | 新增 `jdwp-core`、`jdwp-batch-collector` |
| JDWP 连接实现 | 位于 MCP Server 的 `JDIConnectionService` | 抽取为无框架依赖的 `JdiSocketAttacher` |
| 主要入口 | MCP Tool | MCP Tool + 独立 Collector CLI |
| 运行模式 | 大模型交互式调试 | 批量自动采集 + 交互式深挖 |
| 采集计划 | MCP 调用参数和会话状态 | 独立 `debug-plan.json` |
| 事件处理 | MCP 事件缓冲和工具调用 | Collector 持续消费 JDI EventQueue |
| 类未加载处理 | MCP 调试生命周期处理 | Collector 使用 `ClassPrepareRequest` 延迟绑定 |
| 数据输出 | MCP 文本返回 | 严格 JSONL + Manifest |
| 大对象控制 | 依赖具体 MCP 工具 | Core 统一深度、元素数和字符串长度限制 |
| 调度业务依赖 | 无 | 无 |
| 是否修改算法源码 | 不需要 | 不需要 |
| 可重复性 | 依赖对话中的操作顺序 | 同一 UT + 同一计划可重复执行 |
| 典型用途 | 查看一个疑点 | 采集完整过程、保存证据、离线分析 |

---

## 4. 重构设计原则

### 4.1 Core 不依赖 Spring 和 MCP

底层 JDI/JDWP 能力不能只能在 Spring Boot MCP Server 中使用。

`jdwp-core` 只依赖：

- Java 标准库；
- JDK 的 `jdk.jdi` 模块。

它不依赖：

- Spring Boot；
- Spring AI；
- MCP Annotation；
- 晶圆调度模型；
- JUnit；
- Agent Framework。

### 4.2 MCP 与 Collector 是两种 Adapter

两者不是互相替代关系：

```text
jdwp-core
├── jdwp-mcp-server
│   └── 面向大模型的交互式调试 Adapter
└── jdwp-batch-collector
    └── 面向文件和自动流水线的批量采集 Adapter
```

### 4.3 Raw Trace 不直接包含强制业务语义

JDWP Collector 负责采集确定性事实：

- 命中了哪个代码位置；
- 当前线程是什么；
- 当前调用栈是什么；
- 当前局部变量是什么；
- 对象的指定范围内有哪些字段。

Collector 不直接判断：

- 某个对象是不是调度候选；
- 某次过滤是不是工艺约束；
- 哪个 wafer 应该先进入 chamber；
- 当前甘特图是否异常。

这些语义由后续 Trace Normalizer、Validator 和 Agent 负责。

### 4.4 采集必须有硬限制

调度算法通常包含较大对象图，例如：

- 多个 Job；
- 每个 Job 的多片 Wafer；
- 多套 Sequence；
- 多个 Chamber；
- 候选集合；
- 资源状态时间线；
- 排序和评分明细。

因此所有快照必须限制：

- 对象展开深度；
- 单层字段或元素数量；
- 字符串长度；
- 单采集点命中次数；
- 整个 Session 的事件数量；
- 无事件等待时间。

### 4.5 Collector 不调用目标对象方法

对象快照通过 JDI 读取字段，不调用目标 JVM 中的业务方法。

禁止随意执行类似：

```java
candidate.getScore();
resource.toString();
scheduler.recalculate();
```

因为方法调用可能：

- 修改算法状态；
- 获取锁；
- 抛出异常；
- 触发延迟加载；
- 改变调度结果；
- 导致目标线程死锁。

---

## 5. 重构后的模块结构

```text
mcp-jdwp-java/
├── pom.xml
├── jdwp-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/one/edee/mcp/jdwp/core/
│       │   ├── JdwpEndpoint.java
│       │   ├── JdiSocketAttacher.java
│       │   ├── SnapshotLimits.java
│       │   ├── JdiValueSnapshotter.java
│       │   └── FrameSnapshotter.java
│       └── test/java/one/edee/mcp/jdwp/core/
│
├── jdwp-batch-collector/
│   ├── pom.xml
│   └── src/
│       ├── main/java/one/edee/mcp/jdwp/collector/
│       │   ├── CollectorMain.java
│       │   ├── DebugPlan.java
│       │   ├── TracePlanExecutor.java
│       │   └── JsonlTraceWriter.java
│       └── test/java/one/edee/mcp/jdwp/collector/
│
├── jdwp-mcp-server/
│   ├── pom.xml
│   └── src/main/java/one/edee/mcp/jdwp/
│       ├── JDIConnectionService.java
│       ├── JdiEventListener.java
│       ├── BreakpointTracker.java
│       ├── JDWPTools.java
│       └── ...
│
├── jdwp-sandbox/
├── examples/
│   └── wafer-scheduler-debug-plan.json
└── docs/
    ├── batch-collector.md
    └── jdwp-collector-refactoring.md
```

---

## 6. `jdwp-core` 设计

### 6.1 `JdwpEndpoint`

文件：

```text
jdwp-core/src/main/java/one/edee/mcp/jdwp/core/JdwpEndpoint.java
```

职责：

- 表示目标 JDWP 地址；
- 去除 host 两侧空格；
- 禁止空 host；
- 校验端口范围 `1..65535`。

它把地址验证从 MCP 和 Collector 中移出，避免两个 Adapter 产生不同规则。

### 6.2 `JdiSocketAttacher`

文件：

```text
jdwp-core/src/main/java/one/edee/mcp/jdwp/core/JdiSocketAttacher.java
```

核心流程：

```text
Bootstrap.virtualMachineManager()
  -> 查找 com.sun.jdi.SocketAttach
  -> 设置 hostname
  -> 设置 port
  -> connector.attach(arguments)
  -> VirtualMachine
```

原来这段逻辑直接写在 `JDIConnectionService` 中。重构后：

```java
vm = new JdiSocketAttacher().attach(new JdwpEndpoint(host, port));
```

这说明 Core 已被现有 MCP Server 实际复用，而不是复制了一套未使用的代码。

### 6.3 `SnapshotLimits`

文件：

```text
jdwp-core/src/main/java/one/edee/mcp/jdwp/core/SnapshotLimits.java
```

字段：

```text
maxDepth
maxItems
maxStringLength
```

默认值：

```text
maxDepth = 2
maxItems = 20
maxStringLength = 2000
```

同时存在安全上限，避免错误计划要求无限展开。

### 6.4 `JdiValueSnapshotter`

文件：

```text
jdwp-core/src/main/java/one/edee/mcp/jdwp/core/JdiValueSnapshotter.java
```

负责将 JDI Value 转换为 JSON 友好结构。

支持：

- `null`；
- `PrimitiveValue`；
- `StringReference`；
- `ArrayReference`；
- 普通 `ObjectReference`；
- 循环引用；
- 已被 GC 的对象；
- 读取对象时的运行时异常。

普通对象示意：

```json
{
  "$type": "org.example.scheduler.wafer.WaferContext",
  "$id": 12345,
  "fields": {
    "wafer": {},
    "job": {},
    "sequence": {}
  },
  "$remainingFields": 3
}
```

数组示意：

```json
{
  "$type": "java.lang.Object[]",
  "$id": 56789,
  "$length": 100,
  "elements": [],
  "$remaining": 80
}
```

### 6.5 `FrameSnapshotter`

文件：

```text
jdwp-core/src/main/java/one/edee/mcp/jdwp/core/FrameSnapshotter.java
```

采集内容：

- 栈帧序号；
- 类名；
- 方法名；
- 源代码行；
- JDI code index；
- 顶层栈帧局部变量；
- 顶层栈帧 `this`。

局部变量需要目标 class 包含 `LocalVariableTable`。如果不存在，Collector 会记录明确错误：

```text
LocalVariableTable is absent; compile target classes with debug information
```

不会因为某个类没有调试信息而终止整个采集 Session。

---

## 7. `jdwp-batch-collector` 设计

### 7.1 `CollectorMain`

文件：

```text
jdwp-batch-collector/src/main/java/one/edee/mcp/jdwp/collector/CollectorMain.java
```

它是独立 Java CLI，不启动 Spring Boot 和 MCP。

主要参数：

```text
collect
--plan <debug-plan.json>
--output <output-directory>
--host <optional override>
--port <optional override>
```

处理流程：

```text
解析命令行
  -> 读取 JSON Plan
  -> 校验 Plan
  -> 应用 host/port 覆盖
  -> attach 目标 JVM
  -> 执行 TracePlanExecutor
  -> 写 raw-trace.jsonl
  -> 写 collection-manifest.json
  -> dispose 调试连接
```

### 7.2 `DebugPlan`

文件：

```text
jdwp-batch-collector/src/main/java/one/edee/mcp/jdwp/collector/DebugPlan.java
```

计划顶层字段：

| 字段 | 作用 |
|---|---|
| `sessionId` | 本次采集的稳定标识 |
| `target.host` | JDWP 地址 |
| `target.port` | JDWP 端口 |
| `resumeOnAttach` | Collector 安装采集点后是否恢复目标 JVM |
| `idleTimeoutMillis` | 多长时间没有事件后停止 |
| `maxEvents` | 整个 Session 的最大事件数 |
| `tracepoints` | 采集点列表 |

Tracepoint 字段：

| 字段 | 作用 |
|---|---|
| `id` | 采集点唯一 ID |
| `className` | 完整类名 |
| `line` | 源代码行号 |
| `methodName` | 可选的方法名过滤 |
| `maxHits` | 最大命中次数 |
| `capture` | 本采集点的快照策略 |

Capture 字段：

| 字段 | 作用 |
|---|---|
| `locals` | 是否读取顶层局部变量 |
| `stack` | 是否输出调用栈 |
| `maxFrames` | 最大栈帧数量 |
| `maxDepth` | 最大对象深度 |
| `maxItems` | 每层最大字段或数组元素数 |
| `maxStringLength` | 最大字符串长度 |

### 7.3 `TracePlanExecutor`

文件：

```text
jdwp-batch-collector/src/main/java/one/edee/mcp/jdwp/collector/TracePlanExecutor.java
```

这是 Batch Collector 的核心执行器。

#### 已加载类

如果：

```java
vm.classesByName(tracepoint.className)
```

已经能找到目标类，则：

```text
ReferenceType.locationsOfLine(line)
  -> 根据 methodName 过滤
  -> createBreakpointRequest(location)
  -> SUSPEND_EVENT_THREAD
  -> enable
```

#### 未加载类

如果目标类尚未加载，则：

```text
createClassPrepareRequest
  -> addClassFilter(className)
  -> enable
```

收到 `ClassPrepareEvent` 后，再解析源代码位置并安装真正的断点。

这保证 Collector 能在 UT 尚未开始时提前 attach。

#### 事件循环

```text
vm.eventQueue().remove(timeout)
  -> BreakpointEvent
       -> 读取 tracepoint ID
       -> 增加 hit count
       -> 捕获线程、位置、frames、locals、this
       -> 写 JSONL
       -> 达到 maxHits 后 disable
  -> ClassPrepareEvent
       -> 安装延迟 tracepoint
  -> VMDeathEvent / VMDisconnectEvent
       -> 正常结束
  -> eventSet.resume()
```

### 7.4 Suspend Policy

当前使用：

```java
EventRequest.SUSPEND_EVENT_THREAD
```

即：

- 只短暂停命中 tracepoint 的线程；
- 不暂停整个 JVM；
- 采集完成后立即 `eventSet.resume()`。

对于单线程算法 UT，这仍然会短暂停住算法线程，但不需要人工点击 Resume。

### 7.5 `JsonlTraceWriter`

文件：

```text
jdwp-batch-collector/src/main/java/one/edee/mcp/jdwp/collector/JsonlTraceWriter.java
```

输出契约：

```text
一个事件 = 一行紧凑 JSON
```

即使 Manifest 使用 pretty-print，JSONL Writer 也会显式关闭缩进，避免一个事件跨多行。

每次写入后 flush，目标 JVM 意外退出时也能保留已经采集的证据。

---

## 8. 具体代码变更点

### 8.1 根 `pom.xml`

新增：

```xml
<module>jdwp-core</module>
<module>jdwp-batch-collector</module>
```

### 8.2 `jdwp-mcp-server/pom.xml`

新增：

```xml
<dependency>
    <groupId>one.edee.mcp.jdwp</groupId>
    <artifactId>jdwp-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 8.3 `JDIConnectionService`

修改前：

```text
JDIConnectionService 内部：
  -> Bootstrap.virtualMachineManager()
  -> 遍历 AttachingConnector
  -> 查找 SocketAttach
  -> 填写 hostname/port
  -> attach
```

修改后：

```java
vm = new JdiSocketAttacher().attach(new JdwpEndpoint(host, port));
```

连接生命周期、MCP 事件处理、断点和 Watcher 行为保持不变。

### 8.4 新增可执行 Collector Jar

Collector 使用 Maven Assembly Plugin 生成包含运行时依赖的独立 Jar：

```text
jdwp-batch-collector/target/jdwp-batch-collector.jar
```

运行 Collector 不需要单独拼装 Jackson 或 Core classpath。

### 8.5 新增文档和示例

JDWP 仓库新增：

```text
docs/batch-collector.md
docs/jdwp-collector-refactoring.md
examples/wafer-scheduler-debug-plan.json
```

---

## 9. 为什么没有一次性搬迁全部 MCP 内部类

本次采用渐进式重构。

当前仍留在 `jdwp-mcp-server` 的能力包括：

- 原有 `BreakpointTracker`；
- 原有 `JdiEventListener`；
- EventHistory；
- Watcher；
- Object Mark；
- 表达式编译和求值；
- MCP Tool Adapter；
- MCP 诊断和资源输出。

原因：

1. 原 MCP Server 已有 1000 多个测试；
2. 一次迁移所有类会显著扩大回归范围；
3. Batch Collector MVP 不需要全部交互式能力；
4. Collector 的事件吞吐和文件输出模型与 MCP 不完全相同；
5. 应先用真实算法 UT 验证批量采集路线。

因此第一阶段只抽取已经明确共享的：

- Endpoint；
- SocketAttach；
- 快照限制；
- JDI Value Snapshot；
- Frame Snapshot。

后续可以继续下沉：

```text
BreakpointRegistry
ClassPrepareRegistry
通用 JdiEventLoop
Object Projection
TracePlan Compiler
```

---

## 10. Debug Plan 示例

晶圆调度示例位于：

```text
D:\mcpcode\mcp-jdwp-java\examples\wafer-scheduler-debug-plan.json
```

示例：

```json
{
  "sessionId": "wafer-scheduler-order-analysis",
  "target": {
    "host": "localhost",
    "port": 5005
  },
  "resumeOnAttach": true,
  "idleTimeoutMillis": 120000,
  "maxEvents": 10000,
  "tracepoints": [
    {
      "id": "scheduler-decision-loop",
      "className": "org.example.scheduler.wafer.SimpleWaferScheduler",
      "line": 120,
      "methodName": "scheduleWafer",
      "maxHits": 500,
      "capture": {
        "locals": true,
        "stack": true,
        "maxFrames": 8,
        "maxDepth": 2,
        "maxItems": 20,
        "maxStringLength": 2000
      }
    }
  ]
}
```

这个采集点对应当前 Demo 中：

```java
int start = Math.max(readyAt, resourcesReadyAt);
```

它适合分析：

- 为什么这个 operation 从某个时刻开始；
- wafer 本身何时 ready；
- robot/chamber/load port 等资源何时 ready；
- 调用链从哪个 UT 和调度方法进入；
- 同一位置在不同 wafer 上的状态差异。

---

## 11. 一次完整采集的运行时序

```text
Terminal A                         Terminal B
----------                         ----------
启动 Maven/JUnit UT
JDWP suspend=y
等待 localhost:5005
                                   启动 Collector
                                   读取 debug-plan.json
                                   attach 目标 JVM
                                   安装/注册 tracepoint
                                   vm.resume()
UT 开始执行
命中调度代码行
线程短暂停止
                                   捕获 stack/locals/this
                                   写 raw-trace.jsonl
                                   eventSet.resume()
UT 继续执行
...
UT 完成，目标 JVM 退出
                                   收到 VMDeath
                                   写 collector_finished
                                   写 collection-manifest.json
                                   Collector 退出
```

---

## 12. 使用方式

### 12.1 环境要求

推荐：

- JDK 21；
- Maven 3.9 或兼容版本；
- 目标 JVM 可启用 JDWP；
- JDK 中存在 `jdk.jdi` 模块；
- 目标 class 保留行号和局部变量调试信息。

检查：

```powershell
java -version
mvn -version
java --list-modules | Select-String "jdk.jdi"
```

### 12.2 构建 Collector

```powershell
cd D:\mcpcode\mcp-jdwp-java

mvn -pl jdwp-batch-collector -am test package
```

生成：

```text
D:\mcpcode\mcp-jdwp-java\
  jdwp-batch-collector\target\jdwp-batch-collector.jar
```

### 12.3 第一终端：以 JDWP 模式启动 UT

```powershell
cd D:\javacode\hellomvn

mvn `
  "-Dtest=org.example.scheduler.wafer.SimpleWaferSchedulerTest#complexParallelModeSchedulesThreeJobsAcrossFiveChambers" `
  "-DargLine=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" `
  test
```

出现下面内容表示正在等待 Collector：

```text
Listening for transport dt_socket at address: 5005
```

推荐使用 `suspend=y`，因为这样 Collector 能在 UT 真正执行前安装所有采集点。

### 12.4 第二终端：启动 Collector

```powershell
cd D:\mcpcode\mcp-jdwp-java

java --add-modules jdk.jdi `
  -jar D:\mcpcode\mcp-jdwp-java\jdwp-batch-collector\target\jdwp-batch-collector.jar collect `
  --plan D:\mcpcode\mcp-jdwp-java\examples\wafer-scheduler-debug-plan.json `
  --output D:\javacode\hellomvn\output\jdwp-trace
```

Collector 成功结束时会输出类似：

```text
Collection finished (vm_death), 167 events written to ...\raw-trace.jsonl
```

### 12.5 Host 和 Port 覆盖

不修改 Plan，也可以通过 CLI 覆盖：

```powershell
java --add-modules jdk.jdi `
  -jar jdwp-batch-collector.jar collect `
  --plan debug-plan.json `
  --host 127.0.0.1 `
  --port 6006 `
  --output output\trace
```

---

## 13. 输出文件

### 13.1 `raw-trace.jsonl`

每个事件一行，主要字段：

```json
{
  "schemaVersion": "1.0",
  "sessionId": "wafer-scheduler-order-analysis",
  "sequence": 10,
  "timestamp": "2026-07-27T13:58:41.000Z",
  "eventType": "tracepoint_hit",
  "tracepointId": "scheduler-decision-loop",
  "hit": 8,
  "thread": {
    "id": 1,
    "name": "main"
  },
  "location": {
    "className": "org.example.scheduler.wafer.SimpleWaferScheduler",
    "methodName": "scheduleWafer",
    "line": 120,
    "codeIndex": 251
  },
  "frames": []
}
```

生命周期事件包括：

```text
collector_started
tracepoint_hit
collector_finished
```

### 13.2 `collection-manifest.json`

记录本次采集是否可信和完整：

```json
{
  "schemaVersion": "1.0",
  "sessionId": "wafer-scheduler-order-analysis",
  "target": {
    "host": "localhost",
    "port": 5005
  },
  "completionReason": "vm_death",
  "eventCount": 167,
  "hitCounts": {
    "scheduler-decision-loop": 165
  },
  "installedLocations": {
    "scheduler-decision-loop": 1
  }
}
```

重点检查：

| 字段 | 判断 |
|---|---|
| `completionReason=vm_death` | 目标 JVM 正常运行到退出 |
| `installedLocations > 0` | 源代码行成功解析并绑定 |
| `hitCounts > 0` | 调度执行确实经过采集点 |
| `completionReason=idle_timeout` | 可能未命中、UT 卡住或计划错误 |
| `installedLocations=0` | 行号、类名、方法名或调试信息可能不正确 |

---

## 14. 已完成的真实验证

本节早期数据保留为重构完成时的验证记录；统一、最新的跨工具事实以`tool-validation-baseline.md`为准。

验证 UT：

```text
SimpleWaferSchedulerTest
  #complexParallelModeSchedulesThreeJobsAcrossFiveChambers
```

验证采集点：

```text
SimpleWaferScheduler.scheduleWafer():120
```

验证结果：

| 项目 | 结果 |
|---|---|
| UT | 通过 |
| 安装位置数 | 1 |
| tracepoint 命中 | 165 |
| 总事件数 | 167 |
| 完成原因 | `vm_death` |
| JSONL 行数 | 167 |
| 逐行解析失败 | 0 |

实际产物：

```text
D:\javacode\hellomvn\output\jdwp-collector-smoke\raw-trace.jsonl
D:\javacode\hellomvn\output\jdwp-collector-smoke\collection-manifest.json
```

2026-08-10重新验证产物：

```text
D:\javacode\hellomvn\output\jdwp-trace\manual-validation-20260810\raw-trace.jsonl
D:\javacode\hellomvn\output\jdwp-trace\manual-validation-20260810\collection-manifest.json
D:\javacode\hellomvn\output\jdwp-trace\manual-validation-20260810\scheduling-hit-summary.csv
```

重新验证结果：

| 项目 | 结果 |
|---|---|
| Core/Collector构建测试 | 7个测试通过 |
| 原始复杂UT | 1个测试通过 |
| 安装位置数 | 1 |
| tracepoint命中 | 165 |
| 总JSONL事件 | 167（含启动和结束生命周期事件） |
| Raw Trace大小 | 2,246,165 bytes |
| 完成原因 | `vm_death` |
| Gantt SHA-256 | `CD09CDB200821C47E6FB464274BD36C317245B4026E37999D27ED9614DC4CB4D` |

本次构建命令只覆盖`jdwp-core`与`jdwp-batch-collector` Reactor，未重新执行MCP Server全量测试。因此下面的MCP定向/全量测试结论属于早期重构记录，不应冒充2026-08-10复验结果。

新增模块测试：

```text
jdwp-core：2 个测试通过
jdwp-batch-collector：5 个测试通过
MCP 连接层定向回归：16 个测试通过
```

早期记录显示，原 MCP Server 全量测试在当时Windows环境有两个重构前就存在的基线失败：

1. classpath 测试固定期望 `:`，Windows 实际使用 `;`；
2. Maven wrapper 测试期望 `mvnw`，当前环境选择 `mvn`。

这两个失败与 Collector 和 Core 改动无关。

---

## 15. Collector 与 JDWP-MCP 应该怎样配合

### 15.1 第一轮：Collector 批量采集

适合回答：

- 调度循环执行了多少次；
- 每次开始时间计算的输入是什么；
- wafer 和资源状态怎样变化；
- 某条代码路径是否被执行；
- 不同 wafer 在同一个决策点的变量差异。

### 15.2 第二轮：离线分析

后续工具读取：

```text
算法输入 JSON
调度结果 JSON
静态代码分析结果
raw-trace.jsonl
collection-manifest.json
```

然后构建：

```text
Normalized Trace
Domain Trace
Evidence Graph
Debug Report
```

### 15.3 第三轮：MCP 集中深挖

如果证据仍不足，则重新运行同一个 UT，通过 MCP：

- 在某个策略类增加临时断点；
- 检查某个特定 wafer/candidate；
- 求值特定表达式；
- 对比过滤前后的集合；
- 检查某个 scorer 的分数；
- 验证一个假设。

不要让 Batch Collector 和 MCP Server 同时 attach 同一个 UT JVM。

推荐：

```text
Run 1：Collector 完整采集
Run 2：MCP 聚焦调查
Run 3：补充计划后再次 Collector 采集
```

---

## 16. 通用性与迁移能力

### 16.1 Collector 不依赖晶圆调度模型

Collector 只理解：

- 类；
- 方法；
- 源代码行；
- 线程；
- 栈帧；
- 局部变量；
- 对象字段；
- 数组；
- JVM 生命周期。

因此也能用于：

- 其他调度算法；
- 规则引擎；
- 路径规划；
- 资源分配；
- Java 服务；
- 普通 JUnit 测试；
- Spring Boot 程序。

晶圆调度相关内容只存在于示例 Plan 的类名、方法名和采集点中。

### 16.2 跨电脑迁移

可以迁移整个源码仓库并重新构建：

```powershell
mvn -pl jdwp-batch-collector -am clean test package
```

也可以只复制：

```text
jdwp-batch-collector.jar
debug-plan.json
```

然后直接运行。

### 16.3 跨操作系统

核心代码基于 Java/JDI，未硬编码 Windows API，原则上支持：

- Windows；
- Linux；
- macOS。

当前已在 Windows + JDK 21 上完成真实验证。正式发布前仍应增加跨平台 CI。

### 16.4 MCP 路径迁移

MCP 配置通常引用 Jar 的绝对路径。迁移电脑后需要调整：

```json
{
  "command": "java",
  "args": [
    "-jar",
    "<new-path>/mcp-jdwp-java.jar"
  ]
}
```

这属于启动配置迁移，不影响 Core 和 Collector 的通用性。

---

## 17. 安全与性能规则

### 17.1 JDWP 端口

JDWP 不应暴露到不可信网络。

本机离线 UT 推荐监听：

```text
127.0.0.1:5005
```

远程采集应使用：

- 隔离网络；
- SSH Tunnel；
- 临时端口；
- UT 结束后关闭 JVM。

### 17.2 采集计划限制

建议初始使用：

```json
{
  "maxHits": 100,
  "capture": {
    "maxFrames": 8,
    "maxDepth": 1,
    "maxItems": 10,
    "maxStringLength": 1000
  }
}
```

确认数据不足后，再逐步扩大。

### 17.3 避免高频无价值代码行

不要一开始就在以下位置设置大对象快照：

- 极高频循环内部；
- 集合每次比较的 Comparator；
- 每个字段访问；
- 日志方法；
- Getter；
- JVM 或框架内部类。

优先采集：

- 候选集合生成完成后；
- 约束过滤完成后；
- score 计算完成后；
- candidate 选中位置；
- operation commit 前后；
- resource state 更新时间点。

---

## 18. 当前限制

### 18.1 源代码行号可能漂移

当前 tracepoint 依赖：

```text
className + line + optional methodName
```

代码增加或删除行后，Plan 需要更新。

后续应实现 Source Anchor：

```json
{
  "className": "...",
  "methodName": "scheduleWafer",
  "sourcePattern": "int start = Math.max"
}
```

由 Static Analyzer/Plan Compiler 解析成最终行号。

### 18.2 尚未支持字段路径投影

当前对象快照按照深度和字段数量展开。

更适合大算法对象的方式是：

```json
{
  "capturePaths": [
    "context.wafer.waferId",
    "readyAt",
    "resourcesReadyAt",
    "requiredResources",
    "resourceAvailableAt"
  ]
}
```

这样可以减少噪音和 trace 大小。

2026-08-10的2.25MB Trace再次直观看到该问题：为了获得`waferId/jobId/planned/readyAt/resourcesReadyAt`，当前全locals快照同时写入了HashMap table、List backing array、enum静态字段等大量JDK实现细节。手工生成的`scheduling-hit-summary.csv`只是验证视图，不是正式Normalizer，也不能替代Collector侧投影。

### 18.3 尚未实现表达式采集

当前 Batch Collector 不执行任意表达式。

后续可以增加受限表达式，但必须：

- 默认只允许字段读取；
- 禁止任意方法调用；
- 有超时；
- 有 allowlist；
- 单个 tracepoint 批量求值；
- 求值失败不影响线程恢复。

### 18.4 尚未生成 Domain Trace

目前输出是 Raw Trace：

```text
tracepoint_hit + stack + locals + object snapshot
```

还需要 Trace Normalizer 转换为：

```text
candidate_generated
constraint_filtered
score_calculated
candidate_ranked
candidate_selected
resource_state_updated
schedule_committed
```

### 18.5 尚未提供正式发行包

理想发行结构：

```text
jdwp-debug-toolkit/
├── bin/
│   ├── collector.cmd
│   ├── collector.sh
│   ├── mcp-server.cmd
│   └── mcp-server.sh
├── lib/
├── schemas/
├── examples/
└── docs/
```

当前已有可执行 Jar、文档和示例，但尚未组装统一发行包。

---

## 19. 下一步重构和产品化路线

### Phase 2：采集计划增强

- JSON Schema；
- Source Anchor；
- 方法入口/出口采集；
- 字段路径投影；
- 条件 tracepoint；
- 计划 dry-run；
- collection preview。

### Phase 3：共享 Core 深化

- 抽取通用 BreakpointRegistry；
- 抽取 ClassPrepareRegistry；
- 抽取 JdiEventLoop；
- MCP 和 Collector 共享更多生命周期逻辑；
- 保留各自的输出 Adapter。

### Phase 4：Trace Normalizer

- 读取 Raw Trace；
- 解析目标算法对象；
- 生成稳定 Domain Trace；
- 添加 source location 和 input field 引用；
- 建立 operation、wafer、job、resource 的关联。

### Phase 5：Trace Validator

- 校验事件序列完整性；
- 校验断点安装和命中；
- 校验资源冲突；
- 校验 wafer 操作先后关系；
- 校验算法结果 JSON 和 trace 一致性。

### Phase 6：Agent 集成

- 根据用户问题生成 Debug Plan；
- 执行静态分析；
- 调用 Debug Harness；
- 判断 Evidence Sufficiency；
- 证据不足时生成补充计划；
- 最后生成 `debug-report.md`。

---

## 20. 常见问题

### 20.1 Collector 会修改调度算法吗

不会。它从目标 JVM 外部通过 JDI/JDWP 读取运行时状态。

### 20.2 Collector 会暂停线程吗

会短暂停命中 tracepoint 的线程。采集完成后自动恢复，不需要人工操作。

### 20.3 为什么不直接全部使用 MCP

MCP 更适合低频、交互式、问题已经聚焦的调查。完整调度过程可能有大量事件，更适合 Collector 流式写文件。

### 20.4 为什么不直接在算法中记录 Domain Trace

因为目标是零源码侵入，并且新问题可能需要新变量。外部采集可以按计划变化，而不修改算法。

### 20.5 为什么同一 JVM 不同时连接 MCP 和 Collector

两个调试器会同时控制断点、线程暂停和 EventQueue，容易产生事件归属和恢复冲突。确定性 UT 可以重复运行，因此分轮调查更安全。

### 20.6 `installedLocations` 是 0 怎么办

检查：

- 类名是否正确；
- 行号是否有可执行字节码；
- `methodName` 是否正确；
- class 是否包含 LineNumberTable；
- 代码修改后行号是否漂移；
- 目标 JVM 运行的 class 是否是最新编译结果。

### 20.7 没有局部变量怎么办

检查目标项目编译配置是否保留调试信息。没有 LocalVariableTable 时，Collector 仍能采集调用栈和代码位置，但不能读取方法局部变量。

### 20.8 Trace 太大怎么办

依次降低：

```text
maxHits
maxDepth
maxItems
maxFrames
maxStringLength
```

并把采集点从高频循环移动到决策阶段结束位置。

---

## 21. 最终结论

本次重构已经把原来的单一 MCP 调试工具扩展成两种互补模式：

```text
JDWP-MCP
  = 大模型交互式、聚焦式 Debug

JDWP Batch Collector
  = 计划驱动、自动化、可重复、文件化采集
```

已经打通的完整链路是：

```text
晶圆调度 UT
  -> JDWP suspend 启动
  -> Collector attach
  -> 自动安装 tracepoint
  -> 自动采集 stack/locals/this/object
  -> 自动恢复线程
  -> UT 正常完成
  -> raw-trace.jsonl
  -> collection-manifest.json
```

这一层已经具备：

- Java 通用性；
- 算法源码零侵入；
- 独立 CLI；
- 可重复采集；
- 有界对象快照；
- 延迟类加载断点；
- 严格 JSONL；
- 与现有 MCP Server 共用 Core；
- 真实复杂晶圆调度 UT 验证。

后续工作的重点不再是证明 JDWP 能否采集，而是：

1. 如何根据用户问题自动生成更精确的采集计划；
2. 如何把 Raw Trace 转换成晶圆调度 Domain Trace；
3. 如何验证证据是否充分；
4. 如何把静态代码、动态状态、输入字段和甘特图异常关联起来；
5. 如何让 Agent 基于证据准确解释调度现象。
