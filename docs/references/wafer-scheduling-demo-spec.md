# Wafer Scheduling Demo 规格说明

- 文档状态：Implemented Demo Scope
- 版本：0.2
- 更新日期：2026-07-20
- 适用范围：离线晶圆调度算法 Demo、UT 复现、Gantt 结果观察

## 1. 文档目的

本文档定义当前 Wafer Scheduling Demo 的总体运行模型、算法输入输出边界，以及下一阶段需要实现的两项调度规则：

1. 机台串行/并行 Job 进腔策略；
2. 同一 Job 内 wafer 防超车规则。

本 Demo 用于离线复现和解释调度结果，不直接连接生产在线系统，也不负责执行设备动作。

## 2. 总体业务模型

### 2.1 Job 启动

Job 启动时必须确定：

- `jobId`：Job 唯一标识；
- `jobStartOrder`：Job 在当前机台上的启动顺序，数值越小表示启动越早；
- `sequenceId`：Job 使用的工艺 Sequence；
- `waferIds`：本 Job 绑定的 wafer 集合；
- `sourceLoadPort`：本 Job wafer 的起始 Load Port；
- wafer 在 Job 内的固定顺序，例如 `waferOrder` 或 Load Port slot 顺序。

正常 Job 启动场景下，本 Job 的所有 wafer 初始都位于绑定的 Load Port 中。

### 2.2 Sequence

Sequence 描述 wafer 必须依次执行的工艺路径。至少应包含：

- Sequence 唯一标识；
- 有序工艺步骤；
- 每个步骤的操作类型；
- 可使用的 Chamber 或 Chamber Group；
- RECIPE 加工时间；
- PICK/PLACE 搬运时间或对应的设备动作参数。

同一 Job 绑定同一个 Sequence，因此同一 Job 的 wafer 具有相同的工艺步骤顺序。

### 2.3 调度输入快照

算法输入表示某一个确定时刻的机台快照。时间轴中的 `0` 表示快照时刻，不表示 Job 的真实启动时刻。

输入至少应包含：

- 快照标识、快照时间和触发原因；
- 机台调度模式：`SERIAL` 或 `PARALLEL`；
- Robot、Load Port、Chamber 等资源信息；
- 每个 Chamber 的可用状态、占用 wafer、运行中 Job 和剩余时间；
- 当前活动 Job 及其启动顺序；
- 每个 Job 绑定的 Sequence；
- 每个 wafer 的当前位置、当前步骤、完成状态和 Job 内顺序；
- 已经开始且不可中断的运行中操作。

算法必须把输入快照视为权威事实，不能根据旧调度结果猜测当前机台状态。

### 2.4 初次调度

初次调度时：

1. Job 已启动并绑定 Sequence 和 wafers；
2. 所有 wafer 位于对应 Load Port；
3. 算法读取完整机台状态；
4. 算法为所有未完成 wafer 生成从当前位置开始的剩余操作；
5. 输出 Gantt 友好的调度结果 JSON。

### 2.5 运行中重调度

机台运行过程中，如果 Chamber 状态、资源可用性、Job 状态或 wafer 状态发生变化，可以触发重调度。

重调度规则：

1. 重新采集当前机台快照并生成新的算法输入；
2. 已完成操作不再进入未来调度；
3. 已开始且不可中断的操作作为 `RUNNING_JOB` 从 `0` 持续到 `remainingTime`；
4. 每片 wafer 从新的 `currentStepIndex` 和 `currentLocation` 继续调度；
5. 上一次结果中尚未执行的未来操作全部失效，由新结果替换；
6. 相同输入必须产生相同输出，便于 UT 复现和结果比较。

每次问题复现使用一个固定输入 JSON，通过 UT 或命令行运行算法并生成独立结果。

## 3. 物理操作语义

一次跨位置搬运必须拆成两个连续物理动作：

```text
PICK:  Chamber/LoadPort -> Robot
PLACE: Robot -> Chamber/LoadPort
```

资源占用规则：

| 操作 | 位置变化 | 同时占用资源 |
|---|---|---|
| PICK | 来源位置 → Robot | Robot + 来源 Chamber/LoadPort |
| PLACE | Robot → 目标位置 | Robot + 目标 Chamber/LoadPort |
| RECIPE | Chamber → Chamber | 对应 Chamber |

约束：

- PICK 后必须紧接 PLACE；
- PICK 结束时间必须等于对应 PLACE 开始时间；
- Robot 同一时间只能执行一个动作；
- Chamber 同一时间只能容纳一片 wafer；
- RECIPE 只能由当前位于该 Chamber 的 wafer 执行；
- 只有空 Chamber 才允许 PLACE；
- wafer 的前一操作 `toLocation` 必须等于后一操作 `fromLocation`。

