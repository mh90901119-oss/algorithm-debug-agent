---
name: algorithm-debug
description: Use when a user asks about one specified Java/Maven algorithm UT, its exception or assertion failure, its Gantt result, runtime call path, internal state, or causal behavior across analysis rounds.
metadata:
  owner: algorithm-debug-agent
  version: "3.1"
---

# Algorithm Debug Workflow

Use immutable, bounded evidence to debug one specified Java/Maven algorithm UT. The LLM chooses the
next evidence gap and explains causality. The Agent executes, validates, archives, and references
deterministic facts. Do not encode target-algorithm business semantics in tools.

## Case identity

- One problem about one target UT is one Case. Omit `caseId` for a new problem. For a follow-up that
  needs fresh deterministic work, pass the prior `caseId`; `analysis_begin` then appends a new
  `analysisId` without overwriting earlier evidence.
- Do not call `algorithm-debug_analysis_begin` for a clarification or follow-up already answerable
  from the current conversation and immutable Case evidence. Answer it directly without creating an
  empty Analysis.
- When a follow-up needs a new input verification, current-source analysis, UT Run, CodePath, JDWP,
  Case audit, or final archived conclusion, call `algorithm-debug_analysis_begin` exactly once first.
  A reported source/UT/input change uses the same Case and a new Analysis. If the named UT does not
  exist, report that fact and stop.
- Do not ask for Workspace, `projectId`, result directory, or tool JAR paths. Installed settings and
  project registration resolve them.
- Reuse immutable Runs, Collections, Evidence, and Artifacts. A new analysis adds an `analysisId`; it
  never overwrites prior evidence.

## 1. Capture and read the algorithm input

Immediately call `algorithm-debug_algorithm_input_capture`. It accepts exactly one first-level
`String` literal in the target test method whose value ends case-insensitively with `input.json` or
`input_.json`. Zero, multiple, computed, missing, invalid, or changed inputs are hard boundaries:
report the concrete Tool result and stop before running or collecting.

The Agent archives the input once per Case at `input/<original-file-name>`. Later analyses verify and
reuse that Artifact; they do not create renamed copies. Read the registered `ALGORITHM_INPUT`
Artifact through bounded `artifact_read` calls before choosing runtime evidence. Its SHA verifies
exact bytes and reuse only; it is not a business conclusion.

Extract only question-relevant planning facts from the JSON input, such as involved entities,
remaining steps, candidate resources, flags, limits, and relationships. Treat these as input facts,
not proof that a runtime branch executed.

## 2. Execute the target UT once

Call `algorithm-debug_run_test` when the current analysis needs fresh execution. First distinguish an
Agent/tool failure from a target UT result. A target exception, assertion failure, timeout, or nonzero
exit is still valid evidence; an Agent/tool failure is not a target diagnosis.

Read the actual Run facts: executed test identity/count, exit code, termination, exception chain,
first relevant target stack frame, assertion expected/actual values, stdout/stderr excerpts, and
registered JSON Artifacts. Do not force failures into a closed enum. Explain the earliest causally
sufficient failure and do not analyze algorithm stages that could not have executed.

One uninstrumented Run captures at most the newly produced Gantt JSON using its original file name.
CodePath and JDWP reruns never copy Gantt output and never use Gantt SHA as a gate. For a large Gantt,
use `gantt_inspect` summary and bounded slices; the Tool exposes structure and values, while the LLM
owns semantic interpretation.

## 3. Build causal hypotheses from input and source

Call `algorithm-debug_static_analyze` when relevant current methods and dispatch boundaries are not
already known. The Method Catalog is a bounded planning index, not runtime proof:

- `DIRECT` is a compiler-resolved source relationship.
- `POLYMORPHIC_CANDIDATE` is a possible implementation, not proof that it ran.
- An incomplete catalog narrows planning but cannot prove route absence.

Combine the user-observed Gantt symptom, algorithm-input facts, current source conditions, and prior
validated evidence. Write one or more explicit causal hypotheses from upstream causes to the visible
result. Do not assume the entity named by the user caused its own symptom; inspect competing entities,
earlier steps, shared resources, configuration flags, and policy dispatch when the input/source make
them plausible.

For every dynamic Plan supply:

- `questionToAnswer`: one concrete unresolved question.
- `hypothesis`: the explanation to verify or reject.
- `basedOnEvidenceIds`: only prior Evidence IDs from the same Case.
- `expectedObservations`: observations that distinguish the hypothesis.

The Agent rejects missing or cross-Case Evidence lineage. Do not create a Plan merely to increase
confidence; create it only when its expected observation can change the conclusion.

## 4. Collect only discriminating runtime evidence

Choose one smallest next action after each evidence step. There is no fixed number of rounds,
methods, tracepoints, or collections.

Only one target-executing Tool may be active at a time. Never issue `algorithm-debug_run_test`,
`algorithm-debug_codepath_collect`, or `algorithm-debug_jdwp_collect` in parallel, in one Tool batch,
or before the previous target-executing Tool has returned. Wait for the complete ToolResponse, inspect
its Summary, Validation, Evidence, and any required bounded query result, then decide whether another
target execution is still necessary.

Do not pre-create CodePath and JDWP Plans for speculative paired execution. Create the smallest Plan
for the current evidence gap, collect it, evaluate it, and only then create a different Plan when a
new concrete gap remains. `ADA_TARGET_EXECUTION_SEQUENCE_VIOLATION` is a workflow rejection, not a
target failure; do not retry it automatically.

For every ToolResponse with `success=false`, treat the named error code and message as the
authoritative Agent boundary. Never use that failed call as target-test evidence. When the response
contains a failure Manifest Artifact, read that bounded Manifest to distinguish process start,
exit, attach, and archive facts; do not read Collector logs as algorithm evidence. Follow only the
specific recovery action in the message, and never modify the target project POM to repair an Agent
installation or Collector failure.

Use CodePath when the unresolved question is which implementation or path executed. Select exact
`class#method(descriptor)` keys from the current Method Catalog. Use `scopeMethodKey` for a repeated
operation whose invocation groups/path variants matter. Expand only across a specific unresolved
boundary exposed by the previous validated summary.

For each selected method, request only scalar values that can distinguish the current hypothesis.
Use literal brackets in `arg[0]`, `arg[1].field.subfield`, `return`, or `return.field.subfield`;
`arg0` is invalid. The zero-based argument index comes from the exact method descriptor and source
declaration. Projection names must describe
the source value (`waferId`, `candidateChamber`, `strategyName`) without claiming business meaning
that the source does not establish. Do not request getters, collection indexes, Map keys, arbitrary
expressions, or complete objects. An unavailable optional projection is a recorded fact; an
unavailable required projection is an evidence gap, not permission to discard the method event.

After collection, use Method Path Summary for execution counts and path variants. When individual
argument/return values are needed, call `evidence_query` on the registered `CODEPATH_INVOCATIONS`
Artifact with an exact `methodRef` and, when useful, one projection `valueName`/`scalarValue` pair.
Do not page through the complete JSONL with `artifact_read`. For an
incremental CodePath Plan, cite the prior Evidence ID in `basedOnEvidenceIds`; the rationale must name
the concrete observation that was insufficient, and expected observations must state what result
would change the next decision. This is the complete multi-collection linkage; do not invent a
separate conversation state or invocation ID.

When CodePath leaves a concrete value gap that requires JDWP, finish the CodePath Collection first,
read its Evidence ID from the successful response, and include that exact ID in the JDWP Plan
`basedOnEvidenceIds`. Never create the JDWP Plan before the CodePath Evidence exists.

If the user explicitly requests a runtime method path, use CodePath and collect Method Path Evidence.
JDWP state or line-hit evidence does not replace method-path evidence. Conversely, CodePath does not
replace JDWP when the unresolved question requires a named runtime value.

Use JDWP when the unresolved question is a named runtime value at a current executable source line.
CodePath and JDWP are independent; do not require one before the other. Build each tracepoint from the
current Method Catalog and source: select an executable line where the named state is in scope, then
request only the exact scalar or enum `valuePaths` needed to distinguish the current hypothesis.
Algorithm-input identifiers may be used as condition values, but the Collector never assigns business
meaning to a field.

For a repeated method, use up to four `conditions` when entity and state values identify relevant
invocations. All conditions are combined with AND:

- `valuePath`: top-frame local/parameter name or `this`, followed by at most seven instance fields,
  for example `candidate.wafer.id`.
