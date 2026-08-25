# 通用 UT、JSON 结果归档与失败优先分析实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 将固定 Wafer Demo 流程收敛为可分析任意 Maven/JUnit UT 的通用流程，并以外部 Workspace 配置的 JSON 结果、开放式失败证据和按需动态采集支撑 OpenCode 针对具体执行结果进行根因分析。

**架构：** 使用一个无领域语义的 Maven/JUnit Adapter 负责生成测试启动规范；Run 从 `ProjectRegistration.resultJsonDirectory` 获取结果目录并归档本次变化的唯一 JSON；OpenCode Skill 先分析 UT 结果，再按证据缺口选择 Static、CodePath 或 JDWP。现有 Artifact SHA、Plan SHA、normalized Gantt SHA、失败指纹和追加式产物模型保持不变。

**技术栈：** Java 21、Maven、JUnit 5、Jackson、JSON Schema、Node.js `node:test`、OpenCode Tool API、PowerShell 安装脚本。

**设计：** `docs/designs/2026-08-20-generic-ut-json-result-failure-first-design.md`

## 全局约束

1. 严格使用 Red-Green-Refactor；每个行为先有失败测试。
2. 不修改目标项目生产源码，也不要求目标项目添加 Agent 专用依赖。
3. 不增加自动领域知识引擎、Failure Classifier、异常规则引擎或通用 Gantt 领域模型。
4. `resultJsonDirectory` 必须是相对 `moduleRoot` 的安全路径；持久化时使用 `/`。
5. 旧 `project.json` 缺少新字段时必须可读取。
6. JSON 结果扫描不递归，最多 20,000 个目录项，单文件最大 64 MiB。
7. 保留 `GANTT` Artifact、`raw/gantt.json`、`ganttOutcome` 和 normalized Gantt SHA 作为兼容命名。
8. CodePath/JDWP 确认性证据必须通过基线一致性检查。
9. JDWP Collector JAR 继续由 Agent 内置发现，不新增路径或 SHA 配置。
10. OpenCode 不绑定版本号；接口不兼容时返回带命令和 cause 的清晰错误。
11. 输入异常、算法异常和断言失败只作为测试样例，不定义封闭原因枚举；未知失败必须保留原始证据并进入同一分析流程。

## 文件变更地图

| 区域 | 主要文件 | 最终职责 |
|---|---|---|
| 契约 | `ada-contracts/.../ProjectRegistration.java`、`schemas/workspace/project-registration-v1.schema.json` | 持久化可选结果目录 |
| 项目注册 | `case-management/.../ProjectRegistry.java`、`ProjectRegistrationRepository.java` | 幂等创建或更新同一项目配置 |
| CLI | `algorithm-debug-cli/.../CliArguments.java`、`CliCommandExecutor.java` | 接收 `--result-directory` 并保留已有值 |
| Adapter SPI | `adapter-sdk/.../TargetProjectAdapter.java` | 只保留项目检查与 UT 启动职责 |
| 通用 Adapter | `adapters/maven-junit-adapter/**` | 将任意 TargetTest 编译为 Maven Surefire 启动规范 |
| 结果归档 | `debug-harness/.../OutputDirectorySnapshotter.java`、`ScheduleResultCapture.java` | 对配置目录做有界差异与 JSON 校验 |
| 应用服务 | `ada-core/.../RunApplicationService.java`、`CollectionApplicationService.java`、`JdwpCollectionApplicationService.java` | 使用项目配置，支持成功或失败 UT 基线 |
| OpenCode | `integrations/opencode/lib/tool-runtime.mjs`、`tools/algorithm-debug.ts` | 配置透传与可靠完成 Analysis |
| Skill | `skills/algorithm-debug/SKILL.md` | 基于实际 UT 证据分析、按需采集、单次完成 |
| 集成测试 | `integration-tests/src/test/**` | 通用 Fixture 与成功/失败矩阵 |
| 清理 | `adapters/wafer-demo-adapter/**` | 从生产构建删除 Wafer 专用运行时代码 |

---

### 任务 1：记录架构决策并扩展项目注册契约

**文件：**

