# SDD ledger — plan: docs/designs/2026-10-06-evidence-constrained-agent-refactor/06-implementation-plan.md

## 授权与基线

- 用户于 2026-10-06 授权本地实施；当前 checkout 原生执行，不创建 worktree。随后单独授权先提交并推送本次文档；代码/Schema/测试改动仍仅在本地，不纳入文档提交。
- BASE：39514630743a65722fca8e90f8eda4051a85a302；分支 codex/qwen-mcp-gateway-bootstrap。
- 初始变更：docs/architecture/README.md 与本设计包来自文档交付；docs/sharing/ 是用户资产，禁止触碰。
- Ruling: 使用仓库内可追踪进度文件替代 skill 的 Bash scratch/commit helpers — Windows 原生执行且计划禁止自动提交；完整测试输出仍由 Maven reports 保存，进度不得靠聊天记忆。
- Ruling: 本地实施不推测公司源码/配置，也不等待公司盘点 — 公司迁移和 A/B 真值是外部验收输入；缺少资料时不宣称公司兼容/质量达标。

## 接口预检

| 生产任务 → 消费任务 | 共享接口 | 预检结论 |
|---|---|---|
| P1 → P2/P4/P6/P7 | 新版本 Schema/字段 | 新协议先独立验证，不提前切换正式 Catalog |
| P2 → P4/P6/P7 | atomic commit/snapshot/operation | 短锁必须释放后再执行 UT/解析 Raw；诊断不改变 token |
| P3 → P5/P7 | ExecutionControl | contracts 所有权；Adapter 禁止 import core |
| P4/P5 → P6 | Truth/Effect/coverage/baseline | 资格与效果分离；反证不靠最新 UNKNOWN 覆盖 |
| P6 → P7/P8 | Candidate/Decision/Finalization | Gate 唯一等级所有者；事实由服务端渲染 |
| P7 → P8/P9 | 同源反馈/恢复/角色资产 | 恢复新 operation，不重跑 UT；真实模型质量独立验收 |

## 任务状态

- P0: local baseline verified — 原有 Java 与 Node 测试已运行；公司能力盘点与真实复杂 Case/模型基线仍需公司资料，不宣称已验证。
- P1: in progress — 已新增 Binding/Gap/Witness/Candidate/Decision/Finalization/RecoveryAction/Commit 等目标 Schema；其余契约和教学夹具未完成，正式 Catalog 未切换。
- P2: in progress (atomic journal tranche only) — 调查写入已采用短锁及单批原子提交；已修复并发序号、半批次和 commit-only Analysis 读取。业务操作身份、结果回放、项目级 begin、统一 snapshotToken 与 Case 共享登记锁尚未完成。
- P3: pending — 取消与资源安全。
- P4: pending — 多轮调查与效果语义。
- P5: pending — UT 资格、JDWP 预检。
- P6: pending — 逐条见证与 Gate。
- P7: pending — MCP 反馈、恢复、宿主。
- P8: pending — 端到端与故障验证。
- P9: pending — Eval、全面审计与发布。

## 验证记录

### 基线与首批 RED/GREEN

- 基线 `mvn -Pcodepath-launcher test -q`：exit 0。未冻结旧版基线精确用例数，不将后续新增测试计入旧版基线。
- Node 原始基线暴露环境问题：Node 继承 PowerShell 7 的 PSModulePath 后调用 Windows PowerShell 5.1，Qwen 安装测试找不到 Get-FileHash。仅在测试进程环境暂时移除 PSModulePath 后，五组 Node 测试 90/90 通过；没有修改安装器生产行为。
- P1：前四个目标夹具针对旧 Schema 得到 4 failures/0 errors；Finalization 针对旧 Schema 得到 1 failure；新增严格目标 Schema 后 GREEN。Commit Schema 补充不可变 DTO round-trip、连续序列、归属与未知字段拒绝。
- P2：并发屏障复现两服务分配相同序号；故障 Writer 复现 PlanBound 两事件只写一半。短锁内读取最新状态、完整回放后一次提交，使两项回归 GREEN。
- P2：总事件数溢出与大于单文档预算的合法旧前缀先 RED，增加提交前总量检查及流式前缀哈希后 GREEN。旧事件文件保持逐字节不变。
- P2：commit-only Analysis 被误判 INVALID、坏 commit 路径被误判 ABSENT，新增两项回归先 RED，再修复 AnalysisArchiveReader 后 GREEN。
- 第一轮完整新代码 `mvn -Pcodepath-launcher test -q`：exit 0；873 tests、0 failures、0 errors、6 skipped（201 个当前 Reactor report）。随后新增的两项 Reader 回归已专项通过，但尚未计入下一轮完整 Reactor 结果。
- `mvn -pl ada-contracts -am test -q`、`mvn -pl case-management,ada-core -am test -q` 已通过；最后一次新增 Reader/Lock 专项测试 exit 0。
- 旧故障注入测试通过 test-only LegacyInvestigationFaults 构造真正的旧逐事件残缺档案；没有在生产代码中删除历史，也没有削弱原断言。

### 实施裁决与未接通边界

- Ruling: P1 的首组 Schema 先与 P2 原子存储闭环交错实施 — 原子提交不依赖后续采集/逐 Claim 协议，可以独立复现真实缺陷；P1/P2 仍明确标为未完成，代价是不能把阶段性 GREEN 视作正式新协议可发布。
- Ruling: 当前 Commit.inputSha256 是规范事件批次的输入哈希，当前 ledger operationId 是内部提交身份 — 不冒充 MCP 业务请求身份或 exactly-once；P2/P7 必须接通业务 Context 和完整回放才可结束该任务，若漏接会失去调用级去重保证。
- 旧二进制不识别新 commits 目录；当前仅开发测试，不发布/混装，也不得让旧服务继续写已经产生新批次的 Analysis。
- 现阶段没有更改 Byte Buddy Advice、JDWP 投影/命中统计、UT 启动方式、正式 Tool 名单或宿主 Prompt。测试通过不证明大型算法根因准确率/模型稳定性已达到目标。
