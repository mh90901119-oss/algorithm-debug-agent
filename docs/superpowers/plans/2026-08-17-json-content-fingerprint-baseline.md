# JSON Content Fingerprint Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每次目标 UT Run 生成通用 JSON 内容指纹或目标失败指纹，并在同一 Case 的多轮分析中可靠返回 `MATCHED`、`CHANGED`、`NOT_COMPARED` 或 `INCOMPARABLE`。

**Architecture:** Adapter 只负责识别结果源和解析合法目标 JSON；Debug Harness 对已捕获文件计算原始 SHA-256 与忽略 JSON 格式空白的 Token SHA-256。Case Management 追加保存 Run 指纹和 Context 首次复现参考，Core 组合比较结论；不实现业务投影、字段级 Diff 或 `gantt-analysis` 生产代码。

**Tech Stack:** Java 21、Maven、JUnit 5、Jackson Core 2.17.2、Jackson Databind 2.17.2、JSON Schema Draft 2020-12。

**Approved Design:** `docs/designs/2026-08-17-json-content-fingerprint-baseline-design.md` 与 `docs/decisions/ADR-008-json-content-fingerprint-baseline.md`。

## Global Constraints

- 不修改目标算法生产源码、原始 UT 或目标模块 `pom.xml`。
- 每次 `run execute` 只运行一次 UT；Baseline 比较不能触发隐式重跑。
- `rawSha256` 只校验字节完整性；`normalizedJsonSha256` 只忽略 JSON Token 之间的格式空白。
- 字符串内部空格、对象成员顺序、数组顺序、字段值和数字 Token 文本必须参与内容指纹。
- 成功/失败指纹、Run、Context reference 和 RunOutcome 全部追加保存，不覆盖历史产物。
- 只有 Agent 失败且没有可信 Gantt/目标失败时，不建立 reproduction reference。
- 变化只证明结果不同；确定性代码不解释业务字段、算法错误或根因。
- 不新增 Projector、条目 DTO、Diff DTO、数据库、复杂状态机或 `gantt-analysis` 依赖。
- 所有行为变更遵循 Red-Green-Refactor；每个 Task 后执行代码审计、修复缺陷、运行受影响 UT 并独立提交。
- 执行时保留用户现有未提交 OpenCode、Schema 和文档改动；只精确暂存当前 Task 文件。

---

### Task 1: 审计并收尾现有 Baseline 命名与 Case 目录迁移

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BaselineStabilityState.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseLifecycleState.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InquiryId.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/TurnId.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BaselineVerification.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/BaselineVerificationTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/BaselineStabilityService.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseWorkspace.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/BaselineStabilityServiceTest.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseWorkspaceTest.java`
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java`
- Modify: `schemas/execution/baseline-manifest-v2.schema.json`

**Interfaces:**
- Consumes: 现有 `BaselineVerification` 1.0 JSON 枚举值和旧 `CaseWorkspace` 测试夹具。
- Produces: 只包含 `BASELINE_CANDIDATE`、`BASELINE_STABLE`、`BASELINE_UNSTABLE` 的 `BaselineStabilityState`；与正式 Case Archive 一致的 `contexts/analyses/runs/evidence` 目录。

- [ ] **Step 1: 审计当前差异是否只做命名收敛和路径约束修复**

Run:

```powershell
git diff -- ada-contracts case-management integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java schemas/execution/baseline-manifest-v2.schema.json
rg -n "CaseLifecycleState|InquiryId|TurnId" ada-contracts case-management integration-tests
```

Expected: 生产引用全部迁移到 `BaselineStabilityState`；删除的两个对话 ID 没有剩余 Java 引用；Schema 仍为版本 2.0。

- [ ] **Step 2: 补齐枚举 JSON 兼容和目录不覆盖回归断言**

测试必须包含：

```java
assertEquals("\"BASELINE_STABLE\"",
        objectMapper.writeValueAsString(BaselineStabilityState.BASELINE_STABLE));
assertTrue(Files.isDirectory(workspace.caseRoot().resolve("contexts")));
assertTrue(Files.isDirectory(workspace.caseRoot().resolve("analyses")));
assertTrue(Files.isDirectory(workspace.caseRoot().resolve("evidence")));
assertTrue(Files.notExists(workspace.caseRoot().resolve("inquiries")));
```

- [ ] **Step 3: 运行受影响测试并确认没有依赖旧类型**

