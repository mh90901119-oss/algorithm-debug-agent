# 公司环境源码安装与双 JDK 实施计划

> 对应设计：`docs/designs/2026-08-25-company-source-install-dual-jdk-design.md`

## 实施原则

- 交付普通 GitHub 源码仓，不制作 runtime-only 离线发行包。
- 不改变当前本地默认行为。
- 不修改系统 `JAVA_HOME`、`PATH` 或注册表。
- 公司算法源码始终位于公司算法仓，OpenCode 可按用户请求直接修改。
- 每项行为变更先补失败测试，再做最小实现。
- 实施结束执行根项目测试、安装检查、JDWP loopback 和真实 OpenCode 端到端验收。

## Task 1：归档固定 CodePathTracer Maven 制品

### 涉及文件

- 新增 `third-party/code-path-tracer/LICENSE`
- 新增 `third-party/code-path-tracer/README.md`
- 新增 `third-party/maven-repository/io/github/takahirom/codepathtracer/code-path-tracer/0.1.0-SNAPSHOT/code-path-tracer-0.1.0-SNAPSHOT.jar`
- 新增 `third-party/maven-repository/io/github/takahirom/codepathtracer/code-path-tracer/0.1.0-SNAPSHOT/code-path-tracer-0.1.0-SNAPSHOT-sources.jar`
- 新增 `third-party/maven-repository/io/github/takahirom/codepathtracer/code-path-tracer/0.1.0-SNAPSHOT/code-path-tracer-0.1.0-SNAPSHOT.pom`
- 修改根 `pom.xml`
- 修改 `.gitignore`（仅在现有规则会误排除固定制品时）

### 实施

1. 从当前已审计的本地 CodePathTracer 制品复制 JAR、Sources、POM 和 Apache-2.0 License。
2. README 记录来源、版本、用途、许可证和升级步骤。
3. 根 POM 增加基于 `${maven.multiModuleProjectDirectory}` 的仓库内 `file:` Repository。
4. Snapshot 更新策略设为 `never`，禁止网络查找同坐标的新快照。
5. 保持 CodePath Launcher 原依赖坐标不变。

### 测试

- 新增 POM/制品完整性测试：验证固定文件、坐标和许可证存在。
- 在隔离 Maven Local Repository 下构建 CodePath Launcher，确认不会依赖用户原有 `~/.m2` 中的 CodePathTracer。

## Task 2：扩展统一路径与运行时配置

### 涉及文件

- 修改当前 Agent Settings Schema 和默认配置
- 修改配置解析器及其单元测试
- 修改 `README.md` 和配置说明文档

### 实施

1. 新增 `agentJavaHome`、`targetJavaHome`、`mavenExecutable`。
2. 三个字段默认保留为空并写入默认配置模板。
3. 空值保持现有回退规则。
4. 非空路径执行确定性校验，并返回结构化英文错误。
5. 安装脚本打印最终解析出的 Agent Java、Target Java、Maven、Workspace、算法输出和 OpenCode 配置路径。

### 测试

- 空字段兼容测试。
- Windows 绝对路径解析测试。
- Java Home 缺少 `bin/java.exe` 的失败测试。
- Maven 可执行文件不存在的失败测试。

## Task 3：新增源码构建脚本

### 涉及文件

- 新增 `scripts/build-agent.ps1`
- 新增脚本测试或可替换进程执行器测试
- 修改 `README.md`

### 实施

1. 读取统一配置，不接收 Java/Maven 路径参数。
2. 检查 Agent Java 主版本至少为 21。
3. 仅在脚本进程内设置构建环境。
4. 使用配置 Maven 执行 `mvn -Pcodepath-launcher package`。
5. 在 `finally` 恢复进入脚本前的环境变量。
6. 输出各模块产物位置和下一步安装命令。

### 测试

- JDK 版本不足时失败。
- Maven 构建失败时保留原退出码。
- 构建后调用进程的环境变量未被永久修改。

## Task 4：分离 Agent Java 与 Target Java

### 涉及文件

- 修改 `bin/ada.cmd`
- 修改 Java 子进程启动配置和 Java 可执行文件解析器
- 修改 CLI、CodePath、JDWP 相关测试

### 实施

1. `bin/ada.cmd` 从统一配置或安装生成的稳定配置读取 `agentJavaHome`。
2. Agent CLI 始终使用 Agent Java。
3. JDWP Collector 始终使用 Agent Java。
4. 目标 Maven/JUnit 和 CodePath 目标 JVM始终使用 Target Java。
5. 禁止再用单一 `System.getProperty("java.home")` 同时承担两种角色。
6. ToolResponse 返回实际使用的 Java 可执行文件和主版本，便于 DFX 追踪。

### 测试

- Agent Java 与 Target Java 不同时选择正确。
- 仅配置 Agent Java、仅配置 Target Java和都为空时的回退测试。
- 子进程命令中无开发机绝对路径。

## Task 5：让目标 Maven/JUnit 使用 JDK 17

### 涉及文件

- 修改 UT Runner 的进程环境构造
- 修改外部 JUnit Launcher 启动逻辑
- 修改进程和 ToolResponse 契约测试

### 实施

1. Maven 子进程环境的 `JAVA_HOME` 指向 Target Java。
2. 仅在该子进程 `PATH` 前置 Target Java 的 `bin`。
3. 不修改父进程和系统环境。
4. 归档目标 JVM 版本、Maven 路径、命令和退出码。
5. 工具失败与 UT 失败继续分开表示。

### 测试

