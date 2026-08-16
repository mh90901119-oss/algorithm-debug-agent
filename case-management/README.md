# Case Management

Phase 0 已实现追加式 Case 档案与 Baseline 原型的确定性核心：

- `CaseArchiveLayout`、`CaseArchiveRepository`：按固定目录原子追加 Case、Context、Analysis、Run 请求、
  Run 结果指纹和 write-once Context reproduction reference；
- `CaseSessionService`：不传 `caseId` 时新建 Case；显式续接时校验 Project 和目标 UT；每次问题创建新 Analysis；
- `ContextSnapshotBuilder`：只扫描模块 `src/main/java`、`src/test/java`、POM 和 Adapter 定位的输入，
  使用文件数、字节数和时间预算生成可比较快照；
- `CaseDigestReader`：读取不可变子文档并重建最近 Analysis、Run、不完整 Run 和归档警告的有界摘要；
- `BaselineStabilityService`：相同 Fingerprint 下连续语义哈希一致进入 `BASELINE_STABLE`，结果不同
  进入 `BASELINE_UNSTABLE`；
- `ReproductionComparator`：只比较 Gantt JSON 内容与目标失败两个指纹维度，固定输出
  `MATCHED/CHANGED` 和比较范围，不解释业务原因；
- `CaseWorkspace`：保留为标准目录兼容入口，不再承担新 Case 判定；
- `ImmutableArtifactStore`：用临时文件和原子移动保存不可覆盖的 Run 产物；
- `WorkspaceLayout`、`WorkspaceInitializer`：在目标算法仓库之外建立受边界约束的 Workspace；
- `ProjectRegistry`：登记大型软件仓库中拥有独立 `pom.xml` 的算法模块，不修改目标仓库；
- `WorkspaceConfigurationResolver`：按 CLI、项目级、Workspace 用户级、内置默认值合并固定配置文档。

Case 是一个问题的追加式分析档案，不是工作流状态机。是否复用已有证据、是否执行一次或多次 UT 由大模型
根据 `CaseDigest` 决定；确定性代码只验证显式 `caseId`、Project 和目标 UT。当前不实现全局 Case Lock、
自然语言相似度匹配或可变 `current.json`。

```powershell
mvn -pl case-management -am test
```