- 新建：`docs/decisions/ADR-011-generic-maven-junit-json-result-adapter.md`
- 修改：`ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistration.java`
- 修改：`schemas/workspace/project-registration-v1.schema.json`
- 修改：`ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneContractsTest.java`
- 修改：`ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneJsonTest.java`
- 修改：`ada-contracts/README.md`

**接口：**

- 产生：`ProjectRegistration.resultJsonDirectory()`，类型为可空 `String`，值为标准化项目相对路径。
- 兼容：缺失 JSON 属性反序列化为 `null`；已有构造调用使用兼容构造器或显式传 `null`。
- 校验：拒绝绝对路径、空白路径、`.`、`..` 越界段和解析后离开 `moduleRoot` 的路径。

- [ ] **步骤 1：写 ADR-011**

记录三个选择及取舍：结果目录属于外部 ProjectRegistration；Adapter 不包含业务输入/输出语义；第一阶段保留历史 Gantt 命名以避免 Schema 迁移。ADR 状态写为 `Accepted`，引用本设计文档。

- [ ] **步骤 2：先写旧 JSON 与新 JSON 契约失败测试**

测试必须包含以下固定断言：

```java
assertNull(readLegacyRegistrationWithoutResultDirectory().resultJsonDirectory());
assertEquals("output/algorithm-results", readRegistrationWithResultDirectory().resultJsonDirectory());
assertSchemaValid(registrationWith("output/algorithm-results"));
```

同时增加绝对路径 `D:/results`、`/tmp/results` 和越界路径 `../results` 的拒绝测试。

- [ ] **步骤 3：运行契约测试并确认失败**

```powershell
mvn -pl ada-contracts -am -Dtest=WorkspaceControlPlaneContractsTest,WorkspaceControlPlaneJsonTest test
```

预期：因 `resultJsonDirectory` 尚不存在或 Schema 不接受新属性而失败。

- [ ] **步骤 4：实现最小兼容字段和 Schema**

只增加一个可选字符串字段，不创建新的配置层或 Provider 抽象。Schema 使用禁止绝对路径和父目录段的 pattern；Java 构造器执行同等校验。

- [ ] **步骤 5：运行契约测试并确认通过**

```powershell
mvn -pl ada-contracts -am -Dtest=WorkspaceControlPlaneContractsTest,WorkspaceControlPlaneJsonTest test
```

- [ ] **步骤 6：提交检查点**

```powershell
git add docs/decisions/ADR-011-generic-maven-junit-json-result-adapter.md ada-contracts schemas/workspace/project-registration-v1.schema.json
git commit -m "feat: configure project result json directory"
```

### 任务 2：让项目注册幂等保存结果目录

**文件：**

- 修改：`case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistry.java`
- 修改：`case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepository.java`
- 修改：`case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistryTest.java`
- 修改：`case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepositoryTest.java`
- 修改：`algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- 修改：`algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- 修改：`algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliArgumentsTest.java`
- 修改：`algorithm-debug-cli/README.md`

**接口：**

- CLI：`project register --workspace <path> --project <path> [--project-id <id>] [--result-directory <relative-path>]`。
- 行为：同一规范化 `moduleRoot` 再次注册时复用 `projectId`；传入结果目录则原子更新；未传入则保留已有值。
- 产生：后续 Run 可从 `ProjectRegistration` 唯一取得结果目录。

- [ ] **步骤 1：写注册与 CLI 失败测试**

覆盖首次设置、二次更新、自动注册未传参不清空、非法路径拒绝和重启后读取保持。

```java
assertEquals("output/a", register(project, "output/a").registration().resultJsonDirectory());
assertEquals("output/b", register(project, "output/b").registration().resultJsonDirectory());
assertEquals("output/b", register(project, null).registration().resultJsonDirectory());
```

- [ ] **步骤 2：运行测试并确认失败**

```powershell
mvn -pl case-management,algorithm-debug-cli -am -Dtest=ProjectRegistryTest,ProjectRegistrationRepositoryTest,CliArgumentsTest test
```

- [ ] **步骤 3：实现幂等更新和原子保存**

更新必须复用现有 repository 临时文件加原子提交路径，不增加第二份项目配置文件。CLI 错误必须显示无效参数名和值，但不得输出敏感内容。

- [ ] **步骤 4：运行测试并确认通过**