Run:

```powershell
mvn -pl ada-contracts,case-management,integration-tests -am test
rg -n "CaseLifecycleState|InquiryId|TurnId" --glob "*.java"
```

Expected: Maven exit 0；第二条命令无输出。

- [ ] **Step 4: 执行代码审计并独立提交**

检查枚举 JSON 兼容、路径越界、create-new 行为、删除类型是否仍出现在 Schema/公共 API。只修复本 Task 发现的问题。

```powershell
git add -- ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BaselineStabilityState.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BaselineVerification.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseLifecycleState.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InquiryId.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/TurnId.java ada-contracts/src/test/java/org/example/algorithmdebug/contracts/BaselineVerificationTest.java case-management/src/main/java/org/example/algorithmdebug/casecore/BaselineStabilityService.java case-management/src/main/java/org/example/algorithmdebug/casecore/CaseWorkspace.java case-management/src/test/java/org/example/algorithmdebug/casecore/BaselineStabilityServiceTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/CaseWorkspaceTest.java integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java schemas/execution/baseline-manifest-v2.schema.json
git diff --cached --check
git commit -m "refactor: separate baseline stability state"
```

---

### Task 2: 实现流式 JSON Token 内容指纹

**Files:**
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/JsonTokenContentHasher.java`
- Create: `debug-harness/src/test/java/org/example/algorithmdebug/harness/JsonTokenContentHasherTest.java`
- Modify: `debug-harness/pom.xml`

**Interfaces:**
- Consumes: 已复制到 Run 目录的合法 JSON `Path`。
- Produces: `String JsonTokenContentHasher.sha256(Path jsonPath) throws HarnessException`，返回 64 位小写 SHA-256。

- [ ] **Step 1: 添加 Jackson Core 依赖并写格式空白失败测试**

`debug-harness/pom.xml` 增加父 POM 已锁定的依赖：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-core</artifactId>
</dependency>
```

首个测试：

```java
@Test
void shouldIgnoreFormattingWhitespace(@TempDir Path directory) throws Exception {
    Path compact = Files.writeString(directory.resolve("compact.json"),
            "{\"name\":\"A B\",\"values\":[1,2]}");
    Path formatted = Files.writeString(directory.resolve("formatted.json"),
            "{\n  \"name\" : \"A B\",\n  \"values\" : [ 1, 2 ]\n}");
    JsonTokenContentHasher hasher = new JsonTokenContentHasher();
    assertEquals(hasher.sha256(compact), hasher.sha256(formatted));
}
```

- [ ] **Step 2: 运行测试确认 Red**

Run:

```powershell
mvn -pl debug-harness -am -Dtest=JsonTokenContentHasherTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 因 `JsonTokenContentHasher` 不存在而编译失败。

- [ ] **Step 3: 实现 Token 类型和值的长度前缀 Hash**

生产接口固定为：

```java
public final class JsonTokenContentHasher {
    public String sha256(Path jsonPath) throws HarnessException;
}
```

实现循环必须等价于：

```java
try (JsonParser parser = new JsonFactory().createParser(jsonPath.toFile())) {
    JsonToken token;
    int rootValues = 0;
    int depth = 0;
    while ((token = parser.nextToken()) != null) {
        if (depth == 0 && token != JsonToken.FIELD_NAME) {
            rootValues++;
        }
        update(digest, token.name());
        if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING
                || token.isNumeric()) {
            update(digest, parser.getText());
        }
        depth += token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY ? 1 : 0;
        depth -= token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY ? 1 : 0;
    }
    if (rootValues != 1 || depth != 0) {
        throw new HarnessException("GANTT_JSON_TOKEN_HASH_FAILED", "JSON 必须且只能包含一个完整根值");
    }
}
```

`update` 必须先写 4 字节大端长度，再写 UTF-8 字节；不得构建 `JsonNode` 或完整对象树。Jackson 解析、IO 和 SHA-256 初始化失败统一保留 cause，并使用错误码 `GANTT_JSON_TOKEN_HASH_FAILED`。

- [ ] **Step 4: 增加保守比较和非法 JSON 测试**

必须明确验证：

```java
assertNotEquals(hash("{\"v\":\"A B\"}"), hash("{\"v\":\"AB\"}"));
assertNotEquals(hash("{\"a\":1,\"b\":2}"), hash("{\"b\":2,\"a\":1}"));
assertNotEquals(hash("[1,2]"), hash("[2,1]"));
assertNotEquals(hash("1"), hash("1.0"));
assertThrows(HarnessException.class, () -> hash("{\"a\":"));
assertThrows(HarnessException.class, () -> hash("{} {}"));

