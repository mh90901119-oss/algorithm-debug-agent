---
name: algorithm-debug
description: Use when a user asks about a specified Java/Maven algorithm UT, including its exception, assertion failure, Gantt result, runtime call path, internal state, or changes across analysis rounds.
metadata:
  owner: algorithm-debug-agent
  version: "2.0"
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
- For a new analysis that depends on the current execution, run the target UT once before choosing
  Static, CodePath, or JDWP evidence. A follow-up that is already answered by immutable Case evidence
  does not require another run.
- If the user's question is whether a named target UT exists and current source discovery confirms it
  does not, report that fact and stop. Do not manufacture a Run, Gantt expectation, CodePath
  collection, or JDWP collection for a non-existent target.
- Do not ask the user for a Workspace, registry path, or `projectId`; tools prepare them automatically.
- Do not ask the user for an algorithm result directory and do not infer one from the question.
  `analysis_begin.resultJsonDirectory` reports the installed Agent setting. When present,
  continue normally and never pass the path back to `run_test`.

## Evidence loop

| Evidence gap | Tool action |
|---|---|
| Current execution facts are missing | Run `algorithm-debug_run_test` once |
| Prior facts may already answer | Use `algorithm-debug_case_inspect` |
| Relevant methods are unknown | Use `algorithm-debug_static_analyze` |
| Runtime call path is needed | Create a CodePath plan, then collect it |
| Named method state is needed | Create a JDWP plan, then collect it |
| Raw detail is needed | Read a bounded Artifact excerpt by ID |

First decide only whether the Agent/tool chain executed correctly. If it did, every target UT result
is evidence, regardless of process exit code or test success. Do not force target failures into a
closed classifier. Instead, read the facts actually present: executed-test identity/count, exit code,
timeout/termination, exception type/message/cause chain/stack, assertion expected/actual values,
stdout/stderr excerpts, and captured JSON artifacts. Missing input, algorithm exceptions, assertion
failures, setup errors, process termination, and previously unseen failure forms are examples, not an
exhaustive enum.

If the Agent/tool chain itself failed, report that boundary and do not invent a target diagnosis. If
the target UT failed, explain the earliest causally sufficient target fact and stop analyzing later
algorithm stages that could not have executed. A failed UT may still contain a valid JSON result; a
successful UT may produce no JSON when no result directory is configured or no JSON file changed.
When a result directory is configured but no JSON is captured, state only that no JSON was captured
from the configured directory. Do not declare the path incorrect and do not scan or guess another path.
When `analysis_begin.resultJsonDirectory` is absent and the user's question requires an algorithm JSON
result, record that configuration as `MISSING_EVIDENCE`; do not repeatedly ask for a path during the
analysis. UT logs, Surefire facts, Static, CodePath and JDWP remain available when applicable.

After each evidence step, ask whether the user's concrete question is already answerable. If yes,
complete the analysis. If not, choose only the single most valuable next action: a bounded artifact
excerpt, Static analysis, CodePath collection, or JDWP collection. Unknown failure shapes remain raw
evidence for model analysis; never convert them into `OTHER` or reject them because they were not
predefined.

Do not repeat a failed collection with the same effective plan. Retry only when the Tool error names
a correctable Plan input and the next Plan materially changes that input; otherwise preserve the
failure diagnostics, report the tool boundary, and stop collecting.

For CodePath, select only the exact `class + method + descriptor` entries needed from the Method
Catalog. Archive the plan before collecting. For JDWP, first identify one or a few named methods and
variables, then archive a bounded plan before collecting. A Case may contain multiple plans and
collections of either type.

Plan Tool `requestJson` is strict. Use these shapes and do not invent `methods`, `lineRange`,
`projection`, or other fields:

```json
{"planId":"plan-id","selectedMethodKeys":["class#method(descriptor)"],"rationale":"why this path is needed","budget":{"maxEvents":100000,"maxBytes":16777216,"timeoutMillis":300000},"requestedAt":"ISO-8601 timestamp"}
```

```json
{"planId":"plan-id","tracepoints":[{"tracepointId":"point-id","methodKey":"class#method(descriptor)","line":1,"maxHits":3,"capture":{"locals":true,"stack":true,"maxFrames":6,"maxDepth":1,"maxItems":20,"maxStringLength":256}}],"budget":{"maxEvents":100,"maxBytes":16777216,"timeoutMillis":300000,"idleTimeoutMillis":120000},"rationale":"why this state is needed","requestedAt":"ISO-8601 timestamp"}
```

If plan creation reports a schema or compilation error, correct the supplied JSON from these shapes.
Do not search the target repository or Agent workspace for request examples.