```powershell
mvn -pl case-management,algorithm-debug-cli -am -Dtest=ProjectRegistryTest,ProjectRegistrationRepositoryTest,CliArgumentsTest test
```

- [ ] **步骤 5：提交检查点**

```powershell
git add case-management algorithm-debug-cli
git commit -m "feat: persist result directory during project registration"
```

### 任务 3：增加通用 Maven/JUnit Adapter

**文件：**

- 新建：`adapters/maven-junit-adapter/pom.xml`
- 新建：`adapters/maven-junit-adapter/README.md`
- 新建：`adapters/maven-junit-adapter/src/main/java/org/example/algorithmdebug/adapter/maven/MavenJUnitAdapter.java`
- 新建：`adapters/maven-junit-adapter/src/main/resources/META-INF/services/org.example.algorithmdebug.adapter.TargetProjectAdapter`
- 新建：`adapters/maven-junit-adapter/src/test/java/org/example/algorithmdebug/adapter/maven/MavenJUnitAdapterTest.java`
- 新建：`adapters/maven-junit-adapter/src/test/java/org/example/algorithmdebug/adapter/maven/MavenJUnitAdapterServiceLoaderTest.java`
- 修改：`pom.xml`
- 修改：`algorithm-debug-cli/pom.xml`
- 修改：`integration-tests/pom.xml`

**接口：**

- Adapter id：`maven-junit`。
- 消费：现有 `TargetTest` 与 ProjectRegistration 中的 Maven 路径。
- 产生：现有 `TestLaunchSpec`，Surefire 选择器为 `ClassName` 或 `ClassName#methodName`。
- 不产生：输入定位器、结果目录、Wafer DTO 或领域解析器。

- [ ] **步骤 1：写任意 UT 选择器失败测试**

```java
assertEquals("org.example.FirstTest", selectorFor("org.example.FirstTest", null));
assertEquals("org.example.SecondTest#edgeCase", selectorFor("org.example.SecondTest", "edgeCase"));
```

再验证空类名、包含命令分隔符的非法选择器被结构化拒绝，而未知但合法类名不会在 Adapter 阶段拒绝。

- [ ] **步骤 2：运行 Adapter 测试并确认失败**

```powershell
mvn -pl adapters/maven-junit-adapter -am test
```

- [ ] **步骤 3：实现最小 Adapter 和 ServiceLoader 注册**

只复用现有 Maven 命令、超时和 JVM 参数构造逻辑。不要检测测试源码文件是否存在；不存在的测试交给 Maven/Surefire 返回真实错误。

- [ ] **步骤 4：运行 Adapter 测试并确认通过**

```powershell
mvn -pl adapters/maven-junit-adapter -am test
```

- [ ] **步骤 5：提交检查点**

```powershell
git add adapters/maven-junit-adapter pom.xml algorithm-debug-cli/pom.xml integration-tests/pom.xml
git commit -m "feat: add generic maven junit adapter"
```

### 任务 4：从 Adapter SPI 移除输入和业务结果职责

**文件：**

