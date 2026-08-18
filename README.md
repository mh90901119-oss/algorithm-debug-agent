# Algorithm Debug Agent

Offline, evidence-driven assistant for reproducing deterministic algorithm UTs, explaining Gantt
results and locating the responsible input, strategy, runtime state and source code.

The Agent keeps deterministic collection and validation outside prompts. OpenCode/LLM plans and
explains; Maven/JUnit, static analysis, Code Path Tracer, JDWP Collector, normalizers and validators
produce verifiable facts.

## Current status

Phase 0 now contains a usable diagnostic vertical slice. The external Workspace control plane can
initialize a workspace, register an independent Maven algorithm module, open or resume a Case,
inspect its bounded history, explicitly run one supported JUnit method and append immutable
Case/Context/Analysis/Run documents. Each completed Run returns orthogonal process, test, Gantt,
target-failure and Agent-failure facts plus hashed Artifact references. A valid Gantt and/or target
failure now produces an immutable Run fingerprint and a write-once Context reproduction reference;
later Runs report `MATCHED` or `CHANGED` for the same or previous Context.

Gantt comparison deliberately ignores JSON formatting whitespace but preserves object/array order and
string content. It reports only changed dimensions, not a field-level Diff. Context is now an explicit,
minimal analysis-version identity: an existing Case reuses the latest Context by default and appends a
new one only when `--context-mode new` is requested. Static method analysis, exact method-level
CodePathTracer Plan/collection and the JDWP Plan/collection application flow are implemented. Every
successful Run, static Plan and Collection response now registers its Case-local Artifact references;
the CLI can read a hash-verified UTF-8 excerpt by Artifact ID and append a final Analysis result.
The real Wafer vertical-slice acceptance is complete: an uninstrumented target Run archived its
Gantt; exact CodePath and bounded JDWP collections matched that same-Context baseline and produced
usable Evidence; a later OpenCode model round reused the Case, read the registered JDWP summary in
bounded excerpts, and completed a new Analysis without rerunning the UT or collecting again. Dynamic
collections now expose both Case-relative provenance paths and registered `artifactIds` for model
reads. Input Analysis remains planned.

The approved OpenCode integration target keeps all product assets in this repository. The canonical
`algorithm-debug` Skill, bounded OpenCode Agent/Command/Custom Tool assets and the Java CLI exist.
The one-time installer is idempotent, preserves conflicting files as backups, and has been verified
against OpenCode 1.18.15. Install or check it with:

```powershell
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

Then restart any running OpenCode session. Normal use is:

```powershell
cd D:\path\to\target-algorithm
opencode
```

The intended experience is that the user specifies a JUnit UT and asks a question. Each executed UT
returns a bounded structured summary plus immutable Artifact references; the Skill guides the model to answer from existing
evidence or request the next minimal action. Use `/debug-case <target UT and question>` or ask the
same information directly. The current phase does not implement an Algorithm Debug MCP server or
other CLI-runtime adapters.

The verified Reference Demo flow runs one dedicated UT twice, captures each result into a separate
Run directory and confirms equal raw and JSON Token content SHA-256 values.

## Current CLI slice

Build the executable JAR and CodePath Launcher once, then use the repository-owned launcher from any
directory. `case open` only archives the question
and context; it does not run the UT. Reuse the returned IDs only when the model decides a new Run is
needed.

```powershell
mvn -Pcodepath-launcher package
$ada = "D:\tools\algorithm-debug-agent\bin\ada.cmd"
& $ada workspace init --root D:\agent-workspace
& $ada project register --workspace D:\agent-workspace --project D:\large-system\algorithm-module
& $ada case open --workspace D:\agent-workspace --project-id <projectId> --test fully.qualified.Test#method --question-file question.txt [--context-mode reuse|new]
& $ada case inspect --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId>
& $ada run execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId>
& $ada static analyze --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId>
& $ada plan codepath create --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --request-file codepath-plan-request.json
& $ada collection codepath execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --plan-id <planId>
& $ada plan jdwp create --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --request-file jdwp-plan-request.json
& $ada collection jdwp execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --plan-id <planId>
& $ada artifact read --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --artifact-id <artifactId> [--offset-bytes 0] [--max-bytes 16384]
& $ada analysis complete --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --result-file analysis-result.json
```

JDWP execution treats the Collector as one configured local JAR. Before starting OpenCode/CLI,
set its location:

```powershell
$env:ADA_JDWP_COLLECTOR_JAR = "D:\mcpcode\mcp-jdwp-java\jdwp-batch-collector\target\jdwp-batch-collector.jar"
```

也可复制 `bin\ada.local.example.cmd` 为被 Git 忽略的 `bin\ada.local.cmd`，只在其中保存本机 JDWP
路径。启动器自动配置 CodePath Launcher 及其 SHA-256。详见 `bin\README.md`。

`doctor` reports whether the configured path points to a regular JAR file without starting a target JVM.
The Agent records the configured Collector version but does not require a repository-pinned JAR
fingerprint; a malformed or incompatible JAR is retained as a structured tool execution failure.
Every execution creates a new Collection and returns only a bounded summary plus relative Artifact
references. Raw JDWP events, the external Collector Manifest, Agent Manifest, four process logs,
optional Gantt and Baseline check remain in that Collection directory.

JDWP value-depth, item-count and summary budgets deliberately preserve limit markers. When the
Collector completed, at least one tracepoint hit exists, artifact/plan/provenance checks pass, and the
Gantt baseline is `MATCHED`, those bounded observed values remain usable runtime evidence; the model
must not infer that omitted values do not exist. A partial CodePath trace remains inconclusive because
missing call events can change the path itself.

CodePath collection follows the same Plan-then-execute rule. Its v2 Plan contains only exact
class/method/descriptor selectors and event/byte/time budgets. The Launcher writes one Raw JSONL stream;
there is no package-superset collection or post-filter artifact. The current supported target is a
single-thread UT. The unchanged upstream tracer can still incur Advice callbacks for unselected methods,
so large-algorithm cost must be confirmed with the supplied real-project smoke and measurements.

The packaged CLI currently loads the Wafer Demo Adapter. Supporting an arbitrary algorithm module
requires a compatible Adapter; the CLI does not guess Gantt locations.

## Build

```powershell
.\mvnw.cmd test
```

If the Maven Wrapper is unavailable, use:

```powershell
mvn test
```

## Development workflow

1. Read `AGENTS.md` and `docs/development/development-rules.md`.
2. Check architecture, ADRs and existing designs before implementation.
3. Create or update an implementable design under `docs/designs` when required.
4. Develop behavior with test-first Red-Green-Refactor.
5. Verify affected modules and synchronize contracts, documentation and Eval cases.

## Start here

- `AGENTS.md`
- `docs/development/development-rules.md`
- `docs/designs/implementation-design-template.md`
- `docs/architecture/README.md`
- `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- `docs/architecture/algorithm-debug-agent-complete-design.md`
- `docs/plans/algorithm-debug-agent-development-plan.md`
- `docs/README.md`

## Repository boundaries

- This repository owns Agent orchestration, contracts, tooling adapters, knowledge and OpenCode integration.
- The wafer scheduling demo remains in `D:\javacode\hellomvn`.
- JDWP/JDI implementation remains in `D:\mcpcode\mcp-jdwp-java`.
- Code Path Tracer and its external JUnit Bundle remain in `D:\mcpcode\code-path-tracer`.