private String hash(String json) throws Exception {
    Path path = Files.writeString(
            temporaryDirectory.resolve("case-" + fileSequence.incrementAndGet() + ".json"), json);
    return new JsonTokenContentHasher().sha256(path);
}
```

测试类使用 `@TempDir Path temporaryDirectory` 和 `AtomicInteger fileSequence = new AtomicInteger()`，
避免依赖真实时间或随机文件名。

- [ ] **Step 5: 运行测试、审计流式内存边界并提交**

```powershell
mvn -pl debug-harness -am -Dtest=JsonTokenContentHasherTest -Dsurefire.failIfNoSpecifiedTests=false test
git add -- debug-harness/pom.xml debug-harness/src/main/java/org/example/algorithmdebug/harness/JsonTokenContentHasher.java debug-harness/src/test/java/org/example/algorithmdebug/harness/JsonTokenContentHasherTest.java
git diff --cached --check
git commit -m "feat: add json token content hashing"
```

审计重点：多个根值、字符串空格、数字文本、64 MiB 文件、流关闭、异常 cause 和无完整树加载。

---

### Task 3: 将 Gantt 捕获迁移到通用内容指纹并删除 Adapter Hash SPI

**Files:**
- Delete: `adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/SemanticHashStrategy.java`
- Delete: `adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferSemanticHashStrategy.java`
- Delete: `adapters/wafer-demo-adapter/src/test/java/org/example/algorithmdebug/adapter/waferdemo/WaferSemanticHashStrategyTest.java`
- Modify: `adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/AdapterCapability.java`
- Modify: `adapter-sdk/src/main/java/org/example/algorithmdebug/adapter/TargetProjectAdapter.java`
- Modify: `adapter-sdk/src/test/java/org/example/algorithmdebug/adapter/TargetProjectAdapterContractTest.java`
- Modify: `adapter-sdk/README.md`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/CapturedScheduleResult.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleResultCapture.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunner.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleResultCaptureTest.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`
- Modify: `adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoAdapter.java`
- Modify: `adapters/wafer-demo-adapter/src/test/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoAdapterTest.java`
- Modify: `adapters/wafer-demo-adapter/src/test/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoRealProjectSmokeTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/AdapterCatalogTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java`
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java`
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java`

**Interfaces:**
- Consumes: Task 2 的 `JsonTokenContentHasher.sha256(Path)`。
- Produces: `CapturedScheduleResult(..., String rawSha256, String normalizedJsonSha256, ...)`；`ScheduleProducingTestRunner.run(...)` 不再接收 Hash Strategy；`TargetProjectAdapter` 不再暴露 Hash SPI。

- [ ] **Step 1: 先修改契约测试，要求 Adapter 不再提供 Hash 能力**

删除 Fake Adapter 的 `semanticHashStrategy()`，并将能力断言改为：

```java
assertTrue(adapter.descriptor().supports(AdapterCapability.SCHEDULE_RESULT));
assertFalse(Arrays.asList(TargetProjectAdapter.class.getMethods()).stream()
        .anyMatch(method -> method.getName().equals("semanticHashStrategy")));
```

Run:

```powershell
mvn -pl adapter-sdk -am test
```

Expected: 旧 SPI 尚存在，新的反射断言失败。

- [ ] **Step 2: 先修改 Harness 测试，要求捕获两个明确 Hash**

测试候选改为合法 JSON，断言：

```java
assertEquals(fileSha256(captured.capturedPath()), captured.rawSha256());
assertEquals(new JsonTokenContentHasher().sha256(captured.capturedPath()),
        captured.normalizedJsonSha256());

private static String fileSha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
    }
    return HexFormat.of().formatHex(digest.digest());
}
```

并从所有 `capture(...)`、`runner.run(...)` 调用删除 `hashStrategy` 参数。

Run:

```powershell
mvn -pl debug-harness -am test
```

Expected: 因现有签名和 `semanticHash` 字段不匹配而失败。

- [ ] **Step 3: 实现 Harness 最小迁移**

`CapturedScheduleResult` 改为：