- 修改：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/TargetProjectAdapter.java`
- 删除：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/InputLocator.java`
- 删除：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/ScheduleResultSource.java`
- 删除：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/ScheduleResultParser.java`
- 删除：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/ScheduleResultSnapshot.java`
- 删除：`adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/ScheduleResultLocator.java`
- 修改：`adapter-sdk/src/test/java/org/example/algorithmdebug/adapter/TargetProjectAdapterContractTest.java`
- 删除：`adapter-sdk/src/test/java/org/example/algorithmdebug/adapter/ScheduleResultSourceTest.java`
- 修改：`adapter-sdk/README.md`

**接口：**

- 保留：`descriptor`、`inspect`、`createLaunchSpec` 的现有领域无关职责。
- 删除：`inputLocator`、`scheduleResultSource`、`scheduleResultParser` 及接口泛型参数。
- 前置：任务 3 的通用 Adapter 已实现保留接口。

- [ ] **步骤 1：先修改契约测试表达最终最小 SPI**

契约测试只允许项目识别和启动规范方法，不再构造输入快照或业务结果 Parser。

- [ ] **步骤 2：运行测试并确认编译失败**

```powershell
mvn -pl adapter-sdk -am test
```

- [ ] **步骤 3：删除 SPI 成员和无调用方的接口类型**

本步骤只改 SDK；核心调用方迁移在任务 6。若 Maven 因下游模块编译失败，使用 `-pl adapter-sdk` 验证 SDK 本身，不通过增加兼容空实现延长旧抽象寿命。

- [ ] **步骤 4：运行 SDK 测试并确认通过**

```powershell
mvn -pl adapter-sdk test
```

- [ ] **步骤 5：提交检查点**

```powershell
git add -A adapter-sdk
git commit -m "refactor: minimize target project adapter spi"
```

### 任务 5：将结果采集改为通用 JSON 差异归档

**文件：**

- 修改：`debug-harness/src/main/java/org/example/algorithmdebug/harness/OutputDirectorySnapshotter.java`
- 修改：`debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleResultCapture.java`
- 修改：`debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunner.java`
- 修改：`debug-harness/src/main/java/org/example/algorithmdebug/harness/CapturedScheduleResult.java`
- 修改：`debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleRunResult.java`
- 修改：`debug-harness/src/test/java/org/example/algorithmdebug/harness/OutputDirectorySnapshotterTest.java`
- 修改：`debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleResultCaptureTest.java`
- 修改：`debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`

**接口：**

- 消费：可选的已解析结果目录、运行前快照、运行后快照。
- 产生：零个或一个经过 UTF-8 JSON 校验的结果 Artifact，以及确定性的缺失/歧义/无效原因。
- 保留：JSON token normalized SHA、64 MiB 文件预算、20,000 项目录预算、稳定等待。

- [ ] **步骤 1：写通用 JSON 结果矩阵失败测试**

```java
assertEquals(PRESENT, capture(oneChangedValidJson()).outcome());
assertEquals(ABSENT, capture(noChangedJson()).outcome());
assertEquals(AMBIGUOUS, capture(twoChangedJson()).outcome());
assertEquals(INVALID, capture(oneChangedInvalidJson()).outcome());
```

测试还必须证明旧文件未变化不会被误归档、非 JSON 文件被忽略、扫描不递归、超预算时停止且不返回部分成功。

- [ ] **步骤 2：运行 Harness 测试并确认失败**

```powershell
mvn -pl debug-harness -am -Dtest=OutputDirectorySnapshotterTest,ScheduleResultCaptureTest,ScheduleProducingTestRunnerTest test
```

- [ ] **步骤 3：实现最小差异与校验逻辑**

复用当前快照和哈希类，不引入 JSONPath、业务 DTO 或递归扫描库。无结果目录时传递显式缺失状态，不创建空文件。

- [ ] **步骤 4：运行 Harness 测试并确认通过**

```powershell
mvn -pl debug-harness -am -Dtest=OutputDirectorySnapshotterTest,ScheduleResultCaptureTest,ScheduleProducingTestRunnerTest test
```

- [ ] **步骤 5：提交检查点**

```powershell
git add debug-harness
git commit -m "refactor: capture configured json result generically"
```

### 任务 6：迁移 Run、CodePath 与 JDWP 到项目配置并保留开放式失败证据

**文件：**

- 修改：`ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java`
- 修改：`ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- 修改：`ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- 修改：`ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java`
- 修改：`ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- 修改：`ada-core/src/test/java/org/example/algorithmdebug/core/JdwpCollectionApplicationServiceTest.java`
- 修改：`ada-core/src/test/java/org/example/algorithmdebug/core/AdapterCatalogTest.java`

**接口：**

- Run 从 `ProjectRegistration.resultJsonDirectory` 安全解析结果路径。
- Run 使用 Adapter 仅获取 `TestLaunchSpec`。
- CodePath/JDWP 复用相同 LaunchSpec 和基线 Run。
- 失败基线至少包含 `targetFailureSha256`；成功基线继续使用结果哈希。
- 不产生输入异常、算法异常或断言失败原因枚举；仅保存进程事实和可选结构化字段。

- [ ] **步骤 1：写无配置、成功和多种失败事实测试**

