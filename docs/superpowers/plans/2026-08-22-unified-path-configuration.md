# Unified Path Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Centralize all user-configurable paths in one Agent repository configuration, install the resolved values into the existing OpenCode installation module, and remove per-target-project path configuration.

**Architecture:** `config/agent-settings.json` is the only user-edited configuration. The PowerShell installer expands its explicit Windows defaults and generates the existing `installation.mjs`; OpenCode passes the resolved Workspace and absolute result directory to the existing Java CLI, while Java persists the value in ProjectRegistration and keeps read-only compatibility for historical relative registrations.

**Tech Stack:** PowerShell, JSON/JSON Schema, Java 21, Maven, JUnit 5, Node.js test runner, TypeScript/OpenCode Custom Tools.

**Spec:** `docs/designs/2026-08-22-unified-path-configuration-design.md`

## Global Constraints

- Do not add `agent-settings.local.json`, `runtime.json`, a database, a daemon, or a second configuration precedence chain.
- Keep internal subprocess path arguments; remove only user-facing path configuration parameters.
- Keep existing `PRESENT`, `ABSENT`, and `AgentFailureDiagnostic`; do not add result-directory status enums.
- Do not modify target algorithm source, UTs, POMs, or repositories at runtime.
- All new program and installer error messages are English.
- Preserve append-only Workspace evidence and read compatibility for historical relative result directories.
- Do not add third-party dependencies.

---

### Task 1: Version the single Agent settings contract

**Files:**

- Create: `config/agent-settings.json`
- Create: `schemas/config/agent-settings-v1.schema.json`
- Modify: `config/README.md`
- Modify: `integrations/opencode/test/configuration-assets.test.mjs`

**Interfaces:**

- Produces: required fields `schemaVersion`, `openCodeConfigDirectory`, `workspaceDirectory`, `dfxDirectory`, `evalDirectory`, `resultJsonDirectory`, and `dfxEnabled`.
- Consumes: only `%USERPROFILE%` and `%LOCALAPPDATA%` variable syntax in path strings.

- [ ] **Step 1: Add a failing configuration asset test**

Add assertions that `config/agent-settings.json` is strict UTF-8 JSON, contains exactly the seven required fields, contains the explicit defaults from the design, and has no unknown fields.

- [ ] **Step 2: Run the Node test and confirm the settings file is missing**

Run:

```powershell
node --test integrations/opencode/test/configuration-assets.test.mjs
```

Expected: FAIL because `config/agent-settings.json` and its Schema do not exist.

- [ ] **Step 3: Add the settings JSON and Schema**

Use this exact settings shape:

```json
{
  "schemaVersion": "1.0",
  "openCodeConfigDirectory": "%USERPROFILE%\\.config\\opencode",
  "workspaceDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\workspace",
  "dfxDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\diagnostics",
  "evalDirectory": "%LOCALAPPDATA%\\algorithm-debug-agent\\evals",
  "resultJsonDirectory": "D:\\javacode\\hellomvn\\output\\algorithm-results",
  "dfxEnabled": true
}
```

The Schema must set `additionalProperties: false`, require every field, fix `schemaVersion` to `1.0`, require non-empty strings for paths, and require a boolean for `dfxEnabled`.

- [ ] **Step 4: Update configuration documentation**

Replace the current “CLI override first” text with one source rule: explicit repository settings, resolved by the installer, with no user command override.

- [ ] **Step 5: Run the focused Node test**

Run:

```powershell
node --test integrations/opencode/test/configuration-assets.test.mjs
```

Expected: PASS.

### Task 2: Resolve settings in the OpenCode installer

**Files:**

- Modify: `scripts/install-opencode.ps1`
- Modify: `scripts/verify-opencode-installer.ps1`
- Modify: `integrations/opencode/lib/installation.mjs`

**Interfaces:**

- Produces: installed `installation` object with `launcher`, `workspaceDirectory`, `dfxDirectory`, `evalDirectory`, `resultJsonDirectory`, and `dfxEnabled`.
- Consumes: `config/agent-settings.json`, `$env:USERPROFILE`, `$env:LOCALAPPDATA`, and installer `Mode` only.

