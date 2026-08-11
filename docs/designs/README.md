# 可实施详细设计目录

本目录集中保存进入编码阶段的功能级、模块级和跨模块详细设计。

## 使用规则

1. 编码前先检索是否已有覆盖本次变更的设计。
2. 已有设计仍适用时，在其变更记录中增加本次范围。
3. 没有设计时，复制 `implementation-design-template.md` 创建新文档。
4. 跨模块、Schema、SPI、CLI、性能、安全或外部工具集成变更必须使用完整模板。
5. 小型缺陷可使用模板中的精简章节，但必须保留根因、测试、兼容性和回滚说明。
6. 实现结束后更新状态、实际差异和验证结果。

## 文件命名

推荐格式：

```text
YYYY-MM-DD-<module-or-feature>-design.md
```

示例：

```text
2026-08-10-baseline-runner-design.md
2026-08-18-codepath-plan-adapter-design.md
```

稳定且影响全局的架构结论应另写入 `docs/architecture`；重大决策另写 ADR，详细设计只引用它们。

## 已归档设计

- `2026-08-10-ada-contracts-phase0-design.md`：基础契约模块 Phase 0，状态为 Implemented。
- `2026-08-10-adapter-sdk-design.md`：目标算法适配 SPI，状态为 Implemented。
- `2026-08-10-wafer-demo-adapter-design.md`：Reference Wafer Demo 历史固定结果适配设计，状态为 Superseded。
- `2026-08-11-case-baseline-lifecycle-design.md`：动态结果采集、Case Resolution 与 Baseline 稳定性垂直闭环，状态为 Implemented。
- `2026-08-11-debug-harness-maven-junit-runner-design.md`：通用 Maven/JUnit 子进程监管、日志、超时清理与结果捕获组合，状态为 Implemented。
- `2026-08-12-case-context-run-outcome-multiturn-analysis-design.md`：Case 问题档案、Run Outcome、目标异常与多轮 Analysis/Evidence 持久化，状态为 Review。