## 4. Job 串行/并行进腔策略

### 4.1 输入参数

机台快照必须提供：

```json
{
  "jobProcessingMode": "SERIAL"
}
```

允许值：

- `SERIAL`：不同 Job 对共享 Chamber 实行 Job 级独占；
- `PARALLEL`：不同 Job 的 wafer 可以交替使用共享 Chamber。

该参数属于本次算法输入快照，而不是写死在算法代码中的配置。

### 4.2 SERIAL 模式

对于某个 Chamber，如果多个活动 Job 的 Sequence 都允许使用该 Chamber：

1. 按 `jobStartOrder` 确定 Job 优先级；
2. 最早启动且尚未完成该 Chamber 加工的 Job 获得 Chamber 所有权；
3. 在该 Job 的所有 wafer 都完成该 Chamber 的 RECIPE 并 PICK 离开 Chamber 前，后续 Job 不得 PLACE 进入该 Chamber；
4. 当前 Job 的最后一片 wafer PICK 离开后，Chamber 所有权释放给下一个尚未完成的 Job；
5. Job 不使用的 Chamber 不受该 Job 阻塞。

这里的“完成该 Chamber 加工”定义为：该 Job 绑定的所有 wafer 都已完成该 Chamber 对应 RECIPE，并且已经从该 Chamber PICK 到 Robot。是否已经 PLACE 回 Load Port 不影响 Chamber 所有权释放。

示例：J1 比 J2 先启动，两者都使用 CH1。

```text
允许：J1-W1 -> J1-W2 -> J2-W1 -> J2-W2
禁止：J1-W1 -> J2-W1 -> J1-W2 -> J2-W2
```

### 4.3 PARALLEL 模式

不同 Job 的 wafer 可以交替使用同一 Chamber，不维护 Job 级 Chamber 独占权。

仍然必须满足：

- Chamber 单 wafer 容量；
- Robot 互斥；
- wafer 工艺步骤顺序；
- 同一 Job 内防超车规则；
- 运行中操作不可中断；
- Chamber 状态必须允许加工。

示例：J1 和 J2 都使用 CH1，以下顺序在资源和防超车约束满足时允许：

```text
J1-W1 -> J2-W1 -> J1-W2 -> J2-W2
```

`PARALLEL` 表示允许 Job 交替，不表示两个 wafer 可以同时占用同一个 Chamber。

## 5. 同一 Job 内 wafer 防超车规则

### 5.1 基本定义

同一 Job 的 wafer 使用同一个 Sequence，并具有稳定的 `waferOrder`。

本阶段使用“首次从 Load Port PICK 的开始时间”定义抽片顺序：

- wafer A 的 Load Port PICK 早于 wafer B，则 A 为先抽片 wafer；
- 若计划时间相同，使用 `waferOrder`，再使用 `waferId` 作为确定性排序条件。

### 5.2 进腔防超车

对于同一个 Job、同一个 Sequence 步骤和同一个目标 Chamber：

- 先抽片 wafer 必须先 PLACE 进入该 Chamber；
- 后抽片 wafer 不得在先抽片 wafer 之前进入对应 Chamber；
- 防超车只约束同一个 Job；不同 Job 之间是否交替由 `SERIAL/PARALLEL` 决定。

形式化约束：

```text
如果：
  A.jobId == B.jobId
  A.targetChamber == B.targetChamber
  A.loadPortPickStart < B.loadPortPickStart

则必须：
  A.chamberPlaceStart < B.chamberPlaceStart
```

本阶段不允许算法通过先抽 B、再让 A 先进 Chamber 的方式绕过该规则。

### 5.3 本阶段边界

本阶段只实现以下防超车范围：

- 同一 Job；
- 同一个目标 Chamber；
- 以首次 Load Port 抽片顺序为基准；
- 约束首次进入该目标 Chamber 的顺序。

多工艺段重复进腔、返工、Chamber Group 动态选腔后的跨腔顺序，以及跨 Job 优先级抢占不在本阶段范围内，后续单独扩展。

## 6. 调度结果和 Gantt 输出

调度结果 JSON 必须包含：

- 快照标识和调度运行标识；
- 算法名称和版本；
- `jobProcessingMode`；
- makespan；
- 资源列表；
- 所有逻辑操作；
- 每个操作的 wafer、Job、Sequence、步骤和时间；
- 每个操作占用的全部资源；
- 操作来源：运行中事实或新调度；
- 调度原因和约束说明；
- 每片 wafer 的最终位置；
- 可选的 Chamber 所有权变化和候选过滤原因。

