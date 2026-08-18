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
The P3 JDWP release audit and real Wafer one-point smoke are complete: the target UT produced three
bounded hits, its normalized Gantt Hash matched the no-collection same-Context baseline, and no
managed process survived. Input Analysis, Evidence construction, the OpenCode installer and
end-to-end `/debug-case` model workflow remain planned.

The approved OpenCode integration target keeps all product assets in this repository. The canonical
`algorithm-debug` Skill, bounded OpenCode agent/command/custom-tool contract assets and the Java
CLI exist; the one-time installer and pinned-version OpenCode loading verification are not yet
implemented. After that adapter work is complete, normal use will be:

```powershell
cd D:\path\to\target-algorithm
opencode
```

The intended experience is that the user specifies a JUnit UT and asks a question. Each executed UT
returns a bounded structured summary plus immutable Artifact references; the Skill guides the model to answer from existing
evidence or request the next minimal action. The current phase does not implement an Algorithm Debug
MCP server or other CLI-runtime adapters.

The verified Reference Demo flow runs one dedicated UT twice, captures each result into a separate
Run directory and confirms equal raw and JSON Token content SHA-256 values.

## Current CLI slice

Build the executable JAR, then invoke it from any directory. `case open` only archives the question
and context; it does not run the UT. Reuse the returned IDs only when the model decides a new Run is
needed.

```powershell
mvn -pl algorithm-debug-cli -am package
$ada = "D:\tools\algorithm-debug-agent\algorithm-debug-cli\target\algorithm-debug-cli-0.1.0-SNAPSHOT-all.jar"
java -jar $ada workspace init --root D:\agent-workspace
java -jar $ada project register --workspace D:\agent-workspace --project D:\large-system\algorithm-module
java -jar $ada case open --workspace D:\agent-workspace --project-id <projectId> --test fully.qualified.Test#method --question-file question.txt [--context-mode reuse|new]
java -jar $ada case inspect --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId>
java -jar $ada run execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId>
java -jar $ada static analyze --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId>
java -jar $ada plan codepath create --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --request-file codepath-plan-request.json
java -jar $ada collection codepath execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --plan-id <planId>
java -jar $ada plan jdwp create --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --request-file jdwp-plan-request.json
java -jar $ada collection jdwp execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --plan-id <planId>
java -jar $ada artifact read --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --artifact-id <artifactId> [--offset-bytes 0] [--max-bytes 16384]
java -jar $ada analysis complete --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId> --result-file analysis-result.json
```

JDWP execution treats the Collector as one configured local JAR. Before starting OpenCode/CLI,
set its location:

```powershell
$env:ADA_JDWP_COLLECTOR_JAR = "D:\mcpcode\mcp-jdwp-java\jdwp-batch-collector\target\jdwp-batch-collector.jar"
```

`doctor` reports whether the configured path points to a regular JAR file without starting a target JVM.
The Agent records the configured Collector version but does not require a repository-pinned JAR
fingerprint; a malformed or incompatible JAR is retained as a structured tool execution failure.
Every execution creates a new Collection and returns only a bounded summary plus relative Artifact
references. Raw JDWP events, the external Collector Manifest, Agent Manifest, four process logs,
optional Gantt and Baseline check remain in that Collection directory.

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
