# Runtime Evidence Quality Long Task

Updated: 2026-09-03
Status: implemented and verified

Specification:
`docs/designs/2026-09-03-runtime-evidence-usability-and-single-session-sequencing-design.md`

## Completed implementation

- [x] Guard `run_test`, `codepath_collect`, and `jdwp_collect` against overlap in one OpenCode Runtime.
- [x] Keep the guard non-blocking and lock-free; release it in `finally`.
- [x] Replace legacy CodePath selection with Plan v4 exact methods and bounded scalar projections.
- [x] Capture argument/return projections and derive complete invocation JSONL plus bounded summaries.
- [x] Return actionable CodePath Plan compilation errors.
- [x] Replace fixed JDWP hit selection with observed/matched/captured budgets and first/periodic sampling.
- [x] Add generic top-frame value-path conditions and explicit local/field projections.
- [x] Keep JDWP event-thread suspension bounded and use one synchronous buffered writer.
- [x] Add SHA-verified, exact-filtered, bounded `evidence_query`.
- [x] Remove `analysis_complete`, `AnalysisResult`, final-answer persistence, and stale Case Digest fields.
- [x] Return exact Case/Analysis answer context and require full evidence identifiers.
- [x] Enforce CodePath Collection before a refining JDWP Plan and require Evidence lineage in Eval.
- [x] Expand the Demo with parallel, serial, and running-wafer strategy scenarios.
- [x] Add the versioned 50-case real OpenCode quality suite.
- [x] Remove empty and obsolete implementation artifacts in the modified feature scope.
- [x] Update current architecture, capability, Eval, and final audit documentation.

## Verification checklist

- [x] Red-Green-Refactor tests for runtime sequencing, answer context, Plan errors, Eval ordering, and ID rules.
- [x] CodePath contracts, compiler, launcher, normalizer, validator, and query tests.
- [x] JDWP contracts, compiler, collector, adapter, normalizer, validator, and query tests.
- [x] OpenCode adapter and asset tests.
- [x] Eval Harness parser, grader, runner, and ordering tests.
- [x] Root Maven reactor tests and integration tests.
- [x] Demo Maven tests.
- [x] Real CodePath, independent JDWP, and combined CodePath-to-JDWP sessions.
- [x] 50 unique real OpenCode quality scenarios with per-Case Workspace and interaction audits.
- [x] Build, install, installation check, and JDWP loopback verification.
- [x] Final stale-symbol, empty-directory, documentation-link, and Artifact audit.

## Acceptance interpretation

The 50-case full run initially passed 48 cases. `causal-04` exposed an Eval prompt/tool requirement
mismatch and passed after correction. `causal-05` exposed abbreviated evidence IDs and passed after
the answer contract was strengthened. A DeepSeek retry after the fix was externally blocked by HTTP
402 balance exhaustion; the final post-fix scenario passed with `opencode/big-pickle`. The Ling free
model did not finish the JDWP step and remains a model-capability failure, not an Agent collection
failure.

No old Workspace migration is provided. The project is still in the development stage and new
Workspace data is authoritative.