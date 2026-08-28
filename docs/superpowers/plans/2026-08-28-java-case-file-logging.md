# Java Case 文件日志实施计划

> 设计依据：`docs/designs/2026-08-28-java-case-file-logging-design.md`

## 目标

为 Java Agent CLI 增加 Case 级文件日志和 bootstrap 失败日志，覆盖算法输入、UT、静态分析、CodePath、JDWP、Evidence 关键阶段，同时保持 ToolResponse、证据语义和离线安装方式不变。

## 实施顺序

### 1. 日志基础设施与契约测试

新增日志事件、上下文、格式化、脱敏、异常渲染、路径解析、缓冲和文件追加组件。测试覆盖：

- Case/bootstrap 路径与目录逃逸拒绝。
- `yyyy-MM-dd` 文件名和带时区时间戳。
- 英文消息、字段稳定顺序和控制字符转义。
- Windows/Unix 绝对路径、凭据字段和命令参数脱敏。
- 完整异常 cause 链。
- 首次事件延迟创建、追加不覆盖、日志失败隔离。
- Case Open 缓冲成功/失败刷新。

### 2. CLI 和 Case 生命周期接入

修改 `AdaMain`、`CliCommandExecutor`、`CaseApplicationService` 和 `CaseWorkspaceAuditor`：

- CLI 边界建立日志上下文并记录未处理异常。
- 命令分发和完成记录稳定事件。
- Case Open 先缓冲，拿到 `caseId` 后刷新。
- Case Inspect、Analysis Complete、Artifact Read 和审计记录开始/完成。
- stdout/stderr 不增加普通日志。

### 3. 算法输入、UT 与进程接入

修改输入定位、输入归档、UT 执行、Maven 执行、外部进程和输出捕获组件：

- 记录输入候选数量、定位/复制/复用结论，不记录输入正文和绝对路径。
- 记录 Run ID、进程退出码、超时、结果分类和 Gantt 捕获状态。
- 目标 UT stdout/stderr 继续归档到 Run Raw 目录。
- 仅截断和读取失败写 WARN。

### 4. 静态分析、CodePath、JDWP 与 Evidence 接入

修改静态分析、Plan、Collection、Coordinator、Normalizer、Validator、Evidence Builder/Evaluator：

- 记录 Plan、Collection 和 Evidence 关联 ID。
- 记录 Launcher/Collector/目标 JVM 生命周期。
- 记录 Raw 归档、规范化、校验、基线和充分性状态。
- 不记录 Trace、局部变量、对象字段或算法语义。

### 5. 配置、文档与安装同步

- 保持 `dfxDirectory` 为 bootstrap 日志根配置，不增加路径命令参数。
- 更新架构、能力清单、README/安装手册中的日志位置和查看方式。
- 明确 Java 日志与 `interaction.jsonl`、Run Raw、Collection Raw 的边界。
- 不增加目标算法项目 Maven 依赖。

### 6. 自动化验证

执行：

```powershell
mvn test
npm test --prefix integrations/opencode
```

关键断言：

- Java 单元/契约/集成测试通过。
- OpenCode Adapter 输出仍是合法 ToolResponse。
- 安装检查不受日志功能影响。
- 日志不存在中文、敏感正文和未脱敏绝对路径。

### 7. 端到端与 Workspace 审计

使用当前算法 Demo 依次验证：

1. 正常成功 UT。
2. 目标 UT 不存在。
3. 算法逻辑异常。
4. 断言失败。
5. CodePath 动态采集。
6. JDWP 动态采集。
7. Case 创建失败或不可写日志路径。

每个用例检查：

- 日志路由、事件顺序、英文内容、异常堆栈和脱敏。
- ToolResponse JSON、Run/Collection/Evidence 产物完整。
- `case audit` 通过。
- Case 中不存在空目录、空文件或无消费方的文件。
- CodePath/JDWP 必须是本次输入捕获之后的新 Collection，不能复用历史结果代替验收。

## 完成定义

- 设计、实现、测试和使用文档一致。
- 所有关键阶段可由 Case 日志复盘，但日志不参与算法证据判定。
- 任一日志故障不会改变 Agent 原有业务结果。
- 根构建、OpenCode 测试和七类端到端检查全部完成；任何缺失产物都已定位原因并修复。

