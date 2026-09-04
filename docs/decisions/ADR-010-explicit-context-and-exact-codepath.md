# ADR-010：Context 显式分组与 CodePath 精确方法采集

- 状态：Superseded by ADR-015（精确 CodePath 条款继续有效）
- 日期：2026-08-18

> 当前状态：Context 创建、复用和基线条款已失效；精确方法 CodePath、预算和 SourceAnchor 条款继续有效。

## 背景

实际使用入口是用户在一个具有独立 `pom.xml` 的算法模块中指定单线程 JUnit 5 UT，并在 OpenCode 中围绕
同一问题多轮分析。分析期间通常不修改目标源码、UT 或输入；确实发生修改时，用户或大模型能够明确知道
需要建立新的分析版本。

旧实现把 Context 建模成全模块源码、输入、POM 和 Git revision 的自动快照，又让 CodePath 先按 package
采集超集、再按计划方法二次过滤。前者会因无关文件变化产生误判和扫描成本；后者在大型算法中仍会生成
大量无关事件。两者都没有提升最常用的“按计划采集当前 UT 实际调用事实”能力。

## 决策

1. Context 只是一段显式分析版本的不可变身份，不再表示自动检测得到的 Workspace 快照；
2. 新 Case 自动创建初始 Context；已有 Case 默认复用最新 Context；只有调用方显式选择
   `CREATE_NEW` 才追加 Context；
3. 普通 Run 的 Gantt 内容 Hash 或失败指纹出现 `CHANGED` 时，只保存变化事实，不自动切换 Context；
4. 动态采集与当前 Context 的无采集参考不一致时，保留全部产物但将证据降级为不可确认，不以新建
   Context 掩盖采集干扰；
5. 分析期间不自动扫描或比较源码、UT、输入、POM、Git revision；使用文档和 Skill 要求修改后显式新建
   Context，并重新建立无采集参考和采集计划；
6. CodePath Plan 只保存精确的 `className + methodName + descriptor` selector、目标 UT、身份、理由和
   `maxEvents/maxBytes/timeoutMillis` 硬预算；
7. CodePath Launcher 在事件格式化和写盘前匹配 selector，只生成计划方法的 Raw Trace；不再保留
   package 超集采集与事后二次过滤；
8. 当前 CodePath 范围为单线程目标 UT。首个命中线程成为唯一线程，第二线程命中计划方法时返回结构化
   `CODEPATH_MULTIPLE_THREADS_UNSUPPORTED`，不得猜测合并调用栈；
9. 不修改或 fork 上游 CodePathTracer。本阶段的精确过滤减少事件生成、格式化、写盘和后处理，但不宣称
   消除了上游为具体方法安装 Advice 的全部成本；是否修改上游 matcher 必须由真实性能数据驱动；
10. JDWP 删除全模块源码指纹和前后扫描，但每个 tracepoint 的 `SourceAnchor` 继续保留并确定性校验；
11. 变更涉及的 Context、Case Manifest、Method Catalog、CodePath/JDWP Plan、Method Path Manifest/Trace/
    Summary 升级为 v2。项目尚未发布，不提供 v1 运行兼容或迁移器，开发 Workspace 重新创建。

## 影响

- Context 的建立不再受无关源码文件、换行、POM 或 Git 状态影响；
- 大模型能够复用同一 Context 的历史证据，也能在明确修改后主动创建新 Context；
- Gantt/失败指纹变化仍完整可见，但不会被硬编码状态规则解释为源码变化；
- CodePath Raw 体积由计划方法的实际命中次数决定，而不是整个 package 的调用规模；
- 静态方法目录继续保存精确 `SourceAnchor`，用于选点和 JDWP；删除的只是全模块指纹和 package census；
- 仍需保留事件数、字节数和进程超时预算，因为单线程算法中的关键方法也可能调用数十万次；
- 旧开发 Case 不可直接读取，需要执行一次 Workspace 重建。

## 被否决方案

- **继续自动扫描并拆分 Context**：会为非常用修改场景增加常驻复杂度，并可能把无关变化当成分析版本变化；
- **根据 Gantt 变化自动新建 Context**：结果变化可能来自非确定性或采集干扰，不等价于源码变化；
- **保留 package 超集作为兼容模式**：项目仍处开发期，双模式会扩大契约、测试矩阵和误用概率；
- **立即修改上游 CodePathTracer matcher**：尚无精确方法方案在真实大型 UT 上的性能数据；
- **删除 JDWP SourceAnchor**：行级采集需要精确源码位置，删除会降低而不是简化确定性。