```java
public record CapturedScheduleResult<T extends ScheduleResultSnapshot>(
        Path sourcePath,
        Path capturedPath,
        String rawSha256,
        String normalizedJsonSha256,
        long sizeBytes,
        T snapshot) { }
```

`ScheduleResultCapture` 在不可变复制完成后执行：

```java
String rawHash = rawFileSha256(captured);
String normalizedHash = new JsonTokenContentHasher().sha256(captured);
return new CapturedScheduleResult<>(selected.path(), captured, rawHash,
        normalizedHash, selected.sizeBytes(), selected.snapshot());
```

非法 JSON Token Hash 作为 `GANTT_JSON_TOKEN_HASH_FAILED` 的独立 Harness failure 返回；不得降级为使用 Adapter 快照 Hash。

- [ ] **Step 4: 原子删除 SPI、能力枚举和 Wafer 专属实现**

从 `TargetProjectAdapter` 删除方法，从 `AdapterCapability` 删除 `SEMANTIC_HASH`，删除两个生产/测试文件，更新所有 Fake Adapter、Wafer Adapter 和调用点。`WaferScheduleResultParser` 与类型化 Snapshot 保留，因为它们仍判定候选是否合法。

- [ ] **Step 5: 运行跨模块回归、源码审计并提交**

```powershell
mvn -pl adapter-sdk,debug-harness,adapters/wafer-demo-adapter,ada-core,integration-tests -am test
rg -n "SemanticHashStrategy|semanticHashStrategy|WaferSemanticHashStrategy|SEMANTIC_HASH|\.semanticHash\(" --glob "*.java"
```

Expected: Maven exit 0；源码检索无输出。

```powershell
git add -- adapter-sdk debug-harness adapters/wafer-demo-adapter ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java ada-core/src/test/java/org/example/algorithmdebug/core/AdapterCatalogTest.java ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java
git diff --cached --check
git commit -m "refactor: move gantt hashing into harness"
```

审计重点：Parser 仍是唯一候选合法性判据、Hash 在复制后计算、原始 SHA 未丢失、UT 异常不被 Gantt 后处理覆盖。

---

### Task 4: 新增唯一持久化指纹契约和 Schema

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunResultFingerprint.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunResultFingerprintTest.java`
- Create: `schemas/execution/run-result-fingerprint-v1.schema.json`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveJsonTest.java`
- Modify: `schemas/README.md`

**Interfaces:**
- Consumes: Case/Context/Run ID 与可选成功/失败 SHA-256。
- Produces: 版本化 `RunResultFingerprint` 1.0，供 Run 文档和 Context reproduction 复用同一 JSON。

- [ ] **Step 1: 写构造不变量和 JSON round-trip 失败测试**

目标接口：

```java
public record RunResultFingerprint(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        RunId runId,
        Optional<String> ganttRawSha256,
        Optional<String> ganttNormalizedJsonSha256,
        Optional<String> targetFailureSha256) { }
```

测试必须覆盖：成功 Gantt、仅失败、断言失败且有 Gantt、大小写 Hash 规范化、两个 Gantt Hash 只出现一个、三个观察都缺失。

- [ ] **Step 2: 运行测试确认 Red**

```powershell
mvn -pl ada-contracts -am -Dtest=RunResultFingerprintTest,CaseArchiveJsonTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 新记录和 Schema 常量不存在。

- [ ] **Step 3: 实现记录、版本常量和严格 Schema**

构造器核心不变量：

```java
if (ganttRawSha256.isPresent() != ganttNormalizedJsonSha256.isPresent()) {
    throw new IllegalArgumentException("Gantt raw/normalized Hash 必须同时存在或同时缺失");
}
if (ganttRawSha256.isEmpty() && targetFailureSha256.isEmpty()) {
    throw new IllegalArgumentException("RunResultFingerprint 至少需要一个目标观察");
}
```

所有存在的 Hash 经 `ContractChecks.requireSha256` 规范为小写。Schema 的 7 个字段全部 required，三个可选 Hash 用 `oneOf: string/null` 表达，`additionalProperties=false`，并通过 `allOf` 表达 Gantt Hash 成对出现和至少一个观察存在。

- [ ] **Step 4: 运行契约测试、审计兼容性并提交**

```powershell
mvn -pl ada-contracts -am test
git add -- ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunResultFingerprint.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunResultFingerprintTest.java ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveJsonTest.java schemas/execution/run-result-fingerprint-v1.schema.json schemas/README.md
git diff --cached --check
git commit -m "feat: define run result fingerprint contract"
```

审计重点：旧 `RunOutcomeSummary` 1.0 不改字段，旧 Run 不要求补写指纹，Schema 与 Java Optional/null 行为一致。

---

### Task 5: 实现目标失败指纹和简单比较器

**Files:**
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/TargetFailureFingerprinter.java`
- Create: `debug-harness/src/test/java/org/example/algorithmdebug/harness/TargetFailureFingerprinterTest.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ReproductionComparator.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ReproductionComparatorTest.java`

