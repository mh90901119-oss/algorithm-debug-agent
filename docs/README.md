# Documentation map

## Architecture

`architecture/` 保存系统边界、模块职责、运行时采集、工具实测基线和性能设计。

## Designs

`designs/` 保存进入编码阶段的可实施详细设计和统一模板。新功能、跨模块、Schema、SPI、CLI、
性能或安全相关变更必须先在此处新增或更新设计。

## Development

`development/` 保存仓库开发规则、生命周期、测试策略、质量门禁和 Agent 专项工程规范。

## Plans

`plans/` 保存阶段实施计划和验收标准。历史计划不能覆盖当前架构基线。

## Experiments

`experiments/` 保存有明确时间边界的技术 Spike。实验结果是决策证据，不自动成为生产契约。

## Decisions

`decisions/` 保存 Architecture Decision Record。重大技术选型、不可逆边界变化和许可证决策必须归档。

## References

`references/` 保存外部系统、算法 Demo 和领域规格参考，不作为 Agent 核心契约的唯一来源。

## 开发入口

1. 阅读仓库根目录 `AGENTS.md`。
2. 阅读 `development/development-rules.md`。
3. 从 `architecture/README.md` 进入当前架构基线。
4. 检查 `designs/` 是否已有本次变更的可实施设计。
5. 如无设计，复制 `designs/implementation-design-template.md` 后再编码。