- 假进程执行器断言子进程环境。
- JDK 17 目标 UT 的集成测试。
- Maven 启动失败和 UT 启动失败的结构化错误测试。

## Task 6：CodePath Launcher 兼容 Java 17

### 涉及文件

- 修改 `tools/code-path-tracer-junit-launcher/pom.xml`
- 新增 Launcher 本地最小 Plan DTO
- 修改 `CodePathPlanReader.java`
- 修改 `PlannedTraceEventGenerator.java`
- 修改 `ExternalJUnitTraceLauncher.java`
- 新增 Plan JSON 契约兼容测试

### 实施

1. Launcher 编译目标改为 `release 17`。
2. 移除 Launcher 对 Java 21 `ada-contracts` 二进制的运行时依赖。
3. 新增只含实际所需字段的 `LauncherCodePathPlan`。
4. Agent 仍使用原 Contracts 生成 Plan JSON。
5. 用固定契约样例证明两端对同一 JSON 的读取一致。

### 测试

- 用 JDK 17 执行 Launcher 的版本兼容测试。
- Plan 缺字段、未知字段、预算字段和测试选择器测试。
- 真实 Demo CodePath 采集回归测试。

## Task 7：提交真实 JDWP loopback 验证脚本

### 涉及文件

- 新增 `scripts/verify-jdwp-loopback.ps1`
- 新增 `scripts/fixtures/jdwp-loopback/JdwpLoopbackProbe.java`
- 新增 `scripts/fixtures/jdwp-loopback/collector-plan.template.json`
- 新增脚本说明和故障排查文档

### 实施

1. 从统一配置读取 Agent Java 和 Target Java。
2. 动态选择 `127.0.0.1` 可用端口。
3. 用 Target Java 编译并启动 Probe。
4. 用 Agent Java 启动真实 Collector。
5. 采集局部变量 `marker=42`。
6. 验证 Raw Trace、Manifest、退出码和 Probe 恢复。
7. 所有临时文件写入 Agent Workspace 的验证 Case，结束后不遗留空目录。
8. 超时清理自身创建的全部进程。
9. 输出 PASS，或输出端口监听、attach、断点、采集、恢复中的准确失败阶段。

### 测试

- 正常 loopback attach。
- 端口占用、Collector 启动失败、attach 超时和断点未命中的失败测试。
- 检查失败运行仍有 manifest 和英文错误日志。

## Task 8：保证当前本地使用不回归

### 涉及文件

- 修改安装器测试、Launcher 测试和文档
- 不修改当前用户机器的全局 Java 环境

### 实施

1. 未配置新字段时走当前 Java/Maven 回退逻辑。
2. 保留现有 `install-opencode.ps1 -Mode Install/Check` 使用方式。
3. 保留当前 Workspace 和算法输出路径配置。
4. 安装器明确打印路径和 Java 角色，不自动改系统配置。

### 验证

- 根项目 `mvn test`。
- `build-agent.ps1` 本地构建。
- 安装器 Install 和 Check。
- 当前 Demo 成功 UT、算法异常、断言失败、CodePath 和 JDWP 用例。
- 每个 Case 检查 Workspace 必需文件、无空文件和无无意义空目录。
- 分析每个端到端 Case 的 DFX 日志和退出状态。

## Task 9：公司算法仓使用与验收文档

### 涉及文件

- 修改根 `README.md`
- 新增或修改公司环境安装说明
- 修改 OpenCode Skill 中的环境失败提示
- 修改 Eval 使用说明

### 实施

1. 写清普通 GitHub 源码 ZIP 安装步骤。
2. 写清 JDK 21 只需解压、无需系统环境变量。
3. 写清 JDK 17 用于公司算法 UT，JDK 21 用于 Agent。
4. 写清公司算法仓与 Agent 仓是两个目录。
5. 写清必须从公司算法模块目录启动 OpenCode。
6. 写清 OpenCode 可按用户要求修改算法源码或 UT；Agent 工具负责证据采集和归档。
7. 写清 JDWP 验证失败时的排查顺序和安全软件边界。
8. 写清公司 Maven 镜像负责主流依赖，CodePathTracer 来自 Agent 仓固定制品。

### 端到端验收

1. 使用普通源码 ZIP 解压到新目录。
2. 使用独立 Maven Local Repository，证明不依赖开发机 CodePathTracer 缓存。
3. 配置解压版 JDK 21 和现有 JDK 17。
4. 构建并安装到 OpenCode。
5. 执行 JDWP loopback 验证。
6. 从算法 Demo 模块启动 OpenCode。
7. 运行成功 UT，确认 Gantt 归档。
8. 运行业务异常 UT，确认异常证据归档。
9. 运行断言失败 UT，确认断言证据归档。
10. 执行 CodePath 和 JDWP 采集。
11. 要求 OpenCode 对 Demo 算法做一个隔离的测试性修改，再运行 UT，证明公司算法仓可编辑且 Agent 工具继续有效。
12. 审计所有 Case 的 Workspace 文件和 DFX 日志；发现缺失、空文件或孤立目录时必须定位根因并修复后重跑。

## 完成定义

- 普通 GitHub 源码 ZIP 包含构建所需的 CodePathTracer 固定制品和 JDWP 验证脚本。
- 公司电脑无需联网访问 Maven Central，也无需修改系统 JDK 配置。
- Agent 使用 JDK 21，公司算法和 UT 使用 JDK 17。
- OpenCode 能在公司算法仓中调用 Agent，也能按用户要求修改代码。
- 当前本地安装和 Demo 端到端流程无回归。
- README、设计、测试、脚本输出和实际行为一致。