**Interfaces:**
- Consumes: `TargetFailureDiagnostic` 与两个 `RunResultFingerprint`。
- Produces: `String TargetFailureFingerprinter.sha256(TargetFailureDiagnostic) throws HarnessException`；`ReproductionComparator.Result compare(reference, current, scope)`。

- [ ] **Step 1: 写失败指纹稳定性和变化检测失败测试**

测试输入：

```java
TargetFailureDiagnostic first = new TargetFailureDiagnostic(
        FailureCategory.TEST_ERROR,
        "java.lang.NullPointerException",
        "missing   route",
        "Planner failed",
        "com.acme.Planner.solve(Planner.java:42)");
TargetFailureDiagnostic same = new TargetFailureDiagnostic(
        FailureCategory.TEST_ERROR,
        "java.lang.NullPointerException",
        "missing route",
        "Planner failed",
        "com.acme.Planner.solve(Planner.java:99)");
assertEquals(fingerprinter.sha256(first), fingerprinter.sha256(same));
```

另断言异常类、cause 或业务栈帧方法变化时 Hash 不同。

- [ ] **Step 2: 实现有界长度前缀失败 Hash**

固定算法头为 `TARGET_FAILURE_SHA256_V1`。依次 Hash：category、exceptionClass、normalizedMessage、cause、stableBusinessFrame。普通文本只 `strip()` 并把连续空白折叠为一个空格；业务栈帧只把匹配 `\\.java:[0-9]+\\)` 的源码行号替换为 `.java:#)`，不得删除其他数字或业务 ID。
SHA-256 初始化异常包装为保留 cause 的 `HarnessException("TARGET_FAILURE_FINGERPRINT_FAILED", ...)`。

- [ ] **Step 3: 写比较器失败测试**

目标接口：

```java
public final class ReproductionComparator {
    public Result compare(
            RunResultFingerprint reference,
            RunResultFingerprint current,
            Scope scope);

    public enum Scope { SAME_CONTEXT, CROSS_CONTEXT }

    public record Result(
            ComparisonOutcome outcome,
            String summary,
            List<String> changedDimensions) { }
}
```

测试覆盖：相同 Gantt、格式变化导致 raw 不同但 normalized 相同、Gantt 内容变化、失败相同、失败变化、Gantt/失败存在性变化、断言失败双维组合和不同 Case 拒绝比较。

- [ ] **Step 4: 实现固定模板比较**

只比较 `ganttNormalizedJsonSha256` 与 `targetFailureSha256`；`ganttRawSha256` 不决定 `MATCHED/CHANGED`。摘要固定为：

```text
Baseline MATCHED; scope=SAME_CONTEXT; referenceRunId=run-1; changedDimensions=NONE
Baseline CHANGED; scope=CROSS_CONTEXT; referenceRunId=run-1; changedDimensions=GANTT,TARGET_FAILURE
```

变化维度固定顺序为 `GANTT`、`TARGET_FAILURE`，摘要最大 2 KiB。

- [ ] **Step 5: 运行测试、审计误判边界并提交**

```powershell
mvn -pl debug-harness,case-management -am -Dtest=TargetFailureFingerprinterTest,ReproductionComparatorTest -Dsurefire.failIfNoSpecifiedTests=false test
git add -- debug-harness/src/main/java/org/example/algorithmdebug/harness/TargetFailureFingerprinter.java debug-harness/src/test/java/org/example/algorithmdebug/harness/TargetFailureFingerprinterTest.java case-management/src/main/java/org/example/algorithmdebug/casecore/ReproductionComparator.java case-management/src/test/java/org/example/algorithmdebug/casecore/ReproductionComparatorTest.java
git diff --cached --check
git commit -m "feat: compare run result fingerprints"
```

