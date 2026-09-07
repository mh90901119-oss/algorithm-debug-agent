# 可扩展 CodePath 采集最终审计

审计日期：2026-09-07。

## 1. 审计范围

本轮修改覆盖 CodePath Plan、目标 JVM Launcher、Collector Adapter、Normalizer、Artifact 注册、`evidence_query`、OpenCode Tool、Skill、Eval 和当前态文档。目标是解决大型算法中“插桩范围过宽、逐调用数据过多、关键值被预算截断、模型难以检索”的问题，不引入目标算法业务字段。

## 2. 行为变化

- CodePath Plan 升级到 v6，新增 `TRACE/AGGREGATE`、`scopeStartOrdinal` 和 `maxMatchedScopes`。
- Launcher 改为仓库自维护的 Plan 感知 Byte Buddy 插桩，只转换 Plan 精确选择的方法。
- `TRACE` 保留逐调用顺序与同次调用投影关联；`AGGREGATE` 只保留有界方法、边、深度、Scope 和投影值计数。
- AGGREGATE 只生成 `raw/codepath-aggregate.json` 和 Method Path Summary，不生成空 Invocation 文件。
- Collection 响应返回主要 Artifact ID、推荐查询工具、支持模式、覆盖状态、原因和下一步动作。
- `evidence_query` 可查询 Method Path Summary 的 `SUMMARY/COUNT`；小输出预算下返回省略计数而不是丢失整个结果。
- 删除不再使用的 CodePathTracer/Kotlin 依赖、本地 Maven Snapshot、第三方目录和根 POM 本地仓库声明。

## 3. 边界与异常审计

| 场景 | 当前行为 | 审计结论 |
| --- | --- | --- |
| Plan v5 | 按 TRACE、默认 Scope 窗口读取 | 兼容保留 |
| 未选择方法 | 不安装对应 Advice，不产生日志事件 | 已测试 |
| 参数位置无效 | 单个投影返回 `ARGUMENT_UNAVAILABLE`，不丢弃整个调用 | 已测试 |
| 空字段、非标量、长字符串 | 分别返回 `NULL`、`NON_SCALAR_VALUE`、`TRUNCATED` | 已测试 |
| 方法抛异常 | 关闭调用栈并将返回值投影标为 `METHOD_THREW` | 已测试 |
| 递归调用 | 最近已选择祖先边在递归返回后保持正确 | 已测试 |
| Scope 条件不匹配 | 只读取条件所需投影，不展开其余投影 | 已测试 |
| Scope 窗口 | ordinal 只统计条件匹配 Scope，窗口外调用显式计数 | 已测试 |
| 事件、字节或值基数超限 | 保留有界前缀/计数并返回明确 reason code | 已测试 |
| Aggregate enter/exit 不平衡 | Summary 标记 `TRACE_STRUCTURE_INCOMPLETE/PARTIAL` | 已测试 |
| Query 预算过小 | 保留总数并报告 `*FactsOmitted` 与 limitation | 已测试 |
| 不支持的 Aggregate 查询模式 | 返回 `UNSUPPORTED_MODE`、支持模式和 `REFINE_QUERY` | 已测试 |
| Collector 失败或截断 | 合并具体原因与硬预算原因，不覆盖原原因 | 已测试 |
| Artifact 被替换 | Registry SHA 校验拒绝读取 | 既有回归覆盖 |

没有新增文件锁、异步写线程或跨进程协调。当前仍只保证单个 OpenCode Runtime 内普通 UT、CodePath 和 JDWP 串行；多个 OpenCode 会话同时运行同一外部 Gantt 目录不在本轮范围。

## 4. 代码质量审计

- Launcher 的路径、值读取、聚合和 Advice 职责分离；没有 God Class 或目标算法语义分支。
- 参数投影使用显式位置和字段路径，字段查找包含继承层级与失败缓存；不调用 getter、不执行表达式、不扫描 Map/List。
- 所有集合均受 Plan、事件、字节、Scope 或值基数预算限制；Raw Aggregate 使用临时文件加原子提交。
- 同步单线程写盘保持事件顺序，未引入队列、后台线程和关闭竞态。
- `CollectionExecutionSummary` 对新增模型提示字段提供旧 JSON 默认值；v2 Schema 中新增字段保持可选，避免破坏旧产物读取。
- 生产错误文本由既有 English-only 集成测试扫描；异常保留 cause，未静默吞异常。
- 删除了旧 `PlannedTraceEventGenerator`、旧捕获模型和本地第三方仓库，未保留双实现。

## 5. 测试结果

- `scripts/build-agent.ps1`：20 模块干净构建成功，`AGENT_BUILD_OK`。
- Java Surefire/Failsafe：142 份报告，549 个测试，0 failure，0 error，5 个按外部环境条件跳过。
- OpenCode/Eval Node：71 个测试全部通过。
- 真实算法 Demo CodePath：TRACE 与 AGGREGATE 各执行目标 UT 一次，均 PASSED；TRACE 为 2 行/698 字节，AGGREGATE 为 2 个接受事件/711 字节。
- AGGREGATE 100000 次调用：连续 5 次通过，含 Maven 启动的中位耗时 2201.2 ms；测试同时断言输出有界。
- `verify-ada-launcher.ps1`：目标 Demo 模块的 Java、Maven、项目、CodePath 和 JDWP 发现通过。
- `verify-jdwp-loopback.ps1`：loopback attach 与条件采集通过。
- `verify-opencode-installer.ps1`：隔离 Profile 中卸载、安装、重复安装、检查、卸载后重装通过。
- 真实 OpenCode Smoke：10 个场景均完成真实执行；9 个首次评分通过，跨实体因果场景因过窄文本正则误判。修正规则后，原轨迹与一次新鲜单 Case 轨迹均确定性重评分通过。
- 新鲜跨实体因果 Case：普通 Run、CodePath、JDWP 均成功，两个 Collection 均为 `SUCCESS/COMPLETE`；Workspace 审计检查 37 个 Artifact、0 issue；交互审计 225 个事件、0 issue。

构建期出现 Maven Shade 重复许可证/`module-info` 警告和 JDK 动态 Agent 未来策略警告，均未造成测试或运行失败。它们属于现有打包/JDK 提示，不以静默或放宽测试处理。

## 6. 过度实施审计

本轮没有新增业务专用字段、直方图独立 Artifact、新 Tool、数据库、索引服务、文件锁、异步生产者消费者、JFR 或 profiler。投影分布复用现有 Method Path Summary 与 `evidence_query COUNT`，避免重复产物。保留 256 个值/投影和 10000 个全局值是确定性内存边界，不是业务策略。

AGGREGATE 不能替代 TRACE：它不能证明调用顺序或两个值属于同一次调用。模型必须先用 Aggregate 定位方法、路径或值分布，再在需要相关性时创建更窄 TRACE Plan。这个两层模式是本轮唯一新增复杂度，直接对应大型算法逐调用数据超预算问题，审计结论为必要且不过度。

## 7. 剩余限制

- 没有用目标组织的私有大型算法执行性能基线；当前性能结论仅覆盖通用 100000 次聚合与本地 Demo。
- Byte Buddy 动态安装在未来 JDK 可能需要显式 Agent 配置；当前 JDK 17/21 路径均可运行，但构建警告已保留。
- 多 OpenCode 会话同时写同一个外部 Gantt 目录仍需用户避免；本轮没有为该未承诺场景增加锁。
