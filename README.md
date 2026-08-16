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
target-failure and Agent-failure facts plus hashed Artifact references.

Baseline comparison is deliberately still `NOT_COMPARED`. The next approved slice only adds a
whitespace-insensitive JSON content fingerprint and simple `MATCHED/CHANGED` comparison; field-level
Gantt Diff is deferred. Input Analysis, CodePathTracer/JDWP orchestration, Evidence construction,
the OpenCode installer and end-to-end `/debug-case` model workflow remain planned.

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
Run directory and reaches `BASELINE_STABLE` only when both semantic hashes match.

## Current CLI slice

Build the executable JAR, then invoke it from any directory. `case open` only archives the question
and context; it does not run the UT. Reuse the returned IDs only when the model decides a new Run is
needed.

```powershell
mvn -pl algorithm-debug-cli -am package
$ada = "D:\tools\algorithm-debug-agent\algorithm-debug-cli\target\algorithm-debug-cli-0.1.0-SNAPSHOT-all.jar"
java -jar $ada workspace init --root D:\agent-workspace
java -jar $ada project register --workspace D:\agent-workspace --project D:\large-system\algorithm-module
java -jar $ada case open --workspace D:\agent-workspace --project-id <projectId> --test fully.qualified.Test#method --question-file question.txt
java -jar $ada case inspect --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId>
java -jar $ada run execute --workspace D:\agent-workspace --project-id <projectId> --case-id <caseId> --analysis-id <analysisId>
```

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
