# External Workspace Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first usable P0 control-plane slice that initializes an external Algorithm Debug Workspace, registers an independent Maven algorithm module inside a larger Git repository, diagnoses the local environment, and exposes those operations through bounded ToolResponse JSON CLI commands.

**Architecture:** Keep stable DTOs in `ada-contracts`, filesystem layout and append-only documents in `case-management`, use-case composition in `ada-core`, and argument/JSON handling in `algorithm-debug-cli`. The registered Agent Project is the Maven algorithm module; `repositoryRoot`, `moduleRoot`, and `mavenExecutionRoot` remain distinct. No module in this slice writes the target module or its containing repository.

**Tech Stack:** Java 21, Maven 3.9.x, JUnit 5.10.3, Jackson 2.17.2, Jackson Dataformat YAML 2.17.2, Maven Shade Plugin, Node built-in test runner for the existing OpenCode wrapper.

## Global Constraints

- Follow `AGENTS.md`, `docs/development/development-rules.md`, and the approved [design](../specs/2026-08-16-external-workspace-control-plane-design.md).
- Use Red-Green-Refactor: every production behavior requires a test that was observed failing for the intended reason first.
- Preserve all pre-existing uncommitted work; stage only files or exact hunks belonging to the current task and inspect `git diff --cached` before every commit.
- Java code and protocol identifiers use English; public API Javadoc and team-facing explanations use Chinese.
- `ada-contracts` must not depend on implementation modules; dependency direction is CLI → Core → Case Management → Contracts.
- The target algorithm module and containing Git repository are read-only.
- Workspace documents are bounded to 1 MiB, versioned, atomically created, and never overwritten by initialization or registration.
- CLI stdout contains exactly one ToolResponse 2.0 JSON document; logs and diagnostics use stderr.
- Do not implement Input Analysis, Case repositories, OpenCode installation, CodePath, JDWP, Evidence, MCP, or other runtimes in this plan.
- Do not add Picocli or another application framework.

---

## Planned file structure

### `ada-contracts`

```text
src/main/java/org/example/algorithmdebug/contracts/
├─ WorkspaceManifest.java
├─ WorkspaceInitializationResult.java
├─ ProjectRegistration.java
├─ ProjectRegistrationResult.java
├─ DoctorStatus.java
├─ DoctorCheck.java
└─ DoctorReport.java
```

These are immutable cross-module and CLI-response DTOs. `ProjectRegistration` stores path strings using `/` separators because absolute paths are local control-plane data, not portable Artifact references.

### `case-management`

```text
src/main/java/org/example/algorithmdebug/casecore/
├─ WorkspaceException.java
├─ WorkspaceLayout.java
├─ AtomicDocumentWriter.java
├─ BoundedDocumentMapper.java
├─ WorkspaceManifestRepository.java
├─ WorkspaceTemplateProvider.java
├─ ClasspathWorkspaceTemplateProvider.java
├─ WorkspaceInitializer.java
├─ RepositoryRootLocator.java
├─ ProjectIdGenerator.java
├─ ProjectRegistrationRepository.java
├─ ProjectRegistry.java
└─ WorkspaceConfigurationResolver.java

src/main/resources/org/example/algorithmdebug/casecore/workspace-templates/
├─ application.yaml
├─ execution.yaml
├─ collection-limits.yaml
└─ security-policy.yaml
```

### `ada-core`

```text
src/main/java/org/example/algorithmdebug/core/
├─ WorkspaceApplicationService.java
├─ ProjectApplicationService.java
├─ MavenExecutableLocator.java
└─ DoctorApplicationService.java
```

### `algorithm-debug-cli`

```text
src/main/java/org/example/algorithmdebug/cli/
├─ AdaMain.java
├─ CliCommand.java
├─ CliArguments.java
├─ CliCommandExecutor.java
└─ CliResponseWriter.java
```

---

