# ADR-017：使用 Qwen MCP Adapter 与双 Runtime 统一工作台

- 状态：Accepted
- 日期：2026-09-20
- 确认日期：2026-09-20
- 确认范围：双 Runtime、算法会话只读、第一版不修改 Qwen Core
- 初始兼容版本：ToolResponse 2.0、View API 1.0、SessionBinding 1.0、Skill 1.0
- 关联设计：[Qwen MCP 与双 Runtime 工作台可实施详细设计](../designs/2026-09-20-qwen-mcp-and-dual-runtime-workbench-design.md)
- 取代范围：扩展 ADR-007 的“当前只支持 OpenCode”阶段性决策，不改变其 CLI 薄适配原则

## 背景

Algorithm Debug Agent 已通过 OpenCode Agent、Skill、13 个 Custom Tool 和 Java CLI 完成真实算法 UT
分析。相同公司模型与算法能力在 Qwen Code 中适配后表现出更好的分析准确性，项目因此出现第二种真实
Agent Runtime 客户端需求。

目标产品需要在一个安装包和一个 Web 工作台中同时提供：

1. 保留 Qwen 原能力的通用编码会话；
2. 只面向 Java/Maven 算法 UT 的专用分析会话；
3. 两类会话共享同一公司模型配置，但不得共享不相容的 Prompt、Skill 和工具权限；
4. 继续复用现有 Java 确定性分析后端，不把算法语义或证据规则搬入 Qwen Core。

Qwen 当前的 Skill、内置工具 allowlist、扩展与追加系统 Prompt 主要绑定 Runtime/Workspace 启动配置。
若在一个 Runtime 中仅靠用户提示区分两类会话，算法会话仍可能看到写文件或 Shell 工具，通用会话也
可能看到算法工具和领域指令。第一版不接受这种软隔离。

## 决策

1. 保留 `algorithm-debug-cli` 和 ToolResponse 2.0 作为确定性后端边界；Java 领域模块不依赖 Qwen 类型。
2. 在本仓库新增 Qwen Extension 与 MCP Adapter。MCP Adapter 暴露现有 13 个工具并调用同一 Java CLI，
   不复制 Maven、CodePath、JDWP、Evidence 或 Case 业务逻辑。
3. `skills/algorithm-debug/SKILL.md` 继续作为规范 Skill 源码。Qwen 打包流程从该位置生成扩展资产，不维护
   可独立漂移的第二份 Skill 正文。
4. 产品工作台内部运行两个隔离的 Qwen Runtime：
   - General Runtime 保留通用 Qwen Prompt、工具和权限；
   - Algorithm Runtime 追加 Algorithm Debug Skill，只注册只读源码工具和 Algorithm Debug MCP 工具。
5. 两个 Runtime 共享同一个 `QWEN_HOME` 中的模型与认证配置，使用不同的 `QWEN_RUNTIME_DIR` 保存运行数据。
   Algorithm Runtime 按需启动。用户只看到一个应用和一个会话列表。
6. Qwen 产品层保存版本化的 `SessionBinding`，把 Qwen `sessionId` 映射到 `GENERAL` 或
   `ALGORITHM_DEBUG`，算法会话同时绑定 Workspace、目标模块、目标 UT、Case 和当前 Analysis。
7. 专用界面只读取版本化、只读、有界的 Agent View 投影；不得通过解析模型自然语言回答推导输入、源码、
   CodePath、JDWP 或 Evidence 状态。
8. 第一版保留 OpenCode 集成并把其行为作为兼容基线。完成真实 Qwen Eval 和发布门禁前，不删除 OpenCode
   安装、测试或文档。
9. 第一版不修改 Qwen Core Agent Loop，不实现单 Runtime 会话级 Profile。若双 Runtime 的实测资源或恢复
   成本不可接受，再以独立设计评估 `(workspace, profileId)` Runtime 池。
10. 第一版算法会话不具备源码写入和任意 Shell 工具。用户要求修改代码时，Agent 只能给出修改建议或
    Diff 预览；自动落盘属于后续独立需求。

## 影响

- ADR-007 中“当前不实现 MCP”的结论因第二客户端真实需求出现而结束，但“Adapter 薄、CLI 确定性、Skill
  单一来源、目标仓库不保存产品资产”的原则继续有效。
- 新增 TypeScript/Node 22 的 MCP/Gateway 交付资产和相应依赖、许可证、锁文件与离线打包验证。
- Qwen Code 仓库新增产品 Host 和 React 工作台包；产品业务编排保持在 Qwen Core 之外。
- 两个 Runtime 会增加本地进程与内存占用，但 Algorithm Runtime 按需启动且故障与 General Runtime 隔离。
- 单个产品进程内由唯一 Algorithm Gateway 串行化 `run_test`、`codepath_collect` 和 `jdwp_collect`；第一版
  不承诺多个产品实例并发操作同一目标 Workspace。
- 两仓发布必须锁定兼容版本，并通过 capability handshake 拒绝不兼容组合。

## 被否决方案

### 单 Runtime，仅靠 Prompt 区分

实现最少，但工具注册与权限没有硬隔离。模型偏离 Prompt 时可能在算法会话修改目标源码，或者在通用
会话误调用算法工具，不满足产品边界。

### 第一版深改 Qwen 为会话级 Agent Profile

需要修改 daemon 协议、会话创建、ACP 子进程池、恢复、持久化、SDK 和 WebShell。范围大于当前交付需求，
也会显著增加跟随 Qwen 上游升级的成本。

### 重写 Java Agent Runtime

Qwen 已提供模型调用、上下文压缩、会话和工具调度；现有 Java 后端已提供确定性分析。重写会复制成熟能力，
扩大验证范围且没有证据表明能提高准确性。

### 删除 OpenCode 集成后直接迁移

会失去已验证基线和回滚路径，也无法进行同模型 A/B Eval。OpenCode 只在 Qwen 产品通过完整门禁后再单独
评估是否退役。

## 后续条件

本 ADR 已由需求方确认。实施继续遵守以下门禁：

1. 算法仓详细设计与实施计划通过评审；
2. Qwen 仓中英文设计保持一致并通过评审；
3. 两仓兼容矩阵、验证用例、回滚条件和许可证处理方式无未决项。
