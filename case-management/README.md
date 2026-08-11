# Case Management

Phase 0 已实现 Baseline Case 的确定性核心：

- `CaseResolutionService`：根据运行前 `CaseFingerprint` 和结构化 `CaseIntent` 决定复用、新 Case、
  Revision 或要求确认；
- `BaselineStabilityService`：相同 Fingerprint 下连续语义哈希一致进入 `BASELINE_STABLE`，结果不同
  进入 `BASELINE_UNSTABLE`；
- `CaseWorkspace`：建立 `baseline/runs/inquiries` 标准目录；
- `ImmutableArtifactStore`：用临时文件和原子移动保存不可覆盖的 Run 产物。

LLM 只能提出 `CaseIntent`，不能绕过 Fingerprint 规则。不同问题在同一 Case 下创建 Inquiry；同一
Inquiry 的追问创建 Turn。Inquiry/Turn JSON 持久化、Case Lock、状态仓库和崩溃恢复属于下一阶段。

```powershell
mvn -pl case-management -am test
```