- `operator`: `EQUALS`.
- `expectedType`: `STRING`, `LONG`, `DOUBLE`, `BOOLEAN`, `CHAR`, `ENUM`, or `NULL`.
- `expectedValue`: typed scalar text, omitted only for `NULL`.

Set `maxObservedHits` high enough to encounter the relevant invocation but no higher than needed.
Set `maxCapturedHits` to the bounded number of full snapshots required. Use
`captureFirstMatchedHits` to retain the first matched state transitions and
`captureEveryMatchedHits` to sample later matched transitions without guessing fixed hit ordinals.
Non-matching observations briefly suspend only the event thread and skip snapshot creation. A capture
`valuePath` reads exactly that path and never expands an object, collection, Map, or array. Select a
deeper field path in a later Plan when the result is `REFERENCE_ONLY`.

Read the Collector/Agent Manifest counters as separate facts: `observed` is breakpoint encounters,
`matched` passed the condition, `captured` produced full snapshots, and `unavailable` means the
condition could not be evaluated. Read bounded unavailable-reason details before changing a Plan.
Never treat zero captured snapshots as proof that the expected state never existed when the Plan was
truncated or condition evaluation was unavailable.

After a Collection, read normalized Summary/Evidence first. Each requested path has one projection
status: `CAPTURED` contains a scalar value, `TRUNCATED` contains a bounded prefix,
`REFERENCE_ONLY` identifies a complex runtime object that needs a deeper path, and `UNAVAILABLE`
contains the deterministic read-failure reason. Use `evidence_query` on the registered
`JDWP_SNAPSHOT_SUMMARY` Artifact to select an exact `tracepointId`, `valueName`, `scalarValue`,
`valueStatus`, or sequence window. Interpret the value with the Method Catalog declaration, current
source, Algorithm Input, stack location, runtime type, and Plan intent; do not infer meaning from a
field name alone. Read Raw Trace only for a specific detail missing from normalized evidence.
Do not repeat an effective Plan unchanged. A later Plan must answer
a materially different question or change a field named by deterministic validation.

## Evidence sufficiency and iteration

After every step ask whether the concrete user question is answerable. If yes, stop collecting. If
not, state the missing causal link and choose one next action: bounded Artifact read, Static analysis,
CodePath, or JDWP. New evidence may reject the current hypothesis or reveal another entity; create a
new incremental Plan that cites the prior Evidence instead of restarting the Case.

For a failing target UT, dynamic evidence confirms the same failure only when its structured failure
fingerprint is `MATCHED`. `CHANGED` or `INCOMPARABLE` is a clue or missing evidence, not confirmation.
For a passing UT, Gantt remains an independently archived result and is not compared as a collection
baseline.

Classify claims as `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION`, `SOURCE_INFERENCE`,
`LLM_HYPOTHESIS`, or `MISSING_EVIDENCE`. Confirmed, validator, and source-inference claims require
explicit Evidence references. Input facts and source control-flow implications must not be promoted
to observed runtime facts.

## DFX boundary

`interaction.jsonl` and the Case execution log show Tool/CLI/stage order and safe identifiers for
manual troubleshooting. They must not be used as Evidence or cited as a root-cause fact. They contain
no hidden reasoning. If Case creation fails, the configured DFX directory may contain an `unassigned`
fallback log.

## Completion

Call `algorithm-debug_case_audit`. Do not ignore missing controls, invalid Artifact registrations,
integrity mismatches, malformed interaction JSONL, or empty Case directories.

Return the conclusion directly to the user; do not persist the model-authored answer in Workspace.
Start the answer by copying the two lines in `analysis_begin.data.answerContext` verbatim. Never
abbreviate either path with `...`, an ellipsis, or a suffix-only path. Then state which major
capabilities were used (`algorithm input`, `run_test`, `static analysis`, `CodePath`, `JDWP`, and
bounded evidence query). Keep claim classifications explicit in the answer and cite registered
Artifact/Evidence IDs when they support a fact. `collectorExecutionRunId` is collector provenance,
not a target Run ID.
Use the full exact Run, Collection, Evidence, and Artifact IDs; never abbreviate an identifier to a
prefix, suffix, or ellipsis.
