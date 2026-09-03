# 当前能力与边界

更新日期：2026-09-03。

## 已实现

### OpenCode 集成

- 安装 `algorithm-debug` Agent、Skill、Command 和 13 个 Custom Tool。
- 不绑定 OpenCode 版本号，以安装后的实际发现和加载检查判断兼容性。
- JS Adapter 调用 `bin/ada.cmd`，Java CLI 输出单一结构化 ToolResponse。
- `analysis_begin` 返回 Case、Analysis ID、配置的算法结果目录和可逐字复制的相对目录。
- 模型结论直接返回用户，不写入 Workspace。

### Case、输入、UT 与 Gantt

- 按目标 Maven 模块规范化路径计算稳定 `projectId`。
- 一个目标 UT 对应一个 Case；需要新确定性工作时追加新的 `analysisId`。
- 只接受目标测试方法第一层源码中恰好一个可确定的 `String` 输入路径，文件名以
  `input.json` 或 `input_.json` 结尾。
- 输入首次按原名复制到 `case/input/`，后续 Analysis 复用并校验同一 Artifact。
- Maven Surefire 精确执行一个 JUnit 5 类或方法，归档退出码、stdout、stderr、Surefire XML。
- 成功普通 Run 从配置的 `${runDate}` Gantt 目录捕获一次新 JSON，保留原文件名。
- 目标异常、断言失败和 Agent/环境失败使用不同的结构化事实，不强制套入封闭业务分类。

### 静态分析

- 生成当前源码的有界 Method Catalog、源码锚点、直接调用边和多态候选边。
- Catalog 是采集规划索引，不声称是完整全程序调用图。
- Maven test classpath 无法解析时明确标记不完整，由 CodePath/JDWP 验证运行时事实。

### CodePath v4

- 按精确 `class#method(descriptor)` 选择方法。
- 支持 `arg[0]`、嵌套普通字段、`return` 和返回对象字段的有界标量投影。
- `arg0`、Getter、任意表达式、容器扫描和完整对象展开不支持。
- 原始 enter/exit 事件归档后，Normalizer 生成 `codepath-invocations.jsonl` 和 Method Path Summary。
- 单个投影不可读不会丢弃调用事件；必填投影缺失会形成 Evidence gap。
- Plan 错误返回具体、单行、有界的英文原因。

### JDWP Collector 4.0 / Plan v5

- 支持精确方法/行断点、显式局部变量、`this` 和普通实例字段的精确值路径。
- 单个 Tracepoint 支持最多四个 `EQUALS` 条件，全部满足才采集；条件与投影共用同一值路径读取规则。
- 分离 `maxObservedHits`、`maxCapturedHits`、首批匹配采样和周期匹配采样。
- 暂停期间只复制选定值，`finally` 恢复事件集，恢复后由同一 Collector 线程顺序写 JSONL。
- Manifest 分别记录 observed、matched、captured、unavailable；每个计划值保留 `CAPTURED/TRUNCATED/REFERENCE_ONLY/UNAVAILABLE` 状态。
- Collector 不递归展开完整对象图，也不自动猜测字段的业务含义。
- 大型重复循环可依据前一轮 Manifest 的具体缺口创建新 Plan；没有固定采集轮数。

### 证据访问与顺序

- `evidence_query` 只查询已注册且 SHA 校验通过的 CodePath invocation 或 JDWP snapshot summary。
- 查询使用精确过滤、分页和字节预算，不进行业务语义判断。
- `run_test`、CodePath 和 JDWP 在单个 OpenCode Runtime 内不得重叠；第二个请求立即被拒绝。
- 当 JDWP 用于细化 CodePath 时，必须等待 CodePath Collection 完成并引用其完整 Evidence ID。
- 最终回答必须包含完整 Case/Analysis 相对目录、实际使用能力、事实分类和完整证据 ID。

### Eval

- Smoke Suite 包含 10 个真实 OpenCode 场景。
- Quality Suite 包含 50 个唯一场景，覆盖成功、目标缺失、输入边界、算法异常、断言失败、
  静态分析、CodePath、JDWP、Artifact 篡改和跨 wafer 因果 refinement。
- Harness 检查 Tool 顺序、执行次数、动态执行重叠、Plan 意图、Evidence lineage、JDWP 条件、
  最终回答、受保护源码未修改、Workspace 应有/实有文件、Artifact 完整性和交互 JSONL。

## 保留边界

- 一个目标 UT 只支持一个算法输入文件。
- Java 工具不解释 Gantt 业务语义，`gantt_inspect` 只提供有界 JSON 结构和值。
- 复杂反射、运行时生成代码和外部依赖分派可能在静态 Catalog 中保持未解析。
- JDWP 只能读取命中栈顶帧可见值及有界实例字段，不执行方法或任意表达式。
- 动态证据受观察命中、匹配命中、采集命中、栈帧、字符串、字节和超时预算约束；超限必须报告为部分证据。
- 当前只保证一个 OpenCode 会话内的目标执行顺序，不提供多会话锁或跨进程协调。
- Agent 不修改目标算法生产源码，不接管生产调度决策。

## 可靠性原则

- Artifact SHA 只证明已注册文件在读取时未被替换或损坏，不证明两次业务结果相同。
- 失败 UT 的动态复现只比较结构化失败指纹；成功 Gantt 不作为动态采集通用门禁。
- Raw Trace 只读；Normalizer、Validator 和 Evidence Engine 确定性地产生派生证据。
- 截断、冲突、条件不可用和缺失值必须显式呈现，不能伪装成确认事实。
