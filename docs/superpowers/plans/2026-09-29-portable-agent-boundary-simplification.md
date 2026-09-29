# Portable Agent Boundary Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove inert and duplicated host-facing contracts while making optional knowledge delivery and conclusion finalization deterministic, bounded, and testable.

**Architecture:** Keep the 17-tool Java MCP control plane unchanged. Make the capability manifest the sole distributable tool-permission snapshot, append bounded knowledge only in the host renderer, and return a typed `ConclusionFinalization` from the existing `analysis_finalize` action.

**Tech Stack:** Java 21, Maven, JUnit 5, JSON Schema 2020-12, Node.js built-in test runner, PowerShell/Qwen CLI adapter.

**Spec:** `docs/designs/2026-09-29-portable-agent-boundary-simplification-design.md`

## Global Constraints

- Do not add a tool, model loop, framework, database, queue, or target-source instrumentation.
- Preserve all Coordinator, evidence eligibility, predicate, conclusion gate, append-only archive, and 17-tool semantics.
- Write and observe all RED tests before production changes.
- Do not modify, stage, or commit `docs/sharing/`.
- Do not commit or push without explicit user authorization.
- Keep knowledge out of Evidence and reject rather than truncate over-budget knowledge.

## Review Focus

- A knowledge directory containing a symlink must fail before replacing an existing installation.
- Nested Markdown ordering must be stable across filesystem enumeration order.
- A rejected conclusion must remain a successful gate evaluation while carrying `decision=REJECTED` and next actions.
- Agent Definition must contain no copied tool list and the rendered Qwen permissions must still equal the Java catalog snapshot.
- Generic MCP results must retain their current output schema while only `analysis_finalize` gets typed finalization data.

---

### Task 1: Freeze the simplified contracts with RED tests

**Files:**
- Modify: `agent-definition/test/agent-definition.test.mjs`
- Modify: `integrations/host-adapter-kit/test/render-host-config.test.mjs`
- Modify: `integrations/qwen-cli/test/installer.test.mjs`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationSchemaTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/ConclusionFinalizationTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/CoreActionRegistryTest.java`
- Modify: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpToolCatalogTest.java`
- Modify: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpPackagingTest.java`

**Interfaces:**
- Produces: executable expectations for Agent Profile 2.0, bounded knowledge, Finalization v1, and typed finalize output.
- Consumes: current Profile 1.0 and Decision-only behavior, which must fail for the intended reasons.

- [x] Write all named tests with exact assertions from the spec.
- [x] Run Node tests and targeted Maven tests.
- [x] Confirm failures are missing Profile 2.0 fields/knowledge content/Finalization type, not fixture errors.

### Task 2: Simplify Agent Definition and tool-permission source

**Files:**
- Modify: `agent-definition/algorithm-debug-agent-v1.json`
- Modify: `schemas/agent/algorithm-debug-agent-v1.schema.json`
- Modify: `integrations/host-adapter-kit/scripts/render-host-config.mjs`
- Delete: `agent-definition/completion-contract-v1.schema.json`
- Modify: `algorithm-debug-mcp-server/pom.xml`
- Modify: `scripts/build-agent.ps1`

**Interfaces:**
- Produces: Profile 2.0 with one tool-permission snapshot source and only Tools as a required MCP feature.
- Consumes: Task 1 contract tests and existing Capability Manifest.

- [x] Make Agent Definition and schema reject the removed fields.
- [x] Read `capabilityManifest.tools` directly and validate uniqueness/name syntax/non-empty content.
- [x] Remove Completion Contract packaging/hash checks.
- [x] Run Agent Definition, renderer, packaging and build-script parsing tests GREEN.

### Task 3: Deliver optional knowledge with hard budgets

**Files:**
- Modify: `integrations/host-adapter-kit/scripts/render-host-config.mjs`
- Modify: `integrations/host-adapter-kit/test/render-host-config.test.mjs`
- Modify: `integrations/qwen-cli/test/installer.test.mjs`

**Interfaces:**
- Produces: `knowledge.status/files/totalBytes` and optional delimited Markdown appended to the rendered Agent.
- Consumes: `definition.knowledge.delivery/limits` and an optional absolute directory.

- [x] Implement deterministic recursive `.md` discovery without following symbolic links.
- [x] Enforce depth, count, per-file, aggregate and strict UTF-8 limits before rendering.
- [x] Append only relative path, SHA-256, size and content after the canonical prompt.
- [x] Run renderer and installer tests GREEN, including atomic preservation on invalid knowledge.

### Task 4: Return a canonical server-owned conclusion finalization

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionFinalization.java`
- Create: `schemas/coordination/conclusion-finalization-v1.schema.json`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionHandlers.java`
- Modify: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolCatalog.java`
- Modify: `algorithm-debug-mcp-server/pom.xml`

**Interfaces:**
- Produces: `ConclusionFinalization(String schemaVersion, ConclusionCandidate candidate, ConclusionDecision decision)` as `analysis_finalize.data`.
- Consumes: the existing Conclusion Gate without changing its policy.

- [x] Add immutable contract validation for conclusion ID, identity and revision equality.
- [x] Wrap the existing Gate decision with its submitted Candidate in the Core handler.
- [x] Compose a finalize-specific MCP output schema while leaving all other outputs generic.
- [x] Run contract/core/MCP tests GREEN.

### Task 5: Synchronize prompt, docs, packaging and audit

**Files:**
- Modify: `agent-definition/system-prompt-v1.md`
- Modify: `agent-definition/README.md`
- Modify: `integrations/host-adapter-kit/README.md`
- Modify: `integrations/qwen-cli/README.md`
- Modify: `config/README.md`
- Modify: `docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md`
- Modify: `docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/architecture/tool-validation-baseline.md`
- Create: `docs/audits/2026-09-29-portable-agent-boundary-simplification-audit.md`

**Interfaces:**
- Produces: documentation that distinguishes hard Java gates, optional host features, knowledge hints, and model narration.
- Consumes: Tasks 2-4 final behavior and verification evidence.

- [x] Remove claims that final model JSON is host-enforced; document Finalization as the canonical close.
- [x] Update prompt hash after all prompt changes and re-render compatibility copies if required.
- [x] Run affected Maven/Node tests, root Reactor, build packaging, MCP wire and Qwen TestProfile lifecycle.
- [x] Audit requirements, duplicate constants/contracts, stale references, TODOs, secrets, absolute paths and `git diff --check`.
- [x] Record exact commands/results, unresolved external-host/Eval limits and rollback in the audit.

## Definition of Done

- No runtime/tool/policy behavior is weakened and the MCP catalog remains exactly 17 tools.
- Tool names are not duplicated in Agent Definition; Catalog-to-Manifest parity is build-gated.
- Knowledge is genuinely delivered when configured and never required, unbounded, silently truncated or registered as Evidence.
- `analysis_finalize.data` is a strict Finalization containing the exact submitted Candidate and Gate Decision.
- Completion Contract/default model hint/mandatory resource-prompt claims are absent from code, schemas, packaging and active docs.
- All affected and full verification commands are green, or the final audit names the exact blocker and residual risk.
- `docs/sharing/` remains untouched and untracked.
