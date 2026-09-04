# Runtime Evidence Quality Final Audit

Date: 2026-09-03
Scope: single-session sequencing, CodePath v4, JDWP v4, bounded evidence use, direct answers, and
50-scenario real OpenCode quality validation.

## Implementation audit

| Area | Result |
|---|---|
| Target execution sequencing | One closure-scoped non-blocking guard; no file lock, Java lock, queue, or cross-session state. |
| CodePath | Exact Method Catalog keys, canonical scalar projections, invocation derivation, actionable Plan errors. |
| JDWP | Generic scalar condition, explicit projections, observed/matched/captured budgets, synchronous buffered JSONL. |
| Evidence access | One SHA-verified `evidence_query`; no business interpretation or cross-Collection automatic join. |
| Final answer | Direct response, exact Case/Analysis directories, capability summary, full evidence identifiers. |
| Persistence | Immutable Case/Run/Collection/Evidence; no `analysis_complete` or model-authored answer file. |
| Demo | Reproduction plus parallel, serial, running, algorithm-exception, and assertion scenarios. |
| Repository hygiene | Stale AnalysisResult public messages and empty Skill reference directory removed. |

## Real OpenCode evidence

Configured report root is `${evalDirectory}`.

- Independent CodePath pass: `20260902215423-ecb2486e`.
- Independent JDWP pass: `20260902220235-b971d892`.
- Strict combined refinement pass: `20260902222303-8e0a7cd5`.
- Full 50-case run: `20260902223741-70606a34`, 48 passed and 2 findings.
- Corrected `causal-04` pass: `20260903004041-a57393a1`.
- Corrected `causal-05` pass with `opencode/big-pickle`: `20260903010232-541d3840`.

The two full-run findings were not hidden:

- `causal-04` required CodePath although its question did not explicitly request it. The Suite was corrected
  and the rerun passed with CodePath followed by JDWP.
- `causal-05` abbreviated full Run/Evidence IDs. Skill and Agent now forbid identifier abbreviation. A
  DeepSeek rerun was externally blocked by HTTP 402 balance exhaustion; Ling did not finish JDWP; the
  final post-fix scenario passed with another recorded model.

Across the final implementation, every one of the 50 unique scenario definitions has a passing real
OpenCode result. The report model identity remains part of `environment.json`; results from different
models are not presented as one homogeneous model score.

## Workspace audit

Every passing Eval Case runs `case audit` and the Harness independently compares expected and actual
files. Dynamic Cases require collection request, Plan, manifest, Raw trace, logs, baseline result,
derived summary, normalization manifest, validation, Evidence Bundle, sufficiency result, Artifact
registration, Case interaction JSONL, and Case log. Empty directories and unregistered files fail the
audit. Intentional integrity Cases append bytes to one Artifact and pass only when SHA/size mismatch is
detected.

## Final deterministic verification

- Root `mvn test`: all 19 reactor modules succeeded; CLI 27 tests and integration module 10 tests passed.
- OpenCode adapter tests: 48 passed, 0 failed.
- Eval Harness tests: 18 passed, 0 failed.
- Demo `mvn test`: 14 passed, 0 failed.
- `scripts/build-agent.ps1`: build succeeded and bundled CLI/CodePath/JDWP artifacts were rebuilt.
- `scripts/install-opencode.ps1 -Mode Install` and `-Mode Check`: succeeded.
- `scripts/verify-jdwp-loopback.ps1`: returned `JDWP_LOOPBACK_OK`.

## Residual boundaries

- Multi-session target execution concurrency is not handled.
- Static analysis is bounded and may be incomplete for reflection or unresolved dependencies.
- JDWP and CodePath remain intrusive reruns, not zero-overhead observation.
- Model quality and provider availability affect whether the LLM finishes a valid tool workflow.
- Exact business interpretation remains an LLM responsibility and must be separated from confirmed facts.