审计重点：不穷举异常类型、不解释根因、不删除业务数字、存在性变化可见、raw 格式变化不误报算法变化。

---

### Task 6: 追加保存 Run 指纹和 Context reproduction reference

**Files:**
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`

**Interfaces:**
- Consumes: Task 4 的 `RunResultFingerprint`。
- Produces: Run 指纹 create-new 路径、Context reference create-new/read、最近旧 Context reference 查询。

- [ ] **Step 1: 写路径和不可覆盖失败测试**

目标路径：

```text
cases/{caseId}/runs/{runId}/run-result-fingerprint.json
cases/{caseId}/contexts/{contextId}/reproduction.json
```

目标方法：

```java
public Path createRunResultFingerprint(RunResultFingerprint fingerprint);
public Optional<RunResultFingerprint> findReproduction(CaseId caseId, ContextId contextId);
public RunResultFingerprint createReproductionIfAbsent(RunResultFingerprint fingerprint);
public Optional<RunResultFingerprint> findLatestReproductionBefore(
        CaseId caseId, ContextId currentContextId);
```

测试要求第二次写 Run 指纹失败；第二次建立 Context reference 返回已经存在的 reference 且文件内容不变；路径文档身份不匹配返回 `CASE_ARCHIVE_IDENTITY_MISMATCH`。

- [ ] **Step 2: 运行 Repository 测试确认 Red**

```powershell
mvn -pl case-management -am -Dtest=CaseArchiveRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 新路径和方法不存在。

- [ ] **Step 3: 实现路径、身份验证和 create-new 读写**

`CaseArchiveLayout` 新增：

```java
public Path contextRoot(ContextId contextId);
public Path contextReproduction(ContextId contextId);
public Path runResultFingerprint(RunId runId);
```

Repository 写 Run 指纹前必须读取同 Run 的 `RunRequest` 并校验 case/context/run。建立 reproduction 前必须确认 Context 存在；已有 reference 只读返回，不调用覆盖写入。

- [ ] **Step 4: 实现确定性的旧 Context 选择**

`findLatestReproductionBefore` 读取当前 Context，并按 `(createdAt, contextId.value())` 建立确定性顺序；
只选择该二元组严格小于当前 Context 的条目，再按同一顺序降序取第一项。损坏或身份不匹配的 reference
不静默跳过，返回 `CASE_DOCUMENT_INVALID` 或 `CASE_ARCHIVE_IDENTITY_MISMATCH`。

- [ ] **Step 5: 运行测试、审计并提交**

```powershell
mvn -pl case-management -am test
git add -- case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java
git diff --cached --check
git commit -m "feat: archive reproduction fingerprints"
```

审计重点：路径逃逸、符号链接、reference 覆盖、并列 Context 顺序、损坏文档、运行身份串扰。

---

### Task 7: 在 RunApplicationService 接入指纹、参考选择和比较结论

**Files:**
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/RunOutcomeAssembler.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/RunOutcomeAssemblerTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java`

**Interfaces:**
- Consumes: `CapturedScheduleResult.normalizedJsonSha256`、`TargetFailureFingerprinter`、Repository reference API、`ReproductionComparator`。
- Produces: 真实 `comparisonOutcome/comparisonSummary` 和 `RUN_RESULT_FINGERPRINT` Artifact 引用。

- [ ] **Step 1: 修改 Assembler 测试，要求比较结果由调用方显式传入**

`assemble` 末尾新增：

```java
ComparisonOutcome comparisonOutcome,
String comparisonSummary
```

并断言传入 `MATCHED` 时输出不再被改回 `NOT_COMPARED`。比较摘要仍执行非空和 2 KiB 上限校验。

- [ ] **Step 2: 运行 Harness 测试确认 Red，再实现最小签名变化**

```powershell
mvn -pl debug-harness -am -Dtest=RunOutcomeAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 旧签名或硬编码 `NOT_COMPARED` 导致失败。

- [ ] **Step 3: 写 Core 首次、匹配、变化和 Agent-only 失败测试**

在 `RunApplicationServiceTest` 用顺序 ID 和可变执行器验证：

```java
assertEquals(ComparisonOutcome.NOT_COMPARED, first.comparisonOutcome());
assertEquals(ComparisonOutcome.MATCHED, second.comparisonOutcome());
assertEquals(ComparisonOutcome.CHANGED, third.comparisonOutcome());
assertTrue(second.artifacts().stream()
        .anyMatch(a -> "RUN_RESULT_FINGERPRINT".equals(a.artifactType())));
```