- [ ] **Step 1: Change the installer verification to the desired no-path-parameter API**

Remove `-ConfigRoot` and `-RepositoryRoot` from verifier calls. Save and restore `USERPROFILE` and `LOCALAPPDATA`, point both to the verifier temporary root, and assert the installed module contains fully resolved paths under that root plus the configured absolute result directory.

- [ ] **Step 2: Add verifier assertions for invalid settings**

Exercise missing required field, unsupported `%VARIABLE%`, non-absolute resolved path, non-boolean `dfxEnabled`, and unknown field. Each case must fail before OpenCode discovery with one English error code prefixed `ADA_INSTALL_CONFIG_`.

- [ ] **Step 3: Run verifier and confirm the old installer interface fails**

Run:

```powershell
pwsh -NoProfile -File scripts/verify-opencode-installer.ps1
```

Expected: FAIL because the installer still accepts path parameters and only emits `defaultLauncher`.

- [ ] **Step 4: Implement strict settings resolution in the installer**

Remove `ConfigRoot` and `RepositoryRoot` parameters. Derive the repository from `$PSScriptRoot`; load `config/agent-settings.json`; reject unknown or missing fields; expand only `%USERPROFILE%` and `%LOCALAPPDATA%`; normalize all paths with `System.IO.Path.GetFullPath`; require every expanded path to be fully qualified.

- [ ] **Step 5: Generate the expanded installation object**

Replace the one-line `defaultLauncher` module with:

```javascript
export const installation = Object.freeze({
  launcher: "...",
  workspaceDirectory: "...",
  dfxDirectory: "...",
  evalDirectory: "...",
  resultJsonDirectory: "...",
  dfxEnabled: true,
})
```

Use `ConvertTo-Json -Compress` for every string and boolean value. Preserve atomic writes, backups, idempotence, capability discovery, and byte-for-byte Check behavior.

- [ ] **Step 6: Create and validate configured directories**

Create OpenCode, Workspace, DFX, and Eval directories. Do not create `resultJsonDirectory`; only verify that an existing value is not a regular file during installation.

- [ ] **Step 7: Print effective paths in Install and Check output**

Emit one bounded English line per configured path so users can see the defaults and custom values that took effect.

- [ ] **Step 8: Run the installer verifier**

Run:

```powershell
pwsh -NoProfile -File scripts/verify-opencode-installer.ps1
```

Expected: `OPENCODE_INSTALLER_VERIFIED`.

### Task 3: Inject installed paths into the OpenCode Tool Runtime

**Files:**

- Modify: `integrations/opencode/tools/algorithm-debug.ts`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `integrations/opencode/test/tool-runtime.test.mjs`
- Modify: `integrations/opencode/test/configuration-assets.test.mjs`

**Interfaces:**

- Consumes: `installation.launcher`, `installation.workspaceDirectory`, and `installation.resultJsonDirectory`.
- Produces: internal `project register --result-directory <absolute-path>` invocation.

- [ ] **Step 1: Change Tool Runtime tests to inject explicit installed settings**

Construct the runtime with:

```javascript
createAlgorithmDebugRuntime({
  execute,
  workspaceDirectory: "D:/ada-workspace",
  resultJsonDirectory: "D:/algorithm-results",
  temporaryRoot,
})
```

Assert every business command retains `--workspace D:/ada-workspace` and every project preparation call includes `--result-directory D:/algorithm-results`.

- [ ] **Step 2: Add rejection tests for missing or relative installed paths**

Assert construction throws for blank Workspace, blank result directory, relative Workspace, and relative result directory. Do not add runtime status objects.

- [ ] **Step 3: Run the Tool Runtime tests and confirm failure**

Run:

```powershell
node --test integrations/opencode/test/tool-runtime.test.mjs
```

Expected: FAIL because Runtime still resolves `ADA_WORKSPACE` internally and does not register the result path.

- [ ] **Step 4: Replace implicit Workspace resolution with constructor settings**