至少断言：无结果配置不会阻止进程启动；UT 失败且没有 JSON 时原始失败与可提取字段仍归档；未知异常不会返回“不支持的失败类型”；失败指纹一致时 Collection baseline 为 `MATCHED`；不同异常或不同 expected/actual 时为 `MISMATCHED`。

- [ ] **步骤 2：运行核心测试并确认失败**

```powershell
mvn -pl ada-core -am -Dtest=RunApplicationServiceTest,CollectionApplicationServiceTest,JdwpCollectionApplicationServiceTest,AdapterCatalogTest test
```

- [ ] **步骤 3：迁移三个服务并删除 InputLocator 调用**

路径解析必须执行 `moduleRoot.resolve(relative).normalize()`，并验证结果仍以规范化 `moduleRoot` 开头。UT 失败和结果缺失分别记录，不允许后者覆盖前者。

- [ ] **步骤 4：验证失败指纹基线无需扩展契约**

先用现有 `RunResultFingerprint` 和 `CollectionBaselineCheck` 完成测试。只有测试证明现有契约无法表达失败基线时，才做最小兼容扩展；不得创建第二套 Baseline 模型。

- [ ] **步骤 5：运行核心测试并确认通过**

```powershell
mvn -pl ada-core -am -Dtest=RunApplicationServiceTest,CollectionApplicationServiceTest,JdwpCollectionApplicationServiceTest,AdapterCatalogTest test
```

- [ ] **步骤 6：提交检查点**

```powershell
git add ada-core
git commit -m "refactor: drive run and collectors from project configuration"
```

### 任务 7：删除 Wafer Demo 运行时代码并保留知识参考

**文件：**

- 删除：`adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoAdapter.java`
- 删除：`adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoCaseCatalog.java`
- 删除：`adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferInputLocator.java`
- 删除：`adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferScheduleSnapshot.java`
- 删除：`adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferScheduleResultParser.java`
- 删除：`adapters/wafer-demo-adapter/src/main/resources/META-INF/services/org.example.algorithmdebug.adapter.TargetProjectAdapter`
- 删除：`adapters/wafer-demo-adapter/src/test/**`
- 删除：`adapters/wafer-demo-adapter/pom.xml`
- 删除：`adapters/wafer-demo-adapter/README.md`
- 修改：`pom.xml`
- 修改：`algorithm-debug-cli/pom.xml`
- 修改：`integration-tests/pom.xml`
- 保留并修改：`skills/algorithm-debug/references/wafer-demo-v1.md`

**接口：**

- ServiceLoader 中只保留 `maven-junit` Adapter。
- Wafer 知识文件只描述字段与领域假设，不包含固定本机路径，不参与是否支持 UT 的判断。

- [ ] **步骤 1：先写“无白名单”回归测试**

在 `AdapterCatalogTest` 中加载通用 Adapter，并用两个不同测试类选择器确认都能创建 LaunchSpec；断言不存在 `wafer-demo` Adapter id。

- [ ] **步骤 2：运行测试并确认旧 Adapter 仍被加载**

```powershell
mvn -pl ada-core -am -Dtest=AdapterCatalogTest test
```

- [ ] **步骤 3：删除模块和所有构建依赖**

删除整个生产模块，而不是保留 deprecated 空壳。将仍有价值的 Wafer 语义说明放在知识 Markdown，不搬运硬编码路径。

- [ ] **步骤 4：运行 Adapter 与核心测试**

```powershell
mvn -pl adapters/maven-junit-adapter,ada-core,algorithm-debug-cli -am test
```

- [ ] **步骤 5：提交检查点**

```powershell
git add -A adapters pom.xml algorithm-debug-cli/pom.xml integration-tests/pom.xml skills/algorithm-debug/references/wafer-demo-v1.md
git commit -m "refactor: retire wafer demo runtime adapter"
```

### 任务 8：将基于实际 UT 证据的开放分析策略写入 Skill

**文件：**

- 修改：`skills/algorithm-debug/SKILL.md`
- 修改：`docs/algorithm-debug-workflow-and-artifacts.md`
- 修改：`README.md`

**接口：**

- 输入：用户问题、目标 UT、`case_run` 产物。
- 决策顺序：Agent/工具执行事实 → UT 客观结果 → 原始失败事实与结果 JSON → 证据充分性 → 最小下一工具。
- 输出：带 Claim classification 和 ArtifactReference 的结论。

