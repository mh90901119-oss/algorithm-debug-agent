# Case Management

本模块保存追加式 Case 分析档案，不实现工作流状态机，也不扫描目标仓库猜测代码版本。

## 当前职责

- `CaseArchiveRepository`：按 `caseId/analysisId/runId/planId/collectionId/evidenceId` 追加保存不可变文档。
- `CaseSessionService`：新问题创建 Case 和首个 Analysis；复用指定 Case 时只追加新的 Analysis。
- `CaseDigestReader`：从不可变 Run、Collection、Evidence 和 Analysis 结果重建有界摘要；损坏子文档只形成警告。
- `CaseArchiveRepository.completeAnalysis`：以 create-new 方式归档本轮最终回答和显式证据引用。
- `CaseArchiveRepository.registerArtifact`：校验实际大小和 SHA-256 后，为 Case 相对产物注册唯一 Artifact ID。
- `RegisteredArtifactReader`：只按已注册 ID 返回最大 64 KiB 的严格 UTF-8 片段，并在每次读取时复核路径、大小和 SHA-256。
- `ReproductionComparator`：只比较同一 Analysis 的目标失败指纹，输出 `MATCHED/CHANGED/INCOMPARABLE`，不解释业务根因。
- `CaseWorkspaceAuditor`：检查控制文件、Artifact 完整性、交互 JSONL 和空目录。
- `AtomicDocumentWriter`：通过临时文件和原子提交防止覆盖历史产物。

## 身份与基线规则

一个用户问题和一个目标 UT 对应一个 Case。只有需要新的确定性执行、采集或归档时才创建新 Analysis；普通澄清可以直接复用已有证据，不创建空 Analysis。

普通 UT Run 和 CodePath/JDWP 动态 Run 可以不同，但必须属于同一 Analysis。失败的普通 Run 保存结构化失败指纹；动态采集只有与该 Analysis 的普通失败指纹匹配时，才能确认同类失败。成功 Run 的 Gantt 独立归档，不参与通用 SHA 门禁。

```powershell
mvn -pl case-management -am test
```