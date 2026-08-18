# Case Management

本模块保存追加式 Case 分析档案，不实现工作流状态机，也不扫描目标仓库来猜测 Context。

- `CaseArchiveRepository`：按 `caseId/contextId/analysisId/runId/planId/collectionId/evidenceId` 追加保存不可变文档。
- `CaseSessionService`：新 Case 创建首个 Context；已有 Case 按显式 `REUSE_LATEST` 或 `CREATE_NEW` 选择 Context；每次提问追加 Analysis。
- `ContextRecord`：只记录 Case、Context 和创建时间的分析版本身份，不包含源码、输入、POM、Git 或环境指纹。
- `CaseDigestReader`：重建面向模型的有界历史摘要，包含最近 Run、Collection、Evidence 和 Analysis 结果，
  帮助模型决定复用证据、运行 UT 或继续采集；损坏的子文档只形成告警。
- `CaseArchiveRepository.completeAnalysis`：以 create-new 方式归档一轮最终回答和显式证据引用。
- `ReproductionComparator`：只比较 Gantt JSON 内容与目标失败指纹，输出 `MATCHED/CHANGED/INCOMPARABLE`，不解释业务根因。
- `ImmutableArtifactStore` 和 `AtomicDocumentWriter`：以不可覆盖、临时文件加原子提交的方式保存产物。

Context 的简单规则是：默认复用；只有用户或模型明确知道目标算法源码、UT 或输入被有意修改时才新建。
运行结果变化只作为分析事实返回，不会自动改变 Context。

```powershell
mvn -pl case-management -am test
```