- [ ] **步骤 1：写清五条强制行为**

Skill 必须明确写出：先 Run；读取本次实际执行事实；证据足够即停止；一次只选择一个最有价值的下一工具；不因 `analysis_complete` 参数错误新建 Analysis。

- [ ] **步骤 2：写开放式失败证据检查表与常见示例**

检查表只要求查看退出码、是否执行到目标测试、测试计数、异常链、栈、expected/actual、超时、stdout/stderr 和结果 Artifact 状态。输入/Fixture 异常、算法异常、断言失败、UT 成功但 JSON 缺失和 Collector 失败作为示例说明，不得写成 `switch`、穷举列表或“不匹配即不支持”的流程。

- [ ] **步骤 3：删除固定完整流水线措辞**

移除要求每次都运行 Static、CodePath 和 JDWP 的描述，也移除先按异常类型分类再选择工具的描述；保留动态证据必须通过基线匹配的硬约束。

- [ ] **步骤 4：人工契约检查**

逐项确认 Skill 没有固定 Wafer 类名、输入路径、结果绝对路径、OpenCode 版本号或“必须复制输入文件”的要求。

- [ ] **步骤 5：提交检查点**

```powershell
git add skills/algorithm-debug/SKILL.md docs/algorithm-debug-workflow-and-artifacts.md README.md
git commit -m "docs: define failure first evidence workflow"
```

### 任务 9：修复 OpenCode 项目准备与 `analysis_complete`

**文件：**

- 修改：`integrations/opencode/lib/tool-runtime.mjs`
- 修改：`integrations/opencode/tools/algorithm-debug.ts`
- 修改：`integrations/opencode/test/tool-runtime.test.mjs`
- 修改：`scripts/install-opencode.ps1`

**接口：**

- 自动 `project register` 未给 `--result-directory` 时保留 Workspace 已有配置。
- `analysis_complete` 成功时返回最终 ArtifactReference。
- 参数错误返回具体字段路径、Java 错误码、CLI stderr 摘要和 cause。
- 同一 Analysis 成功后禁止重复完成；失败后允许在原 Analysis 修正重试一次。

- [ ] **步骤 1：写 Node 失败测试**

```javascript
assert.match(result.error.message, /findings\[0\]\.artifactReferences/);
assert.equal(secondAttempt.analysisId, firstAttempt.analysisId);
assert.equal(successfulResultsForQuestion.length, 1);
```

覆盖中文回答、长但合法回答、非法 ArtifactReference、非法 classification、CLI 非零退出、已完成 Analysis 和未知 OpenCode 版本。

- [ ] **步骤 2：运行测试并确认失败**

```powershell
node --test integrations/opencode/test/*.test.mjs
```

- [ ] **步骤 3：实现参数对齐和错误透传**

TypeScript schema 与 Java 约束使用同一长度和枚举值。Runtime 保留 CLI stderr 的结构化错误，不把所有错误改写为 `CLI_INVALID_ARGUMENTS`。不得在 Runtime 内自动创建新 Analysis。

- [ ] **步骤 4：移除安装脚本的 OpenCode 版本门禁**

`-Mode Check` 只验证命令存在、Tool 文件可加载和必要命令可调用。接口不兼容时输出实际失败命令、检测到的版本和修复建议，不因版本号不相等提前拒绝。

- [ ] **步骤 5：运行 Node 测试并确认通过**

```powershell
node --test integrations/opencode/test/*.test.mjs
```

- [ ] **步骤 6：提交检查点**

```powershell
git add integrations/opencode scripts/install-opencode.ps1
git commit -m "fix: make opencode completion deterministic and version tolerant"
```

### 任务 10：建立通用端到端 Fixture 与开放失败验收矩阵

**文件：**