目标输出使用合法 JSON，例如 `{"schedule":"ok"}`；仅 Maven 缺失/进程启动失败时断言没有 `run-result-fingerprint.json` 和 `reproduction.json`。

- [ ] **Step 4: 在 Core 以两阶段组装方式接入比较**

流程固定为：

```java
RunOutcomeSummary observed = assembler.assemble(
        request, run, testResult, ganttOutcome, agentFailure, markerText, references,
        ComparisonOutcome.NOT_COMPARED, "No valid reproduction reference");
Optional<RunResultFingerprint> fingerprint = createFingerprint(request, schedule, observed);
ComparisonDecision decision = archiveAndCompare(archive, fingerprint, references);
RunOutcomeSummary outcome = assembler.assemble(
        request, run, testResult, ganttOutcome, updatedAgentFailure, markerText, references,
        decision.outcome(), decision.summary());

private record ComparisonDecision(
        ComparisonOutcome outcome,
        String summary) { }
```

`createFingerprint` 规则：有 Gantt 时写 raw/normalized；有 targetFailure 时写失败 Hash；至少一个存在才创建。先写 Run 指纹并加入 `RUN_RESULT_FINGERPRINT` Artifact，再读取/建立当前 Context reference；当前 Context 已有参考使用 `SAME_CONTEXT`，首次 reference 可与最近旧 Context 使用 `CROSS_CONTEXT`。

两个私有方法签名固定为：

```java
private Optional<RunResultFingerprint> createFingerprint(
        RunRequest request,
        ScheduleRunResult<?> schedule,
        RunOutcomeSummary observed);

private ComparisonDecision archiveAndCompare(
        CaseArchiveRepository archive,
        Optional<RunResultFingerprint> current,
        List<ArtifactReference> references);
```

- [ ] **Step 5: 实现失败隔离**

指纹计算或 Run 指纹写入失败：保留目标 UT/Gantt/Surefire 事实，追加 `AgentFailureDiagnostic`，比较为 `INCOMPARABLE`。只有 reference 不存在且当前指纹成功：建立 reference；没有旧 Context 时为 `NOT_COMPARED`。不得因比较失败删除 Gantt Artifact 或阻止 `run-outcome.json` 收尾。

错误码映射固定为：失败 Hash 为 `TARGET_FAILURE_FINGERPRINT_FAILED`，Run 指纹持久化为
`RUN_FINGERPRINT_WRITE_FAILED`，reference 建立为 `REPRODUCTION_REFERENCE_WRITE_FAILED`，reference
读取/身份/Schema 错误为 `REPRODUCTION_REFERENCE_INVALID`，无法可信比较为
`RUN_COMPARISON_INCOMPARABLE`。这些码只表示 Agent 后处理失败，不覆盖目标 UT 事实。

- [ ] **Step 6: 运行 Core/Harness 测试、审计并提交**

```powershell
mvn -pl debug-harness,ada-core -am test
git add -- debug-harness/src/main/java/org/example/algorithmdebug/harness/RunOutcomeAssembler.java debug-harness/src/test/java/org/example/algorithmdebug/harness/RunOutcomeAssemblerTest.java ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java
git diff --cached --check
git commit -m "feat: compare case run fingerprints"
```

审计重点：双阶段组装结果一致、Agent failure 合并、Artifact 先落盘后引用、首次/同 Context/跨 Context、断言失败有 Gantt、超时未知事实和 Run 始终只执行一次。

---

### Task 8: 端到端回归、Skill 指引和最终审计

**Files:**
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java`
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java`
- Modify: `skills/algorithm-debug/SKILL.md`
- Modify: `README.md`
- Modify: `debug-harness/README.md`
- Modify: `case-management/README.md`
- Modify: `ada-core/README.md`
- Modify: `schemas/README.md`
- Modify: `docs/designs/2026-08-17-json-content-fingerprint-baseline-design.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`

**Interfaces:**
- Consumes: Tasks 2-7 完整闭环。
- Produces: 可回归的同 Context/跨 Context/异常 Baseline 行为，以及指导大模型正确解释 `MATCHED/CHANGED` 的仓库内 Skill。

