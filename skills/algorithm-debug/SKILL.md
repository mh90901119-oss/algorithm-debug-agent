---
name: algorithm-debug
description: Use when a user asks about one specified Java/Maven algorithm UT, its exception or assertion failure, its Gantt result, runtime call path, internal state, or causal behavior across analysis rounds.
metadata:
  owner: algorithm-debug-agent
  version: "2.4"
---

# Algorithm Debug Workflow

Use immutable, bounded evidence to debug one specified Java/Maven algorithm UT. The LLM chooses the
next evidence gap and explains causality. The Agent executes, validates, archives, and references
deterministic facts. Do not encode target-algorithm business semantics in tools.

## Case identity

- One problem about one target UT is one Case. Pass the prior `caseId` for a follow-up; omit it for a
  new Case. Use a new Context only after a deliberate source, UT, or input change.
- Call `algorithm-debug_analysis_begin` for every user question. If the named UT does not exist,
  report that fact and stop.
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

Use CodePath when the unresolved question is which implementation or path executed. Select exact
`class#method(descriptor)` keys from the current Method Catalog. Use `scopeMethodKey` for a repeated
operation whose invocation groups/path variants matter. Expand only across a specific unresolved
boundary exposed by the previous validated summary.

Use JDWP when the unresolved question is a named runtime value at a current executable source line.
CodePath and JDWP are independent; do not require one before the other. Prefer focused `localNames`
and `fieldPaths` projections.

For a repeated method, use a generic `condition` when an entity or state can identify relevant
invocations:

- `localName`: top-frame local/parameter name or `this`.
- `fieldPath`: at most eight instance fields; no getter, method, array index, or collection scan.
- `operator`: `EQUALS`.
- `expectedType`: `STRING`, `LONG`, `DOUBLE`, `BOOLEAN`, `CHAR`, `ENUM`, or `NULL`.
- `expectedValue`: typed scalar text, omitted only for `NULL`.

Set `maxObservedHits` high enough to encounter the relevant invocation but no higher than needed.
Set `maxCapturedHits` to the small number of full snapshots required. Use
`captureOnMatchedHits` only when specific matched ordinals matter. Non-matching observations briefly
suspend only the event thread and skip full stack/local/object expansion.

Read the Collector/Agent Manifest counters as separate facts: `observed` is breakpoint encounters,
`matched` passed the condition, `captured` produced full snapshots, and `unavailable` means the
condition could not be evaluated. Read bounded unavailable-reason details before changing a Plan.
Never treat zero captured snapshots as proof that the expected state never existed when the Plan was
truncated or condition evaluation was unavailable.

After a Collection, read normalized Summary/Evidence first. Read Raw Trace only for a specific detail
missing from normalized evidence. Do not repeat an effective Plan unchanged. A later Plan must answer
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

Then call `algorithm-debug_analysis_complete` once with the current Case/Context/Analysis IDs, final
answer, graded conclusions, referenced target Run IDs, Collection IDs, Evidence IDs, Artifact IDs,
and remaining evidence gaps. `collectorExecutionRunId` is collector provenance and must not be placed
in `referencedRunIds`. If completion rejects the payload, correct the same Analysis payload once;
never open a replacement Analysis or submit a dummy result.