For a passing target UT, use dynamic evidence only when collection and validation completed without
an unusable truncation; Gantt content is archived independently and is not a baseline gate. For a
failing target UT, `MATCHED` means the collection rerun reproduced the same structured target
failure. `CHANGED` means it reproduced a different failure, not that the collector caused the change.
Do not use that runtime state to confirm the original failure.

Read summaries before raw artifacts. For `artifact_read`, pass an `artifactIds` value returned by a
Run or Collection summary; a Case-relative path is provenance, not an Artifact ID. Request only the
excerpt needed for the current evidence gap; never load all logs, traces, or historical artifacts.
For a large registered Gantt, call `gantt_inspect` with `summary` first and then bounded `slice`
requests. It returns JSON structure and raw fields only; the LLM remains responsible for semantics.

## Optional planning knowledge

Domain reference files are planning hints, not evidence. When the target is the Wafer Demo, use
`references/wafer-demo-v1.md` when it is available or explicitly attached. Use it only to narrow the
first candidate methods and variables. Always run `static_analyze` to resolve current Method Catalog
keys, descriptors and current source lines before creating CodePath or JDWP plans. Confirm every
runtime statement through validated Collection/Evidence; never promote reference content directly to
`CONFIRMED_FACT`.

## DFX interaction log boundary

Each successfully created Case may contain `interaction.jsonl` in its Case root. This diagnostic file
shows the actual Custom Tool and internal Java CLI order for manual troubleshooting. It may contain
safe Run, Plan, Collection, Evidence, and Artifact IDs that help locate the corresponding immutable
Case artifacts.

The DFX file must not be used as Evidence, must not be cited by `analysis_complete`, and must not be
promoted to any confirmed conclusion. It does not contain hidden reasoning, full questions, answers,
Tool payloads, stdout/stderr, algorithm JSON, or JDWP values. If Case creation fails before a Case ID
exists, the installation's configured DFX directory may contain an `unassigned` fallback log.

## JDWP evidence rules

- JDWP and CodePath are independent evidence tools. Do not run CodePath first unless the question
  actually needs a runtime call path before selecting JDWP locations.
- Build JDWP plans only from current Method Catalog anchors. Preserve exact class, method,
  descriptor and current source line; never guess overloaded methods by name alone.
- Prefer `localNames` and `fieldPaths` for focused collection. Empty projections mean bounded default
  capture, not unlimited object expansion.
- Treat missing descriptors, code indexes, Collector capability mismatch, truncation, absent local
  variable tables, or a changed target-failure fingerprint as `MISSING_EVIDENCE`; do not promote such data to a
  confirmed root cause.
- Use normalized Summary/Evidence for reasoning. Read bounded Raw Trace excerpts only when the
  normalized evidence cannot answer the concrete question.

## Answer contract

Label material claims as `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION`, `SOURCE_INFERENCE`,
`LLM_HYPOTHESIS`, or `MISSING_EVIDENCE`. Cite relevant Case, Context, Analysis, Run, Collection,
Evidence, and Artifact IDs near each claim.

Before replying, call `algorithm-debug_analysis_complete` with the current Case, Context and Analysis
IDs plus the final answer, graded conclusions, explicit evidence references, and remaining evidence
gaps. The Tool adds the strict `AnalysisResult` schema version and completion time. Never store hidden
reasoning in the result. If completion rejects the payload, use its concrete message and the current
Case digest to correct the same payload once. Never open another Analysis or submit a dummy result to
work around a completion error; after one failed correction, report the completion failure honestly.
Before `analysis_complete`, call `case_audit`. Do not silently ignore missing controls, invalid
Artifact registrations, integrity mismatches, invalid interaction JSONL, or empty Case directories.

For completion fields, use only target Run IDs from `recentRuns`, Collection IDs from
`recentCollections`, Evidence IDs from `recentEvidence`, and registered Artifact IDs from
`artifactIds`. Collection responses label their internal execution provenance as
`collectorExecutionRunId`; never put that value in `referencedRunIds`.

## analysis_complete 证据引用契约

- CONFIRMED_FACT、VALIDATOR_CONCLUSION 和 SOURCE_INFERENCE 的 evidenceReferenceIds 必须至少包含一个已注册 Evidence、Artifact 或事实 ID。
- LLM_HYPOTHESIS 和 MISSING_EVIDENCE 可以使用空引用，但必须在 statement 中明确不确定性或缺失内容。
- 提交前逐条检查 conclusions；不得依赖 CLI 拒绝后再读取 Agent 源码修正。
