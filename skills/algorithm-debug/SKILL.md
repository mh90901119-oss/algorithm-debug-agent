---
name: algorithm-debug
description: Use when a user asks about a specified Java/Maven algorithm UT, its exception, Gantt result, runtime path, internal state, or changes across analysis rounds.
compatibility: opencode
metadata:
  owner: algorithm-debug-agent
  version: "1.2"
---

# Algorithm Debug workflow

Use this Skill when the user names a target Java/Maven UT and asks about its exception, assertion,
Gantt output, scheduling behavior, or a change across analysis rounds.

## Core interpretation rules

1. Treat one user problem about one target UT as a Case. A different target UT starts a different Case unless the user explicitly asks for a cross-UT comparison.
2. Each follow-up within a Case creates a new `analysisId`. Preserve prior facts and artifact references; never overwrite history.
3. Context 由显式决策管理：新 Case 自动创建首个 Context；已有 Case 默认复用最近 Context。只有已知目标算法源码、UT 或输入被有意修改时，才使用 `--context-mode new`。不得扫描工作区或依据 Gantt 变化自动新建 Context。
4. When a tool returns `eventType=TARGET_TEST_RUN_COMPLETED`, read the structured summary before raw logs.
5. `latestRunForAnalysis=true` identifies the newest run for that analysis, not the only valid historical evidence.
6. Keep `processOutcome`, `testOutcome`, `ganttOutcome`, `targetFailure`, and `agentFailure` independent. A failed UT can still contain a valid Gantt.
7. Treat exception class, normalized message, cause, and stable business frame as facts. Do not assume they already express the algorithm root cause.
8. Use `artifacts` to request only the bounded excerpt needed for the current evidence gap. Never load every historical log or unbounded object graph.

## Decision loop

After every user message:

OpenCode tools prepare the external Agent Workspace and register the current Maven module automatically.
Do not ask the user to provide a `projectId` or registry path. Use `analysis_begin` to create or continue
the Case, and use `case_inspect` when historical evidence may already answer the new question.

1. Determine whether the target UT belongs to an existing Case. Unless the user or model已明确知道目标源码、UT 或输入发生了有意修改，复用最近 Context；不要自行扫描工作区判断。
2. Reuse prior immutable evidence when it already answers the question. A new chat turn does not require a new UT run.
3. If execution facts are missing or stale for the question, call the test-run tool. Inspect the returned summary even when the command reports target failure.
4. When `comparisonOutcome=MATCHED`, treat the current target observation as reproducible for the
   reported scope. When it is `CHANGED`, state that the Agent detected a Gantt-content and/or target-
   failure fingerprint change, then read the referenced current and reference artifacts only if the
   user's question requires the change location. Do not claim that the Agent produced a field-level
   Gantt diff.
5. Read stdout, stderr, Surefire XML, or Gantt artifacts only by reference and only within the requested byte/line budget.
6. Request CodePathTracer when a bounded runtime call path would close a stated gap. First read the current Method Catalog, choose 1–50 exact `class + method + descriptor` selectors, archive a Plan, then collect. A Case may contain multiple Plans and Collections. Request JDWP only for named methods/variables and bounded hits/depth/bytes.
7. Before a confirmed root-cause claim, verify evidence coverage, contradictions, truncation, and semantic-baseline consistency.
8. Before answering the user, call `analysis_complete` with a strict `AnalysisResult` containing the final
   answer, graded conclusions, and explicit evidence references. Do not put hidden reasoning in that record.

## JDWP refinement loop

Use JDWP after static analysis or CodePath evidence has identified one or a few named methods and the
remaining question concerns their stack or internal runtime state. A Case may contain multiple JDWP
plans and collections; one collection is not a Case limit.

1. Create the smallest plan from a current Method Catalog entry and its Source Anchor. State the
   evidence gap and keep tracepoints, hits, frames, object depth, bytes and timeout bounded.
2. Plan creation and collection execution are separate actions. Do not execute merely because a plan
   exists; execute when the current user question still needs that runtime evidence.
3. Read the collection summary first. `TARGET_FAILED` remains analyzable; `TOOL_FAILED`, `TIMED_OUT`,
   `TRUNCATED`, exact tracepoint SourceAnchor mismatch, or `baselineOutcome!=MATCHED` cannot support a confirmed root cause.
4. Treat JDWP evidence as confirmation-capable only when `completion=SUCCESS`,
   `baselineOutcome=MATCHED`, and `evidenceUsable=true`. Then read only the referenced Raw excerpt or
   derived artifact needed for the question; do not inline the full JSONL.
5. If a later collection differs, cite its new `runId/collectionId` and explain the observed change.
   Preserve and distinguish earlier evidence rather than silently replacing it.

## Answer contract

Label material statements as `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION`, `SOURCE_INFERENCE`,
`LLM_HYPOTHESIS`, or `MISSING_EVIDENCE`. Cite `caseId/contextId/analysisId/runId` and artifact IDs
near the claims they support. Explain target UT failures normally; an exception or nonzero Maven exit
is analysis input, not an Agent crash. If Agent collection/persistence failed, state that separately.
