# 输入优先的因果证据与条件 JDWP 实施计划

**目标：** 精简并增强当前单目标 UT 调试链路，使输入和 Gantt 幂等保留原文件名，Plan 可审计，JDWP 可按通用值路径条件采集，
并用真实 OpenCode Eval 验证多轮因果分析。

**约束：** Java 21、Maven、JUnit 5；测试先行；Collector 无业务语义；历史 Artifact 只读；每阶段完成测试和审计后再继续。

## 阶段 0：设计和当前文档入口

1. 新增并自审当前设计和实施计划。
2. 更新架构/设计索引，使新设计成为唯一当前入口。
3. 删除被完全取代且无历史决策价值的旧设计和计划。
4. 保留 ADR 和必要历史 Schema，明确其只读状态。
5. 检查文档链接、TBD/TODO 和互相矛盾描述。

## 阶段 1：CodePath/JDWP 审计和行为保持重构

1. 为当前 Plan 编译、Collector Plan 映射、断点命中、稀疏采集、CodePath Scope 和归一化增加特征测试。
2. 执行 RED，确认测试能捕获待清理行为。
3. 删除未使用 import、源码 SHA 误导描述、死构造器和无调用方 Collector v1 分支。
4. 提取 JDWP Tracepoint 命中状态，保持现有 v2 行为不变。
5. 运行 debug-plan-engine、CodePath、JDWP Collector、Normalizer 和 Core 受影响测试。
6. 审计 Raw Trace、Manifest、Summary 和 DFX 无行为回归。

## 阶段 2：输入和 Gantt 原文件名幂等归档

1. 先增加 `input_.json`、原文件名、同 Case 复用、输入变化拒绝、Gantt 原文件名和同 Run 幂等测试。
2. 分别执行测试并确认因缺少新行为失败。
3. 修改输入定位器支持两个大小写不敏感后缀。
4. 将输入 Artifact 改为 Case 首次复制、后续 Analysis 引用复用，源文件变化时拒绝覆盖。
5. 将主 Gantt 改为 Run 内按源 basename 归档；CodePath/JDWP 不注册主 Gantt。
6. 更新 Contracts、Schema、CLI ToolResponse 和 Workspace Auditor。
7. 运行静态分析、Case、Harness、Core、CLI、OpenCode Tool 契约测试。
8. 审计同一 Case/Run 无重复文件、无空目录、旧 Case 仍可读。

## 阶段 3：结构化 InvestigationIntent

1. 先增加 Contracts、JSON、Schema、编译器和 Evidence lineage 失败测试。
2. 实现小型不可变 `InvestigationIntent`。
3. 升级 CodePath/JDWP Plan 当前写入版本，保留 v2 只读 Schema。
4. Core 在 Plan 归档前校验引用 Evidence 存在且属于当前 Case。
5. 更新 CLI、OpenCode Tool 和 ToolResponse。
6. 运行 Contracts、Plan Engine、Case、Core、CLI 和 JS Tool 测试。
7. 审计新 Plan 可独立回答“为什么采集、基于什么、期待看到什么”。

## 阶段 4：JDWP 通用值路径条件

1. 先增加条件契约、值读取、匹配/不匹配/不可用、预算和线程恢复测试。
2. 实现无业务语义的条件模型和严格校验。
3. 在 Collector Core 实现只读栈帧值路径解析。
4. 将 Tracepoint 预算统一为 observed、matched、captured 和 unavailable 计数。
5. 条件不匹配时不展开快照；UNAVAILABLE 保留确定性原因。
6. 更新 Collector Plan、Raw Schema、Manifest、Normalizer、Validator 和 Evidence。
7. 更新 Tool 参数和 Plan Compiler。
8. 运行 JDWP 全模块和根项目契约测试。
9. 使用真实 JVM 验证匹配、不匹配、变量不可见、预算停止和异常清理。
10. 审计目标线程恢复、快照上限、Raw Trace 大小和 DFX 计数。

## 阶段 5：输入优先 Skill

1. 删除 Demo 领域知识文件、安装复制入口和主动加载描述。
2. 更新 Skill 顺序为输入、主运行、现象、静态假设、CodePath、条件 JDWP、增量 Evidence。
3. 删除固定方法数、固定轮次和机械动态采集描述。
4. 增加异常已足够、工具失败、证据截断和错误假设的停止规则。
5. 运行安装资产、Skill 文本和 OpenCode 能力发现测试。
6. 审计 Skill 不包含具体算法业务结论。

## 阶段 6：条件采集和复杂因果 Eval

1. 增加条件匹配、条件无匹配、条件不可用 Eval。
2. 增加一个跨对象共享资源因果 Eval；测试数据不作为生产业务知识安装。
3. Grader 检查输入读取、Plan 意图、Evidence lineage、条件计数、最终引用和错误假设拒绝。
4. 不把固定行号或完全固定 Tool 序列作为主要评分条件。
5. 运行 Eval Harness 单元测试和真实 OpenCode Case。
6. 审计每个 Eval 的 Workspace、Interaction、DFX 和最终答案。

## 阶段 7：当前文档和最终门禁

1. 更新 README、架构、当前能力、工作流/Artifact、安装和调试文档。
2. 删除旧描述、旧 Demo 知识设计和失效计划；不保留两个“当前”版本。
3. 运行受影响模块测试、根项目 `mvn test`、构建脚本和安装 Check。
4. 运行主成功 UT、异常 UT、断言失败、CodePath、条件 JDWP 和复杂因果真实 E2E。
5. 每个 Case 执行 Workspace Audit，逐文件确认用途，拒绝意外文件和空目录。
6. 每个 Case 检查 Java DFX 与 OpenCode Interaction，无异常栈、敏感数据和阶段错序。
7. 复杂因果 Eval 连续运行三次，记录实际稳定性而非主观宣称。
8. 新增一份最终审计文档，记录修改、删除、兼容、测试、E2E、已知限制和未实施项。

