---
name: algorithm-debug
description: Use when a user asks about a specified Java/Maven algorithm UT, including its exception, assertion failure, Gantt result, runtime call path, internal state, or changes across analysis rounds.
metadata:
  owner: algorithm-debug-agent
  version: "1.3"
---

# Algorithm Debug Workflow

Debug one specified Java/Maven UT through immutable, bounded evidence. Let the model decide which
evidence is needed; let the Agent execute, validate, archive, and reference deterministic facts.

## Case and analysis rules

- Treat one user problem about one target UT as one Case. Start a different Case for a different UT
  unless the user explicitly requests a cross-UT comparison.
- Call `algorithm-debug_analysis_begin` for every new question. Pass the prior `caseId` for a
  follow-up; omit it for a new Case.
- Reuse the current Context by default. Use `contextMode=new` only when the user or model already
  knows that the target source, UT, or input was deliberately changed. Never scan the repository or
  infer a new Context only because Gantt output changed.
- Inspect existing Case evidence before running the UT. A new conversation turn does not require a
  new run.
- Do not ask the user for a Workspace, registry path, or `projectId`; tools prepare them automatically.

## Evidence loop

| Evidence gap | Tool action |
|---|---|
| Current execution facts are missing | Run `algorithm-debug_run_test` once |
| Prior facts may already answer | Use `algorithm-debug_case_inspect` |
| Relevant methods are unknown | Use `algorithm-debug_static_analyze` |
| Runtime call path is needed | Create a CodePath plan, then collect it |
| Named method state is needed | Create a JDWP plan, then collect it |
| Raw detail is needed | Read a bounded Artifact excerpt by ID |

Keep process, test, Gantt, target-failure, and Agent-failure outcomes independent. Treat a missing
input, assertion failure, algorithm exception, or nonzero Maven result as analysis input, not as an
Agent crash. A failed UT may still contain a valid Gantt artifact.

For CodePath, select only the exact `class + method + descriptor` entries needed from the Method
Catalog. Archive the plan before collecting. For JDWP, first identify one or a few named methods and
variables, then archive a bounded plan before collecting. A Case may contain multiple plans and
collections of either type.

Use dynamic evidence for a confirmed root cause only when the collection completed successfully,
was not unusably truncated, and its baseline outcome is `MATCHED`. When the outcome is `CHANGED`,
state that the Gantt content or target-failure fingerprint changed and inspect referenced artifacts
only when the question needs the change location. Do not claim a field-level Gantt diff.

Read summaries before raw artifacts. For `artifact_read`, pass an `artifactIds` value returned by a
Run or Collection summary; a Case-relative path is provenance, not an Artifact ID. Request only the
excerpt needed for the current evidence gap; never load all logs, traces, or historical artifacts.

## Answer contract

Label material claims as `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION`, `SOURCE_INFERENCE`,
`LLM_HYPOTHESIS`, or `MISSING_EVIDENCE`. Cite relevant Case, Context, Analysis, Run, Collection,
Evidence, and Artifact IDs near each claim.

Before replying, call `algorithm-debug_analysis_complete` with the current Case, Context and Analysis
IDs plus the final answer, graded conclusions, explicit evidence references, and remaining evidence
gaps. The Tool adds the strict `AnalysisResult` schema version and completion time. Never store hidden
reasoning in the result.

For completion fields, use only target Run IDs from `recentRuns`, Collection IDs from
`recentCollections`, Evidence IDs from `recentEvidence`, and registered Artifact IDs from
`artifactIds`. Collector execution `runId` values are provenance, not archived target Run IDs.