- 新建：`integration-tests/src/test/java/org/example/algorithmdebug/integration/GenericMavenJUnitWorkflowTest.java`
- 新建：`integration-tests/src/test/resources/fixtures/generic-maven-junit/pom.xml`
- 新建：`integration-tests/src/test/resources/fixtures/generic-maven-junit/src/main/java/org/example/fixture/DemoAlgorithm.java`
- 新建：`integration-tests/src/test/resources/fixtures/generic-maven-junit/src/test/java/org/example/fixture/DemoAlgorithmTest.java`
- 删除：`integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java`
- 修改：`docs/architecture/tool-validation-baseline.md`
- 修改：`docs/plans/algorithm-debug-agent-development-plan.md`

**接口：**

- Fixture 通过测试参数选择成功、输入异常、算法异常、断言失败、多结果和无效结果场景。
- Fixture 只写临时测试目录，不能写仓库工作树或依赖 `D:\javacode\hellomvn`。
- 验收读取真实 Workspace 产物并校验 Case、Run、Trace、Evidence、Analysis 的引用关系。
- Fixture 场景用于回归常见行为，不形成生产代码失败类型枚举。

- [ ] **步骤 1：创建最小 Fixture 和第一个失败 E2E 测试**

Fixture 的算法只执行确定性整数处理并写 JSON，不引入业务框架。第一个测试指定一个非 Wafer 类名并断言 Run 到达 `TARGET_PASSED` 和 `ganttOutcome=PRESENT`。

- [ ] **步骤 2：运行集成测试并确认失败**

```powershell
mvn -pl integration-tests -am -Dtest=GenericMavenJUnitWorkflowTest test
```

- [ ] **步骤 3：增加完整失败矩阵**

测试方法必须分别覆盖：

```text
passesAndArchivesOneJson
passesWithoutConfiguredResultDirectory
failsDuringInputPreparationWithoutJson
failsWithAlgorithmNullPointer
failsWithArrayIndexOutOfBounds
failsWithExplicitAlgorithmException
failsAssertionWithExpectedAndActual
rejectsAmbiguousChangedJsonResults
rejectsInvalidJsonResult
matchesCodePathBaselineByTargetFailureFingerprint
matchesJdwpBaselineByTargetFailureFingerprint
keepsCollectorFailureSeparateFromTargetFailure
archivesUnknownFailureWithoutClassifier
```

`archivesUnknownFailureWithoutClassifier` 使用自定义 RuntimeException 或非标准断言文本，验收原始 Artifact 仍可读取、Agent 不返回“不支持的失败类型”，大模型后续仍可根据证据继续分析。

- [ ] **步骤 4：运行集成测试并确认通过**

```powershell
mvn -pl integration-tests -am -Dtest=GenericMavenJUnitWorkflowTest test
```

- [ ] **步骤 5：更新基线文档和阶段计划**

文档必须把“固定 Wafer Demo”改为“通用 Maven/JUnit UT”，并记录每个场景对应的自动化测试方法和产物类型。

- [ ] **步骤 6：提交检查点**

```powershell
git add -A integration-tests docs/architecture/tool-validation-baseline.md docs/plans/algorithm-debug-agent-development-plan.md
git commit -m "test: cover generic junit failure first workflow"
```

### 任务 11：执行本机 OpenCode 验收与全仓回归

**文件：**

- 修改：`docs/algorithm-debug-workflow-and-artifacts.md`
- 修改：`README.md`
- 产物：外部 Workspace 中新增 Case/Run/Analysis，不提交到 Git。

**验收前置：**

- Agent 仓库已完成任务 1 至任务 10。
- `D:\javacode\hellomvn` 的目标 UT 可独立由 Maven 执行。
- 项目结果目录配置为 `output/algorithm-results`。

- [ ] **步骤 1：执行受影响模块回归**

```powershell
mvn -pl ada-contracts,case-management,adapter-sdk,adapters/maven-junit-adapter,debug-harness,ada-core,algorithm-debug-cli,integration-tests -am test
node --test integrations/opencode/test/*.test.mjs
```

预期：所有测试通过，无 `wafer-demo-adapter` 构建依赖。

- [ ] **步骤 2：执行根项目回归**

```powershell
mvn test
```

预期：全部模块通过；空的未来模块不得因本次工作新增推测性 API。

- [ ] **步骤 3：安装并检查 OpenCode 集成**