### Task 1: Workspace, Project, and Doctor contracts

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/WorkspaceManifest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/WorkspaceInitializationResult.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistration.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistrationResult.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorCheck.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorReport.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneContractsTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneJsonTest.java`
- Create: `schemas/workspace/workspace-manifest-v1.schema.json`
- Create: `schemas/workspace/project-registration-v1.schema.json`
- Create: `schemas/workspace/doctor-report-v1.schema.json`

**Interfaces:**
- Consumes: existing `ProjectId`, `ToolResponse`, `ContractChecks`, Jackson annotations, `Instant`.
- Produces:
  - `WorkspaceManifest(String schemaVersion, String kind, Instant createdAt)`
  - `WorkspaceInitializationResult(String workspaceRoot, boolean created, String schemaVersion)`
  - `ProjectRegistration(String schemaVersion, ProjectId projectId, String displayName, String repositoryRoot, String moduleRoot, String mavenExecutionRoot, String pomPath, String buildTool, String pomSha256, Instant registeredAt)`
  - `ProjectRegistrationResult(ProjectRegistration registration, boolean created)`
  - `DoctorStatus { PASS, WARN, FAIL }`
  - `DoctorCheck(String name, DoctorStatus status, String code, String message)`
  - `DoctorReport(String schemaVersion, DoctorStatus overallStatus, List<DoctorCheck> checks)`；`fromChecks` 自动填入当前 Schema 版本

- [ ] **Step 1: Write failing contract tests**

Create tests that express constructor invariants and immutable lists before creating the DTOs:

```java
@Test
void shouldKeepModuleAndRepositoryRootsDistinct() {
    ProjectRegistration registration = new ProjectRegistration(
            SchemaVersions.PROJECT_REGISTRATION,
            new ProjectId("algorithm-scheduler-a1b2c3d4e5f6"),
            "algorithm-scheduler",
            "D:/large-system",
            "D:/large-system/algorithm-scheduler",
            "D:/large-system/algorithm-scheduler",
            "pom.xml",
            "MAVEN",
            "a".repeat(64),
            Instant.parse("2026-08-16T00:00:00Z"));

    assertNotEquals(registration.repositoryRoot(), registration.moduleRoot());
    assertEquals(registration.moduleRoot(), registration.mavenExecutionRoot());
}

