# English Runtime Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep human-facing guidance Chinese while making machine-generated runtime output English.

**Architecture:** Translate messages at the externally observable Demo, CLI, contract, and OpenCode boundaries. Validate analysis conclusions before creating files or starting the Java CLI.

**Tech Stack:** Java 21, Maven, JUnit 5, Node test runner, OpenCode TypeScript tools.

**Spec:** `docs/designs/2026-08-20-english-runtime-output-design.md`

## Global Constraints

- Preserve error codes, schema fields, IDs, behavior, Chinese Skill text, comments, and user/model content.
- Do not add encoding detection or translation infrastructure.
- Do not translate internal strings that cannot reach an external output boundary.

---

### Task 1: OpenCode conclusion prevalidation

**Files:**
- Create: `integrations/opencode/test/analysis-complete-validation.test.mjs`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `integrations/opencode/tools/algorithm-debug.ts`

- [ ] Add a failing test for an empty evidence list on `CONFIRMED_FACT`.
- [ ] Reject it before invoking the CLI with a field-level English message.
- [ ] Run the Node test suite.

### Task 2: English Demo and CLI output

**Files:**
- Modify the two Demo failure-message sources.
- Modify CLI entry, parser, executor, writer, and AnalysisResult contract messages.

- [ ] Translate only externally observable machine messages.
- [ ] Keep error codes and user/model content unchanged.
- [ ] Run Demo and root Maven tests.

### Task 3: Skill guidance and end-to-end verification

**Files:**
- Modify: `skills/algorithm-debug/SKILL.md`
- Modify: `integrations/opencode/agents/algorithm-debug.md`

- [ ] State the non-empty evidence rule in Chinese guidance.
- [ ] Install the OpenCode assets.
- [ ] Re-run the assertion-failure OpenCode workflow.