Change the runtime factory signature to require `workspaceDirectory` and `resultJsonDirectory`; remove `workspacePath()`, `homedir`, platform branching, and normal `ADA_WORKSPACE` lookup. Keep temporary file handling unchanged.

- [ ] **Step 5: Wire the installed object into the OpenCode Tool**

Import `installation` instead of `defaultLauncher`. Use `installation.launcher`, pass installed paths to the runtime, and remove the user-facing `ADA_CLI` executable override. Permit only `ADA_EVAL_WORKSPACE` as an internal Eval isolation override.

- [ ] **Step 6: Run focused OpenCode tests**

Run:

```powershell
node --test integrations/opencode/test/tool-runtime.test.mjs integrations/opencode/test/configuration-assets.test.mjs
```

Expected: PASS.

### Task 4: Remove target-project configuration and accept installed absolute result paths

**Files:**

- Delete: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectConfigurationLoader.java`
- Delete: `schemas/workspace/project-configuration-v1.schema.json`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistry.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ProjectRegistration.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseOpenResult.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ProjectResultSource.java`
- Modify: `schemas/workspace/project-registration-v1.schema.json`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/ProjectRegistryTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneContractsTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/WorkspaceControlPlaneJsonTest.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/ProjectApplicationServiceTest.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java`

**Interfaces:**

- Consumes: absolute result directory passed by the internal CLI project registration command.
- Produces: ProjectRegistration storing the installed absolute path; legacy relative registrations remain readable.

- [ ] **Step 1: Replace project configuration tests with central-path tests**

Delete tests that create `.algorithm-debug-agent.json`. Add tests proving an absolute configured result directory is stored and updated on idempotent registration, and target project files are never read for Agent configuration.

- [ ] **Step 2: Add contract compatibility tests**

Assert new absolute paths round-trip through JSON. Retain a test that reads a historical relative `resultJsonDirectory` and resolves it against `moduleRoot`; reject blank values and unsafe legacy traversal.

- [ ] **Step 3: Run affected Java tests and confirm failure**

Run:

```powershell
mvn -pl ada-contracts,case-management,ada-core -am test
```

Expected: FAIL because the current contract rejects absolute result paths and ProjectRegistry still reads the target project configuration file.

- [ ] **Step 4: Remove ProjectConfigurationLoader from ProjectRegistry**

Delete the field, constructor initialization, and fallback `.or(() -> projectConfigurationLoader.load(...))`. Keep idempotent registration update from the caller-provided value.

- [ ] **Step 5: Support new absolute values and historical relative values**

Change `ProjectRegistration.validateResultJsonDirectory` to normalize an absolute path or validate a historical portable relative path. Update `ProjectResultSource.from` to use an absolute value directly and resolve only a legacy relative value against `moduleRoot`.

- [ ] **Step 6: Update the registration Schema**

Allow a normalized Windows absolute result path and the historical portable relative form. Remove the standalone project configuration Schema.

- [ ] **Step 7: Keep existing result outcomes unchanged**

Do not modify `GanttOutcome`, `ScheduleProducingTestRunner`, `OutputDirectorySnapshotter`, or `ScheduleResultCapture`. Existing missing, unreadable, ambiguous, and invalid JSON behavior remains authoritative.

- [ ] **Step 8: Run affected Java tests**

Run:

```powershell
mvn -pl ada-contracts,case-management,ada-core -am test
```

Expected: PASS.

### Task 5: Keep the result path CLI argument internal and update integration fixtures

**Files:**

- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliArgumentsTest.java`
- Modify: `integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java`
- Modify: `algorithm-debug-cli/README.md`

**Interfaces:**

- Consumes: internal `project register --result-directory` call from Tool Runtime.
- Produces: the existing CLI command model carrying an absolute result path into ProjectApplicationService.

- [ ] **Step 1: Change CLI tests to an absolute result directory**

Replace `output/algorithm-results` with a temporary absolute path and retain strict argument parsing assertions. The CLI argument remains because it is an internal JS-to-Java subprocess contract, not a user configuration entry.

- [ ] **Step 2: Remove target configuration files from integration fixtures**

Stop writing `.algorithm-debug-agent.json`; pass the fixture output absolute directory through project registration instead.