- [ ] **Step 1: 增加端到端成功和失败比较断言**

集成测试至少验证：

```java
assertEquals(ComparisonOutcome.MATCHED, secondOutcome.comparisonOutcome());
assertTrue(Files.isRegularFile(runRoot.resolve("run-result-fingerprint.json")));
assertTrue(Files.isRegularFile(contextRoot.resolve("reproduction.json")));
RunResultFingerprint reproduction = mapper.readJson(
        contextRoot.resolve("reproduction.json"), RunResultFingerprint.class);
assertEquals(firstOutcome.runId(), reproduction.runId());
```

再让受控 Fixture 改变一个 JSON 值，断言 `CHANGED`；连续两次相同业务异常断言第二次 `MATCHED`；Maven 启动失败断言仍为 `NOT_COMPARED` 且无 reference。

- [ ] **Step 2: 更新真实 Wafer Smoke Test**

删除 Adapter Hash Strategy，使用：

```java
assertEquals(first.normalizedJsonSha256(), second.normalizedJsonSha256());
assertEquals(first.rawSha256(), second.rawSha256());
```

真实测试继续由 `wafer.demo.projectRoot` 系统属性启用，不把本机路径写入生产测试。

- [ ] **Step 3: 更新仓库内 Skill 的模型决策规则**

把“描述精确 changed dimensions/读取 comparison Artifact”改为：

```markdown
When `comparisonOutcome=MATCHED`, treat the current target observation as reproducible for the
reported scope. When it is `CHANGED`, state that the Agent detected a Gantt-content and/or target-
failure fingerprint change, then read the referenced current and reference artifacts only if the
user's question requires the change location. Do not claim that the Agent produced a field-level
Gantt diff.
```

- [ ] **Step 4: 运行全仓库 UT 和真实目标验收**

```powershell
mvn test
mvn -pl integration-tests "-Dwafer.demo.projectRoot=D:\javacode\hellomvn" -Dtest=WaferBaselineLifecycleSmokeTest test
```

Expected: 根 Reactor 与真实 Wafer Smoke Test 均为 0 failures、0 errors；真实两次运行的 normalized JSON Hash 相同。

- [ ] **Step 5: 执行最终代码审计**

逐项检查：

```powershell
rg -n "SemanticHashStrategy|semanticHashStrategy|WaferSemanticHashStrategy|ScheduleResultProjection|ScheduleProjectionDiffer|ScheduleResultDiffService" --glob "*.java"
rg -n "Baseline comparison is not implemented in this slice" --glob "*.java" --glob "*.md"
git log --check -8 --oneline
git status --short
```

Expected: 生产/测试 Java 无旧 Hash/Projection/Diff 类型；旧占位比较文本只允许出现在明确标记的历史规格中；diff check exit 0。

- [ ] **Step 6: 更新设计完成记录并提交最终闭环**

设计完成记录写入实际测试数量、根 Maven 结果、真实 Wafer Hash、已知限制“无字段级 Diff”。精确暂存本 Task 文件：

```powershell
git add -- integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java integration-tests/src/test/java/org/example/algorithmdebug/integration/WaferBaselineLifecycleSmokeTest.java skills/algorithm-debug/SKILL.md README.md debug-harness/README.md case-management/README.md ada-core/README.md schemas/README.md docs/designs/2026-08-17-json-content-fingerprint-baseline-design.md docs/plans/algorithm-debug-agent-development-plan.md
git diff --cached --check
git commit -m "test: verify fingerprint baseline workflow"
```

---

## Execution Checkpoints

1. Task 1 后确认现有未提交迁移已隔离，工作区其他 OpenCode/文档改动仍保留。
2. Task 3 后确认 Adapter 不再承担 Hash，现有单次 UT Run 功能完全回归。
3. Task 6 后确认 reproduction write-once 和跨 Context 选择行为。
4. Task 7 后确认正式 RunOutcome 已产生真实比较结论。
5. Task 8 后执行根测试、真实 Wafer UT、最终代码审计和完成记录。

## Deferred Work

- 字段级或 operation 级 Gantt Diff；
- `gantt-analysis` 生产实现；
- CodePathTracer/JDWP 采集计划和运行；
- OpenCode 一次性安装器与完整模型端到端 Eval；
- JSON 对象成员排序或 RFC 8785 Canonicalization；
- 数据库、事件溯源和复杂 Case 状态机。