```powershell
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

预期：不因 OpenCode 版本号变化失败；输出 Tool 加载和 CLI 能力检查结果。

- [ ] **步骤 4：一次性配置真实 Demo 结果目录**

```powershell
java -jar algorithm-debug-cli\target\algorithm-debug-cli.jar project register --workspace "$env:LOCALAPPDATA\algorithm-debug-agent\workspace" --project "D:\javacode\hellomvn" --result-directory "output/algorithm-results"
```

若实际可执行 JAR 名称由 Maven shade 配置生成，以 `scripts/install-opencode.ps1` 安装后的同一 CLI 入口执行等价命令，不写死到 OpenCode Tool 源码。

- [ ] **步骤 5：在 OpenCode 执行一个通过 UT 问题**

验收：Run 通过；stdout/Surefire/JSON 归档；回答只运行问题需要的工具；AnalysisResult 只有一个。

- [ ] **步骤 6：在受控 Fixture 执行一个失败 UT 问题**

使用集成 Fixture 或专门测试副本制造断言失败，不修改 `hellomvn` 的长期源码。验收：expected/actual、失败栈和失败指纹归档；需要动态证据时基线 `MATCHED`；回答明确区分测试预期错误与算法错误。

- [ ] **步骤 7：检查外部 Workspace 产物关系**

确认每个问题只有一个 Case 目标、至少一个 Run、按需 Collection，以及一个成功 AnalysisResult；所有 ArtifactReference SHA 可由统一校验机制验证；历史产物未被覆盖。

- [ ] **步骤 8：同步最终使用文档**

README 给出四步最短路径：安装、项目注册并配置结果目录、在项目目录启动 OpenCode、指定 UT 提问。工作流文档给出成功、异常、断言失败三个用户可感知示例。

- [ ] **步骤 9：最终提交检查点**

```powershell
git add README.md docs/algorithm-debug-workflow-and-artifacts.md
git commit -m "docs: publish generic ut analysis workflow"
```

## 长任务执行顺序与检查点

1. 先完成任务 1 至任务 3，得到“配置可保存 + 任意 UT 可生成启动规范”的第一个可审查增量。
2. 再完成任务 4 至任务 7，一次性迁移 SPI 和核心调用方并删除 Wafer 运行时代码，避免长期双轨。
3. 完成任务 8 至任务 9，收敛大模型交互策略和 OpenCode 完成语义。
4. 完成任务 10，用仓库内 Fixture 覆盖所有主路径。
5. 最后执行任务 11 的真实 OpenCode 验收和全仓回归。

每个检查点若失败，只修复该任务引入的行为；不得顺手实现 knowledge-engine、evaluation 评分平台、explanation-reporter、gantt-analysis 或 Failure Classifier。Evidence Sufficiency 保留为 Skill 的显式判断规则和现有确定性校验，不恢复独立的过度设计模块。

## 最终验收清单

- [ ] 任意合法 Maven/JUnit 类或方法选择器不再被 Adapter 白名单拒绝。
- [ ] UT 自己管理输入，Agent 不需要 InputLocator 或输入路径配置。
- [ ] stdout、stderr、Surefire、退出码和失败摘要始终归档。
- [ ] 配置目录中本次唯一变化的 JSON 被校验、归档并计算 SHA。
- [ ] 未配置、未产生、多结果、无效 JSON 都有确定性且不猜测的行为。
- [ ] 任意 UT 失败的原始证据均被保留；常见字段按存在性提取，算法结果缺失不覆盖主失败。
- [ ] 未列举失败不会因缺少原因枚举而被拒绝，大模型仍可结合实际证据继续分析。
- [ ] 失败 UT 可用失败指纹完成 CodePath/JDWP 基线匹配。
- [ ] Static、CodePath、JDWP 由证据缺口驱动，不再每次全部运行。
- [ ] Artifact SHA、Plan SHA、normalized Gantt SHA 和 JDWP 内置 Collector 保持有效。
- [ ] 一次用户问题只产生一个最终 AnalysisResult。
- [ ] OpenCode 版本号不作为安装或运行门禁。
- [ ] Wafer 专用运行时代码已删除，Wafer 知识参考仍可选使用。
- [ ] 自动化测试不依赖外部 Demo 仓库或开发机绝对路径。
- [ ] 所有受影响模块测试、Node 测试和根 Maven 测试通过。