- [ ] **Step 3: Run CLI and integration tests**

Run:

```powershell
mvn -pl algorithm-debug-cli,integration-tests -am test
```

Expected: PASS.

- [ ] **Step 4: Remove public CLI documentation for result path configuration**

Document `project register` as an internal adapter contract. User setup must point to `config/agent-settings.json`, not `--result-directory`.

### Task 6: Remove user JAR path overrides while preserving internal launcher transport

**Files:**

- Modify: `bin/ada.cmd`
- Delete: `bin/ada.local.example.cmd`
- Modify: `.gitignore`
- Modify: `scripts/verify-ada-launcher.ps1`
- Modify: `bin/README.md`
- Modify: `tools/jdwp-collector/README.md`

**Interfaces:**

- Produces: launcher always locates CLI, CodePath, and JDWP artifacts relative to its repository.
- Consumes: `JAVA_HOME` or `PATH` for Java only.

- [ ] **Step 1: Change launcher verification to run without repository or demo path parameters**

Derive the repository from `$PSScriptRoot`. Use the current working directory only when a Maven project check is required. Remove the temporary JDWP environment override from the verifier.

- [ ] **Step 2: Remove `ada.local.cmd` loading and user JAR override branches**

Set internal `ADA_CLI_JAR`, `ADA_CODEPATH_LAUNCHER_JAR`, and `ADA_JDWP_COLLECTOR_JAR` from repository-relative paths on every launcher invocation. Keep these environment variables only as launcher-to-Java transport.

- [ ] **Step 3: Remove obsolete example and ignore rule**

Delete `bin/ada.local.example.cmd` and `/bin/ada.local.cmd` from `.gitignore`.

- [ ] **Step 4: Run launcher verification**

Run:

```powershell
pwsh -NoProfile -File scripts/verify-ada-launcher.ps1
```

Expected: `ADA launcher verification passed`.

### Task 7: Remove user-facing Eval path arguments

**Files:**

- Modify: `scripts/run-agent-evals.ps1`
- Modify: `agent-evals/run.mjs`
- Modify: `agent-evals/test/run.test.mjs`
- Modify: `agent-evals/README.md`
- Modify: `docs/designs/2026-08-21-agent-eval-harness-design.md`

**Interfaces:**

- Consumes: current PowerShell working directory as TargetModule and `evalDirectory` from Agent settings.
- Produces: internal Node arguments and per-Eval isolated `ADA_EVAL_WORKSPACE`.

- [ ] **Step 1: Update Eval tests for current-directory targeting**

Remove user-facing `TargetModule` and `OutputRoot` expectations. Assert the PowerShell wrapper resolves its current directory and configured Eval root before invoking Node.

- [ ] **Step 2: Rename Eval-only Workspace transport**

Set `ADA_EVAL_WORKSPACE` instead of `ADA_WORKSPACE` when the Node runner launches OpenCode. Keep the generated path under one Eval run directory.

- [ ] **Step 3: Remove PowerShell path parameters**

Keep only Suite, Case, Model, TimeoutSeconds, and FailFast. Resolve TargetModule with `Get-Location`; read and expand `evalDirectory` from the Agent settings file; pass both to Node internally.

- [ ] **Step 4: Run Eval Harness unit tests**

Run:

```powershell
node --test agent-evals/test/*.test.mjs
```

Expected: PASS.

### Task 8: Align DFX path design with installation settings

**Files:**

- Modify: `docs/designs/2026-08-21-agent-dfx-observability-design.md`
- Modify: `docs/designs/2026-08-22-unified-path-configuration-design.md`

**Interfaces:**

- Consumes: `installation.dfxDirectory` and `installation.dfxEnabled`.
- Produces: one future DFX event log at `<dfxDirectory>/sessions/<sessionId>/events.jsonl`.

- [ ] **Step 1: Remove `ADA_WORKSPACE` as the DFX path source**

Replace it with the installed DFX directory and keep the Plugin failure non-blocking.

- [ ] **Step 2: Preserve DFX scope boundaries**