@Test
void shouldDeriveFailedDoctorStatusFromChecksAtConstruction() {
    DoctorReport report = DoctorReport.fromChecks(List.of(
            new DoctorCheck("java", DoctorStatus.PASS, "JAVA_OK", "Java 21"),
            new DoctorCheck("maven", DoctorStatus.FAIL, "MAVEN_NOT_FOUND", "Maven not found")));

    assertEquals(SchemaVersions.DOCTOR_REPORT, report.schemaVersion());
    assertEquals(DoctorStatus.FAIL, report.overallStatus());
    assertThrows(UnsupportedOperationException.class,
            () -> report.checks().add(new DoctorCheck("x", DoctorStatus.PASS, "X", "x")));
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn -pl ada-contracts -am -Dtest=WorkspaceControlPlaneContractsTest test
```

Expected: test compilation fails because the new DTOs and Schema version constants do not exist.

- [ ] **Step 3: Implement minimal immutable contracts**

Use compact records with constructor checks. `DoctorReport.fromChecks` computes severity deterministically using `FAIL > WARN > PASS`. Add constants:

```java
public static final String WORKSPACE_MANIFEST = "1.0";
public static final String PROJECT_REGISTRATION = "1.0";
public static final String DOCTOR_REPORT = "1.0";
```

`ProjectRegistration` must require nonblank path strings, `pomPath` through `requirePortableRelativePath`, build tool exactly `MAVEN`, and lowercase SHA-256. Do not introduce a duplicate build-tool enum.

- [ ] **Step 4: Add JSON round-trip and Schema-shape tests**

Use a Jackson mapper with `JavaTimeModule`. Serialize each DTO, deserialize it, and assert equality. Load each Schema as JSON and assert its `$id`, `required` names, `additionalProperties=false`, and matching `schemaVersion.const` value. This is a contract-shape test and does not add a third-party JSON Schema validator.

- [ ] **Step 5: Add the three JSON Schemas**

The Project Schema must require all fields listed in the interface block. Path fields are nonblank strings; `pomPath` uses a portable relative-path pattern; `pomSha256` uses `^[0-9a-f]{64}$`. Doctor checks are bounded with `maxItems: 32`.

- [ ] **Step 6: Verify GREEN and regress existing contracts**

Run:

```powershell
mvn -pl ada-contracts -am test
```

Expected: all `ada-contracts` tests pass, including existing Baseline, RunOutcome, and ToolResponse tests.

- [ ] **Step 7: Audit and commit Task 1**

Check public Chinese Javadoc, DTO immutability, Schema/field agreement, and `ada-contracts` dependency isolation. Stage only the new contract and Schema files plus `SchemaVersions.java`:

```powershell
git add -- ada-contracts/src/main/java/org/example/algorithmdebug/contracts/WorkspaceManifest.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/WorkspaceInitializationResult.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistration.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistrationResult.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorStatus.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorCheck.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/DoctorReport.java ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneContractsTest.java ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneJsonTest.java schemas/workspace
git diff --cached --check
git commit -m "feat: add workspace control plane contracts"
```

---

### Task 2: Safe Workspace layout and bounded document persistence

**Files:**
- Modify: `case-management/pom.xml`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceException.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceLayout.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AtomicDocumentWriter.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/BoundedDocumentMapper.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceManifestRepository.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceLayoutTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/AtomicDocumentWriterTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceManifestRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 `WorkspaceManifest`, `ProjectId`; Jackson Databind, JavaTime and Dataformat YAML.
- Produces:
  - `WorkspaceLayout.of(Path root)`
  - `Path root()`, `configRoot()`, `projectsRoot()`, `systemRoot()`, `projectWorkspace(ProjectId)`, `projectCases(ProjectId)`
  - `AtomicDocumentWriter.writeNew(Path target, byte[] content)`
  - `BoundedDocumentMapper.readYaml(Path, Class<T>)`, `writeYaml(T)`, `readJson(Path, Class<T>)`, `writeJson(T)`
  - `WorkspaceManifestRepository.find(WorkspaceLayout)`, `create(WorkspaceLayout, WorkspaceManifest)`, `require(WorkspaceLayout)`

- [ ] **Step 1: Write failing path-boundary tests**

```java
@Test
void shouldDeriveProjectCasesOutsideTargetRepository(@TempDir Path temp) {
    WorkspaceLayout layout = WorkspaceLayout.of(temp.resolve("agent-workspace"));
    Path cases = layout.projectCases(new ProjectId("algorithm-module-123"));

    assertEquals(layout.root().resolve("projects/algorithm-module-123/cases"), cases);
}

@Test
void shouldRejectEscapingProjectId(@TempDir Path temp) {
    WorkspaceLayout layout = WorkspaceLayout.of(temp.resolve("agent-workspace"));
    assertThrows(IllegalArgumentException.class,
            () -> layout.projectWorkspace(new ProjectId("../outside")));
}
```

- [ ] **Step 2: Run the path tests and verify RED**

```powershell
mvn -pl case-management -am -Dtest=WorkspaceLayoutTest test
```

Expected: compilation fails because `WorkspaceLayout` is missing.

- [ ] **Step 3: Implement `WorkspaceLayout` and `WorkspaceException`**

Normalize to absolute paths. Every child resolver must call one private `resolveWithinRoot` method and verify `candidate.startsWith(root)`. Project IDs must be single safe segments and reject separators, dot segments, drive syntax, and control characters.

- [ ] **Step 4: Write failing atomic and bounded-document tests**

Cover create-new success, overwrite rejection, temporary-file cleanup after serializer failure, 1 MiB read rejection, malformed YAML, unsupported Workspace Schema, and valid round-trip.

- [ ] **Step 5: Run document tests and verify RED**

```powershell
mvn -pl case-management -am -Dtest=AtomicDocumentWriterTest,WorkspaceManifestRepositoryTest test
```

Expected: compilation fails because persistence classes are missing.

- [ ] **Step 6: Add dependencies and minimal persistence implementation**

Add `jackson-databind`, `jackson-datatype-jsr310`, and `jackson-dataformat-yaml` using the parent Jackson BOM. `BoundedDocumentMapper` checks file size before reading and configures YAML parsing without polymorphic typing. `AtomicDocumentWriter` creates its temporary file in the target parent, writes and flushes bytes, then moves with `ATOMIC_MOVE`; it rejects an existing final target.

- [ ] **Step 7: Verify GREEN and audit Task 2**

```powershell
mvn -pl case-management -am test
```

Audit path containment, no string-built paths, cause preservation, 1 MiB bounds, temporary cleanup, and no dependency from Contracts to Case Management.

- [ ] **Step 8: Commit Task 2**

```powershell
git add -- case-management/pom.xml case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceException.java case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceLayout.java case-management/src/main/java/org/example/algorithmdebug/casecore/AtomicDocumentWriter.java case-management/src/main/java/org/example/algorithmdebug/casecore/BoundedDocumentMapper.java case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceManifestRepository.java case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceLayoutTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/AtomicDocumentWriterTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceManifestRepositoryTest.java
git diff --cached --check
git commit -m "feat: add safe workspace persistence"
```

---

### Task 3: Idempotent Workspace initialization and configuration templates

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceTemplateProvider.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ClasspathWorkspaceTemplateProvider.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceInitializer.java`
- Create: `case-management/src/main/resources/org/example/algorithmdebug/casecore/workspace-templates/application.yaml`
- Create: `case-management/src/main/resources/org/example/algorithmdebug/casecore/workspace-templates/execution.yaml`
- Create: `case-management/src/main/resources/org/example/algorithmdebug/casecore/workspace-templates/collection-limits.yaml`
- Create: `case-management/src/main/resources/org/example/algorithmdebug/casecore/workspace-templates/security-policy.yaml`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceInitializerTest.java`

**Interfaces:**
- Consumes: Task 2 layout, writer, manifest repository; Task 1 manifest/result; injected `Clock`.
- Produces:
  - `WorkspaceTemplateProvider.templates(): Map<Path, byte[]>`
  - `WorkspaceInitializer.initialize(Path root): WorkspaceInitializationResult`

- [ ] **Step 1: Write failing initializer tests**

```java
@Test
void shouldBeIdempotentAndPreserveUserConfiguration(@TempDir Path temp) throws Exception {
    WorkspaceInitializer initializer = initializerWithFixedClock();
    WorkspaceInitializationResult first = initializer.initialize(temp.resolve("workspace"));
    Path application = temp.resolve("workspace/config/application.yaml");
    Files.writeString(application, "schemaVersion: \"1.0\"\noffline: false\n");

    WorkspaceInitializationResult second = initializer.initialize(temp.resolve("workspace"));

    assertTrue(first.created());
    assertFalse(second.created());
    assertEquals("schemaVersion: \"1.0\"\noffline: false\n", Files.readString(application));
}
```

Also assert the full standard directory tree, `workspace.yaml`, four templates, no `system/installation.json`, and rejection of an incompatible existing Manifest.

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -pl case-management -am -Dtest=WorkspaceInitializerTest test
```

Expected: compilation fails because initializer and template provider are missing.

- [ ] **Step 3: Implement classpath templates and initializer**

The classpath provider reads exactly four known resources; it never scans a directory. The initializer creates directories, validates or creates the Manifest, then creates only missing template files through `AtomicDocumentWriter`. It returns `created=true` only when it created `workspace.yaml`.

Template values:

```yaml
# application.yaml
schemaVersion: "1.0"
offline: true
```

```yaml
# execution.yaml
schemaVersion: "1.0"
```

```yaml
# collection-limits.yaml
schemaVersion: "1.0"
maxTotalRuns: 8
maxCodePathRuns: 3
maxJdwpRuns: 4
maxTraceBytesPerRun: 52428800
maxWallClockMinutes: 20
```

`maxCodePathRuns` is greater than one because the approved model allows iterative CodePath collection within an Analysis.

- [ ] **Step 4: Verify GREEN and audit Task 3**

```powershell
mvn -pl case-management -am test
```

Audit idempotence, no overwrite, no target-repository path, fixed Clock use, resource bounds, and absence of `installation.json`.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceTemplateProvider.java case-management/src/main/java/org/example/algorithmdebug/casecore/ClasspathWorkspaceTemplateProvider.java case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceInitializer.java case-management/src/main/resources/org/example/algorithmdebug/casecore/workspace-templates case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceInitializerTest.java
git diff --cached --check
git commit -m "feat: initialize external algorithm workspace"
```

---

### Task 4: Register independent Maven algorithm modules

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/RepositoryRootLocator.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectIdGenerator.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepository.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistry.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/RepositoryRootLocatorTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectIdGeneratorTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepositoryTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistryTest.java`

**Interfaces:**
- Consumes: Tasks 1-3 contracts, layout, JSON mapper, atomic writer, manifest repository; injected `Clock`.
- Produces:
  - `RepositoryRootLocator.locate(Path moduleRoot): Path`
  - `ProjectIdGenerator.generate(Path canonicalModuleRoot): ProjectId`
  - `ProjectRegistrationRepository.findById(...)`, `findAll(...)`, `create(...)`
  - `ProjectRegistry.register(Path workspaceRoot, Path moduleRoot, Optional<ProjectId> requestedId): ProjectRegistrationResult`

- [ ] **Step 1: Write failing repository-root and ID tests**

Create a temporary tree with `.git` at the large repository root and `algorithm-scheduler/pom.xml` below it. Assert that the locator returns the Git root without inspecting sibling content. Assert the generated ID is stable, lowercase, path-safe, and changes when canonical module path changes.

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -pl case-management -am -Dtest=RepositoryRootLocatorTest,ProjectIdGeneratorTest test
```

Expected: compilation fails for missing classes.

- [ ] **Step 3: Implement root location and deterministic IDs**

`RepositoryRootLocator` walks only the current path and its parents, accepting `.git` as either a file or directory. If none exists, return canonical `moduleRoot`. `ProjectIdGenerator` uses the lowercased module directory name plus the first twelve lowercase hex characters of SHA-256 over the canonical module path encoded as UTF-8.

- [ ] **Step 4: Write failing registration behavior tests**

Cover:

```java
@Test
void shouldKeepRepositoryModuleAndExecutionRootsDistinct() throws Exception {
    ProjectRegistrationResult result = registry.register(workspace, module, Optional.empty());
    assertEquals(portable(repository), result.registration().repositoryRoot());
    assertEquals(portable(module), result.registration().moduleRoot());
    assertEquals(portable(module), result.registration().mavenExecutionRoot());
    assertEquals("pom.xml", result.registration().pomPath());
}
```

Also cover identical idempotent registration, ID conflict, module-path conflict, missing POM, malformed existing `project.json`, and a before/after recursive snapshot proving no file under repository/module changed.

- [ ] **Step 5: Run and verify RED**

```powershell
mvn -pl case-management -am -Dtest=ProjectRegistrationRepositoryTest,ProjectRegistryTest test
```

Expected: compilation fails because repository and registry are missing.

- [ ] **Step 6: Implement project persistence and registration**

Canonicalize existing module paths with `toRealPath()`. Require `moduleRoot/pom.xml` to be a regular file. Compute the POM SHA-256 without following another arbitrary input path. Store absolute paths with `/` separators. Scan only `workspace/projects/*/project.json` for conflicts; never scan the target repository. Create project knowledge/cases directories before atomically creating `project.json`.

- [ ] **Step 7: Verify GREEN and audit Task 4**

```powershell
mvn -pl case-management -am test
```

Audit Windows path normalization, no target writes, same-repository multiple-module behavior, deterministic ID/hash, conflict ordering, and preserved causes.

- [ ] **Step 8: Commit Task 4**

```powershell
git add -- case-management/src/main/java/org/example/algorithmdebug/casecore/RepositoryRootLocator.java case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectIdGenerator.java case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepository.java case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistry.java case-management/src/test/java/org/example/algorithmdebug/casecore/RepositoryRootLocatorTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectIdGeneratorTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistrationRepositoryTest.java case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistryTest.java
git diff --cached --check
git commit -m "feat: register Maven algorithm modules"
```

---

### Task 5: Fixed-layer Workspace configuration resolution

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceConfigurationResolver.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceConfigurationResolverTest.java`

**Interfaces:**
- Consumes: `WorkspaceLayout`, `ProjectId`, `BoundedDocumentMapper`, `WorkspaceTemplateProvider`, Jackson `JsonNode/ObjectNode`.
- Produces: `JsonNode resolve(WorkspaceLayout layout, String documentName, Optional<ProjectId> projectId, ObjectNode cliOverrides)`.

- [ ] **Step 1: Write failing precedence tests**

```java
@Test
void shouldApplyCliProjectWorkspaceDefaultPriority() throws Exception {
    ObjectNode resolved = (ObjectNode) resolver.resolve(
            layout, "application", Optional.of(projectId),
            jsonObject("offline", true));

    assertTrue(resolved.path("offline").booleanValue());
    assertEquals("project", resolved.path("sourceLabel").textValue());
}
```

Use test-only documents to prove nested object merge, scalar replacement, array replacement, schema mismatch rejection, unknown document rejection, malformed YAML, and 1 MiB limits. Do not add production Collector settings.

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -pl case-management -am -Dtest=WorkspaceConfigurationResolverTest test
```

Expected: compilation fails because the resolver is missing.

- [ ] **Step 3: Implement the fixed four-layer merge**

Allow only `application`, `execution`, `collection-limits`, and `security-policy`. Merge in this order: built-in → Workspace → project → CLI. Object nodes merge recursively; scalars and arrays replace. Every file layer must contain the same supported `schemaVersion`; CLI overrides must not contain `schemaVersion`.

- [ ] **Step 4: Verify GREEN and audit Task 5**

```powershell
mvn -pl case-management -am test
```

Audit no arbitrary filename injection, no unbounded tree, deterministic merge order, and no invented dynamic-collector fields.

- [ ] **Step 5: Commit Task 5**

```powershell
git add -- case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceConfigurationResolver.java case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceConfigurationResolverTest.java
git diff --cached --check
git commit -m "feat: resolve layered workspace configuration"
```

---

### Task 6: Core Workspace, Project, and Doctor use cases

**Files:**
- Modify: `ada-core/pom.xml`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/WorkspaceApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/ProjectApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/MavenExecutableLocator.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/DoctorApplicationService.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/WorkspaceApplicationServiceTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/ProjectApplicationServiceTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/MavenExecutableLocatorTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/DoctorApplicationServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1-5 APIs; injected Java feature supplier, environment map, PATH separator, and filesystem paths.
- Produces:
  - `WorkspaceApplicationService.initialize(Path): WorkspaceInitializationResult`
  - `ProjectApplicationService.register(Path workspace, Path module, Optional<ProjectId>): ProjectRegistrationResult`
  - `MavenExecutableLocator.locate(Optional<Path> explicit): Optional<Path>`
  - `DoctorApplicationService.diagnose(Path workspace, Optional<Path> module, Optional<Path> explicitMaven): DoctorReport`

- [ ] **Step 1: Write failing Use Case delegation tests**

Use real temp-directory domain services rather than mocking filesystem behavior. Assert Workspace and Project services return the exact Task 1 DTOs and do not print output.

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -pl ada-core -am -Dtest=WorkspaceApplicationServiceTest,ProjectApplicationServiceTest test
```

Expected: compilation fails because Core services do not exist.

- [ ] **Step 3: Implement thin application services**

Core services receive domain dependencies through constructors and contain no JSON/YAML or CLI parsing. Add `ada-contracts` and `case-management` dependencies to `ada-core/pom.xml`.

- [ ] **Step 4: Write Maven locator and Doctor regression tests**

Cover priority:

```text
explicit executable > MAVEN_HOME/bin > M2_HOME/bin > PATH entries
```

On Windows probe `mvn.cmd`, then `mvn.bat`, then `mvn.exe`; on non-Windows probe `mvn`. Include a regression where `maven.home` is absent and every source is empty: the result must be `Optional.empty()` and Doctor must return `MAVEN_NOT_FOUND`, never an NPE.

Doctor tests must assert all checks remain present after one FAIL, Java feature below 21 fails, missing Workspace Manifest fails, invalid optional module/POM fails, and the write probe leaves no file behind under `workspace/system`.

- [ ] **Step 5: Run and verify RED**

```powershell
mvn -pl ada-core -am -Dtest=MavenExecutableLocatorTest,DoctorApplicationServiceTest test
```

Expected: compilation fails because locator and Doctor service are missing.

- [ ] **Step 6: Implement locator and Doctor**

Do not invoke a shell and do not inspect credential variables. Maven detection checks only explicit path, `MAVEN_HOME`, `M2_HOME`, and PATH. Doctor executes a fixed maximum of checks, records all results, and builds `DoctorReport.fromChecks`.

- [ ] **Step 7: Verify GREEN and audit Task 6**

```powershell
mvn -pl ada-core -am test
```

Audit Core’s absence of CLI/Jackson types, missing-Maven behavior, write-probe cleanup, target read-only behavior, and fixed check count.

- [ ] **Step 8: Commit Task 6**

```powershell
git add -- ada-core/pom.xml ada-core/src/main ada-core/src/test
git diff --cached --check
git commit -m "feat: add workspace control plane use cases"
```

---

### Task 7: Bounded executable `ada` CLI

**Files:**
- Modify: `algorithm-debug-cli/pom.xml`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommand.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliResponseWriter.java`
- Create: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliArgumentsTest.java`
- Create: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/AdaMainTest.java`

**Interfaces:**
- Consumes: Task 1 ToolResponse/DTOs, Task 6 services, Jackson Databind/JavaTime.
- Produces:
  - sealed `CliCommand` records for workspace init, project register, and doctor;
  - `CliArguments.parse(String[]): CliCommand`;
  - `AdaMain.run(String[] args, PrintStream stdout, PrintStream stderr): int`;
  - executable shaded JAR with `org.example.algorithmdebug.cli.AdaMain` Main-Class.

- [ ] **Step 1: Write failing strict-parser tests**

Assert exact command parsing and rejection of unknown commands, unknown options, duplicate options, missing values, empty values, extra positional arguments, and `--project-id` on the wrong command.

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -pl algorithm-debug-cli -am -Dtest=CliArgumentsTest test
```

Expected: compilation fails because CLI classes are missing.

- [ ] **Step 3: Implement minimal sealed commands and parser**

Use records:

```java
sealed interface CliCommand {
    record WorkspaceInit(Path root) implements CliCommand {}
    record ProjectRegister(Path workspace, Path module, Optional<ProjectId> projectId)
            implements CliCommand {}
    record Doctor(Path workspace, Optional<Path> module) implements CliCommand {}
}
```

The parser performs no filesystem IO.

- [ ] **Step 4: Write failing CLI response tests**

Call `AdaMain.run` with temp directories and captured streams. Parse stdout as `ToolResponse` and assert there is no prefix/suffix text. Cover successful initialize/register/doctor; invalid arguments; unsupported Workspace Schema; missing POM; internal exception sanitization; exit codes 0/2/3/10; and stderr not appearing in stdout.

- [ ] **Step 5: Run and verify RED**

```powershell
mvn -pl algorithm-debug-cli -am -Dtest=AdaMainTest test
```

Expected: compilation fails because executor/writer/main are missing.

- [ ] **Step 6: Implement CLI composition and ToolResponse serialization**

`CliCommandExecutor` calls only Core services. `CliResponseWriter` uses one preconfigured ObjectMapper and rejects serialized responses above 1 MiB before writing. `AdaMain` maps known domain errors to stable codes and never writes stack traces to stdout or stderr; unexpected causes are retained in the thrown/logged local diagnostic path without exposing secrets.

- [ ] **Step 7: Configure and verify the shaded JAR**

Add `ada-contracts` and `ada-core` dependencies and configure Maven Shade Plugin with `AdaMain` as Main-Class. Run:

```powershell
mvn -pl algorithm-debug-cli -am package
$jar = Get-ChildItem algorithm-debug-cli\target\*-all.jar | Select-Object -First 1
$tempWorkspace = Join-Path $env:TEMP ("ada-workspace-" + [guid]::NewGuid())
java -jar $jar.FullName workspace init --root $tempWorkspace
```

Expected: exit code 0; stdout is one ToolResponse JSON document; the external temporary Workspace contains `workspace.yaml`; no target repository is modified.

- [ ] **Step 8: Verify GREEN and audit Task 7**

```powershell
mvn -pl algorithm-debug-cli -am test
node --test integrations/opencode/test/ada-cli.test.mjs
```

Audit stdout/stderr separation, JSON size, dependency direction, absence of a framework, CLI error mapping, and no per-command Agent compilation.

- [ ] **Step 9: Commit Task 7**

```powershell
git add -- algorithm-debug-cli/pom.xml algorithm-debug-cli/src
git diff --cached --check
git commit -m "feat: add external workspace cli"
```

---

### Task 8: Configuration/document convergence and full verification

**Files:**
- Modify: `config/application-default.yaml`
- Modify: `config/collection-limits.yaml`
- Create: `config/execution.yaml`
- Modify: `config/README.md`
- Modify: `README.md`
- Modify: `ada-contracts/README.md`
- Modify: `case-management/README.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/architecture/algorithm-debug-agent-complete-design.md`
- Modify: `docs/designs/2026-08-12-case-context-run-outcome-multiturn-analysis-design.md`
- Modify: `docs/decisions/ADR-007-opencode-adapter-via-cli.md`
- Modify: `integrations/opencode/README.md`
- Modify: `docs/superpowers/specs/2026-08-16-external-workspace-control-plane-design.md`

**Interfaces:**
- Consumes: completed Tasks 1-7 behavior and commands.
- Produces: one current architectural description, accurate status labels, synchronized defaults, and implementation evidence.

- [ ] **Step 1: Update clean configuration sources**

Remove `caseRoot` from `application-default.yaml`, keep `offline: true`, add Schema-only `execution.yaml`, set `maxCodePathRuns: 3`, and document precedence as CLI → Workspace project → Workspace user → built-in. Ensure source config values match Task 3 classpath templates.

- [ ] **Step 2: Update active documentation without claiming future features**

Replace target-repository `.algorithm-debug` defaults with external Workspace paths. Replace `projectRoot` with repository/module/execution roots where required. Remove active complex state-machine, `TURN-*`, fixed three-Baseline, direct OpenCode→JDWP-MCP, and `/debug-case` fallback wording. Keep historical documents clearly labeled rather than deleting them.

Update status text to say only Workspace init/register/doctor and CLI are implemented; Case repositories, Input Analysis, OpenCode installer, collectors, Evidence and end-to-end `/debug-case` remain unimplemented.

- [ ] **Step 3: Run deterministic documentation/config checks**

```powershell
rg -n "caseRoot:.*\.algorithm-debug|<target-project>/\.algorithm-debug|目标项目\.algorithm-debug" config docs README.md
rg -n "TURN-[0-9]|WorkflowStateMachine|BaselineRunner运行3次" docs/architecture docs/designs
rg -n "OpenCode.*JDWP-MCP|/debug-case.*回退" docs integrations/opencode
```

Expected: no active-design matches. Matches in explicitly historical sections must be accompanied by a nearby `历史` or `Superseded` marker and reviewed manually.

- [ ] **Step 4: Run affected and root tests**

```powershell
mvn -pl ada-contracts,case-management,ada-core,algorithm-debug-cli -am test
mvn test
node --test integrations/opencode/test/ada-cli.test.mjs
```

Expected: all tests pass. The real `hellomvn` Baseline test may remain skipped in the default root run because this slice does not modify Harness execution.

- [ ] **Step 5: Run a fresh external-Workspace CLI acceptance**

Use a fresh temporary Workspace and a temporary independent Maven module fixture. Execute init, register twice, and doctor. Verify the second register returns `created=false`, all files are under the temporary Workspace, and the fixture’s recursive file/hash snapshot is unchanged.

- [ ] **Step 6: Perform module-by-module code audit**

Audit in order: Contracts → Case Management → Core → CLI. Check public Javadoc, error causes, path boundaries, atomic writes, idempotence, multi-module semantics, resource limits, stdout/stderr, dependency direction, and test determinism. For every defect found, first add a failing regression test, then fix and rerun the affected module.

- [ ] **Step 7: Update implementation record and inspect the complete diff**

Set the design status to `Implemented` only after all verification passes and record exact commands/results. Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Because several listed documentation files were already modified before this plan, do not stage them wholesale without reviewing their complete diff. Stage only verified Task 8 hunks or keep them uncommitted and report the exact reason; never absorb unrelated user changes into the feature commit.

- [ ] **Step 8: Commit verified Task 8 files**

Stage clean configuration files, the implementation-record hunk, and only reviewed documentation hunks. Inspect `git diff --cached --name-only` and `git diff --cached` before committing:

```powershell
git diff --cached --check
git commit -m "docs: align external workspace control plane"
```

---

## Plan self-review

- Spec coverage: all approved 0.2 requirements map to Tasks 1-8; deferred Input/Case/Collector/OpenCode installer work is explicitly excluded.
- File ownership: every new class has one responsibility and a declared consumer/producer boundary.
- Type consistency: `ProjectRegistration` uses `repositoryRoot`, `moduleRoot`, `mavenExecutionRoot`, and `pomPath` in Contracts, registry, Core, CLI, schemas, and tests.
- TDD: every behavior task starts with a named failing test and an explicit RED command before production implementation.
- Compatibility: no existing Baseline or RunOutcome Schema is replaced; `CaseWorkspace.create(casesRoot, caseId)` remains available.
- Safety: target module/repository read-only behavior, Workspace containment, create-new persistence, size limits, and sensitive-output rules all have tests.
- Scope: no speculative `workspace-management` module, MCP, Input Analysis, Collector, Evidence, installer, or alternate runtime is introduced.
- Dirty-worktree discipline: commits list exact files and Task 8 calls out pre-existing overlapping documentation changes.