同一个逻辑操作可以投影到多条资源泳道。例如 PICK 同时显示在 Robot 和来源 Chamber，但在 JSON 中仍然只是一条 operation。

Gantt 显示规则：

- 横轴表示相对快照时间；
- 纵轴表示 Robot、Load Port、Chamber 等资源；
- 同一 wafer 的所有操作使用相同颜色；
- PICK、PLACE、RECIPE 通过条内文字区分；
- 运行中操作使用同一 wafer 颜色并增加特殊边框；
- tooltip/明细表显示 Job、Sequence、位置变化、资源和调度原因。

## 7. UT 复现方式

每个测试 Case 应由一个独立输入 JSON 表示，推荐目录：

```text
input/cases/
  initial-parallel-two-jobs.json
  initial-serial-two-jobs.json
  reschedule-running-recipe.json
  anti-overtake-same-job.json
  complex-parallel-three-jobs-five-chambers.json
```

UT 的职责：

1. 读取输入 JSON；
2. 运行调度算法；
3. 校验资源无冲突；
4. 校验位置链连续；
5. 校验 Chamber occupant；
6. 校验 SERIAL/PARALLEL 规则；
7. 校验同 Job 防超车；
8. 输出 Gantt JSON；
9. 必要时与预期关键决策或 golden result 比较。

不建议只比较完整 JSON 文本。核心业务约束应使用独立断言，避免合法时间平移导致无意义的测试失败。

## 8. 当前实现覆盖情况

| 能力 | 当前状态 | 说明 |
|---|---|---|
| JSON 输入 | 已实现 | 可以读取单个快照 JSON |
| UT/命令行离线复现 | 已实现 | 可重复运行并生成结果 |
| 运行中 RECIPE 剩余时间 | 已实现 | 输出为 `RUNNING_JOB` |
| PICK/PLACE 物理拆分 | 已实现 | Robot 与单个端点同步占用 |
| Robot/LP/Chamber 资源互斥 | 已实现 | 基于资源可用时间 |
| wafer 位置连续性 | 已实现 | 操作位置链校验 |
| Chamber occupant | 已实现 | 禁止放入已占用 Chamber |
| Gantt JSON 和 HTML | 已实现 | 按资源显示、按 wafer 着色 |
| 独立 Job 模型 | 已实现 | Job 绑定 Sequence、LP、启动顺序和 wafers |
| 独立 Sequence 模型 | 已实现 | Sequence 定义有序 Chamber/RECIPE steps |
| Job 启动顺序 | 已实现 | 使用 `jobStartOrder` |
| 机台 SERIAL/PARALLEL 模式 | 已实现 | 由每次输入快照指定 |
| SERIAL Chamber 所有权 | 已实现 | 先启动 Job 的全部 wafer 优先通过共享 Chamber |
| 同 Job waferOrder | 已实现 | Job 内唯一稳定顺序 |
| 同 Job 防超车 | 已实现 | LP PICK 和 Chamber PLACE 顺序一致 |
| 快照 ID/重调度原因 | 已实现 | 输入包含 `snapshotId/triggerReason` |
| 多 Case 输入目录 | 已实现 | 初始串行、初始并行、运行中重调度 |

因此，当前项目已经实现本文 0.2 版本定义的 Job/Sequence、SERIAL/PARALLEL 和基础防超车 Demo。它仍是确定性离线演示算法，不代表完整生产调度器。

## 9. 下一阶段建议实现顺序

1. 将当前确定性 wafer 排序扩展为显式 candidate/selector 循环；
2. 在结果中输出候选生成、策略过滤和 Chamber owner 变化；
3. 支持 Chamber Group 和多个可选 Chamber；
4. 将 Load Port 从整体互斥细化为 slot 资源；
5. 支持暂停、取消、跳片和返工；
6. 扩展多工艺段防超车规则；
7. 增加 golden result 与 trace validator。

## 10. 待确认问题

以下问题不阻塞本文档记录，但在实现更复杂 Sequence 前需要确认：

1. SERIAL 所有权是按单个 Chamber、Chamber Group，还是整个机台统一生效？本文暂按单个 Chamber 定义。
2. 如果 Sequence 配置多个可选 Chamber，防超车是在选腔前按 Chamber Group 生效，还是选腔后只对实际 Chamber 生效？本文暂按选腔后的实际 Chamber 定义。
3. Load Port 是整体互斥资源还是按 slot 独立？当前 Demo 按整体互斥，真实模型建议按 slot 建模。
4. Job 被暂停、取消或 wafer 被跳过时，SERIAL Chamber 所有权如何释放？
5. 重调度是否允许改变尚未开始 wafer 的目标 Chamber？
6. 后续多工艺段中，防超车是否需要扩展到每一个 Sequence step？