Do not implement the Plugin in this path-config change. Retain single JSONL, redaction, size budgets, PowerShell viewer, and diagnostic-only semantics for the later DFX implementation plan.

### Task 9: Synchronize Agent instructions and user documentation

**Files:**

- Modify: `README.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `docs/current-capabilities.md`
- Modify: `skills/algorithm-debug/SKILL.md`
- Modify: `integrations/opencode/agents/algorithm-debug.md`
- Modify: `integrations/opencode/README.md`
- Modify: `adapters/maven-junit-adapter/README.md`
- Modify: `adapter-sdk/README.md`
- Create: `docs/decisions/ADR-012-central-installed-path-configuration.md`

**Interfaces:**

- Produces: one documented user workflow and one superseding architecture decision.

- [ ] **Step 1: Add the new ADR**

Record that ADR-012 supersedes the project-local result configuration portion of ADR-011. State that one Agent settings file is installed, paths may be customized there, absolute algorithm result paths are supported, and target repositories remain unmodified.

- [ ] **Step 2: Replace all user setup examples**

Remove `.algorithm-debug-agent.json`, `--result-directory`, `-ConfigRoot`, `-RepositoryRoot`, `-TargetModule`, `-OutputRoot`, `ADA_WORKSPACE`, and JAR override instructions from current user documentation.

- [ ] **Step 3: Update Skill behavior without adding states**

Keep `analysis_begin.resultJsonDirectory`; state that it comes from installed Agent settings. When `GanttOutcome.ABSENT`, explain that no JSON was captured from the configured directory without asserting the path is wrong.

- [ ] **Step 4: Add exact install/customize/check instructions**

Show the explicit defaults in `config/agent-settings.json`, how to replace Workspace with an absolute custom value, how to rerun Install and Check, and that changing Workspace does not migrate old evidence.

- [ ] **Step 5: Run documentation literal searches**

Run:

```powershell
rg -n "\.algorithm-debug-agent\.json|--result-directory|-ConfigRoot|-RepositoryRoot|-TargetModule|-OutputRoot|ADA_WORKSPACE|ada\.local\.cmd" README.md docs skills integrations adapter-sdk adapters bin tools
```

Expected: no active user instruction remains; historical designs are marked superseded rather than silently rewritten where preservation is required.

### Task 10: Full regression and real OpenCode acceptance

**Files:**

- No production file changes expected.

**Interfaces:**

- Validates: settings, installer, Java contracts, OpenCode adapter, Eval isolation, normal UT result capture, and historical Workspace compatibility.

- [ ] **Step 1: Run Node and PowerShell checks**

```powershell
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
pwsh -NoProfile -File scripts/verify-opencode-installer.ps1
pwsh -NoProfile -File scripts/verify-ada-launcher.ps1
```

Expected: all pass.

- [ ] **Step 2: Run the Maven reactor**

```powershell
mvn test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Install using the repository settings**

```powershell
pwsh -NoProfile -File scripts/install-opencode.ps1 -Mode Install
pwsh -NoProfile -File scripts/install-opencode.ps1 -Mode Check
```

Expected: installed Agent assets are discovered and Check prints the configured OpenCode, Workspace, DFX, Eval, and result paths.

- [ ] **Step 4: Run a real target UT through OpenCode**

Start OpenCode in the target Maven module, select `algorithm-debug`, and analyze one UT that writes a timestamped JSON into the configured absolute result directory. Confirm `GanttOutcome.PRESENT`, immutable Artifact registration, and no target-project configuration file.

- [ ] **Step 5: Validate the existing missing-result behavior**

Run a UT that produces no result JSON. Confirm `GanttOutcome.ABSENT`, no new result-directory status object, and a model answer that identifies missing evidence without declaring the path wrong.

- [ ] **Step 6: Validate custom Workspace**

Change `workspaceDirectory` in `config/agent-settings.json`, reinstall, restart OpenCode, and confirm new Runs use the custom directory while the old Workspace remains untouched.

- [ ] **Step 7: Validate historical relative registration compatibility**

Open a copied historical Workspace registration containing `output/algorithm-results`. Confirm it remains readable and resolves relative to its recorded module root.
