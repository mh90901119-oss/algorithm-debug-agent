# ADR-015：删除 Context并将运行基线限定到 Analysis

- 状态：Accepted
- 日期：2026-09-02

## 背景

Context 当前只保存 ID 和创建时间，不包含源码、输入、构建或环境快照。新 Context 是否创建由
LLM 的 `contextMode` 参数决定，因此它不能可靠发现代码变化，却把 Context 身份传播到所有
Run、Plan、Collection、Trace、Evidence、Schema 和 OpenCode Tool。

项目仍处于调测阶段，旧 Workspace 不需要保留或迁移。

## 决策

1. 删除 `ContextId`、`ContextRecord`、`ContextMode` 和 `contexts/` 目录。
2. Case 表示一个目标 UT 的持续问题档案。
3. Analysis 表示一次实际产生新确定性证据的调查。
4. 已有证据足够的普通追问和只读操作不创建 Analysis。
5. Run、Plan、Collection、Trace 和 Evidence 直接绑定 `caseId + analysisId`。
6. CodePath/JDWP 的目标失败基线只来自同 Analysis 的普通 UT Run。
7. 跨 Analysis Evidence 可以作为历史证据引用，但不得自动作为当前代码基线。
8. 不增加 `codeChanged` 字段，不使用源码、POM、Git 或 Gantt SHA 判断代码变化。
9. 旧 Context Workspace 直接废弃，不实现兼容读取和迁移器。
10. 安装器和卸载器不得自动删除 Workspace。

## 结果

### 正面结果

- 删除不能提供真实版本隔离的中间身份。
- 防止跨 Analysis 误复用普通 Run 基线。
- 减少契约、Schema、目录、CLI 和 Tool 参数复杂度。
- 多轮分析继续通过 Case、Analysis 和不可变历史 Evidence 实现。
- 不影响任意目标 Maven 算法模块的 UT、输入、Gantt 和动态采集接入。

### 代价

- 旧 Context Workspace 无法由新版本读取。
- 需要新动态采集时，必须先在同 Analysis 执行一次普通 UT。
- Agent 无法检测用户未说明的代码修改；原 Context 同样不具备该能力。

## 被取代内容

- ADR-006 中 Context 分组和跨 Context 比较条款由本 ADR 取代。
- ADR-010 中显式 Context 创建、复用和同 Context 失败基线条款由本 ADR 取代。
- ADR-010 的精确 CodePath 方法选择决策继续有效。
