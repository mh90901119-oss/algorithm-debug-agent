---
name: algorithm-debug
description: Analyze a specified Java/Maven algorithm UT using structured run summaries, immutable artifacts, CodePathTracer and bounded JDWP evidence.
compatibility: opencode
metadata:
  owner: algorithm-debug-agent
  version: "1.0"
---

# Algorithm Debug workflow

Use this Skill when the user names a target Java/Maven UT and asks about its exception, assertion,
Gantt output, scheduling behavior, or a change across analysis rounds.

## Core interpretation rules

1. Treat one user problem about one target UT as a Case. A different target UT starts a different Case unless the user explicitly asks for a cross-UT comparison.
2. Each follow-up within a Case creates a new `analysisId`. Preserve prior facts and artifact references; never overwrite history.
3. A workspace change creates a new `contextId`, not automatically a new Case. Do not rerun merely because code changed.
4. When a tool returns `eventType=TARGET_TEST_RUN_COMPLETED`, read the structured summary before raw logs.
5. `latestRunForAnalysis=true` identifies the newest run for that analysis, not the only valid historical evidence.
6. Keep `processOutcome`, `testOutcome`, `ganttOutcome`, `targetFailure`, and `agentFailure` independent. A failed UT can still contain a valid Gantt.
7. Treat exception class, normalized message, cause, and stable business frame as facts. Do not assume they already express the algorithm root cause.
8. Use `artifacts` to request only the bounded excerpt needed for the current evidence gap. Never load every historical log or unbounded object graph.

## Decision loop

After every user message:

1. Determine whether the target UT belongs to an existing Case and whether the current workspace matches an existing Context.
2. Reuse prior immutable evidence when it already answers the question. A new chat turn does not require a new UT run.
3. If execution facts are missing or stale for the question, call the test-run tool. Inspect the returned summary even when the command reports target failure.
4. When `comparisonOutcome=MATCHED`, treat the current target observation as reproducible for the
   reported scope. When it is `CHANGED`, state that the Agent detected a Gantt-content and/or target-
   failure fingerprint change, then read the referenced current and reference artifacts only if the
   user's question requires the change location. Do not claim that the Agent produced a field-level
   Gantt diff.
5. Read stdout, stderr, Surefire XML, or Gantt artifacts only by reference and only within the requested byte/line budget.
6. Request CodePathTracer when a bounded runtime call path would close a stated gap. Request JDWP only for named methods/variables and bounded hits/depth/bytes.
7. Before a confirmed root-cause claim, verify evidence coverage, contradictions, truncation, and semantic-baseline consistency.

## Answer contract

Label material statements as `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION`, `SOURCE_INFERENCE`,
`LLM_HYPOTHESIS`, or `MISSING_EVIDENCE`. Cite `caseId/contextId/analysisId/runId` and artifact IDs
near the claims they support. Explain target UT failures normally; an exception or nonzero Maven exit
is analysis input, not an Agent crash. If Agent collection/persistence failed, state that separately.
