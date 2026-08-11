# Algorithm Debug Agent

Offline, evidence-driven assistant for reproducing deterministic algorithm UTs, explaining Gantt
results and locating the responsible input, strategy, runtime state and source code.

The Agent keeps deterministic collection and validation outside prompts. OpenCode/LLM plans and
explains; Maven/JUnit, static analysis, Code Path Tracer, JDWP Collector, normalizers and validators
produce verifiable facts.

## Current status

Phase 0 Baseline vertical slice is implemented. `ada-contracts`, `adapter-sdk`, `case-management`,
`debug-harness` and `wafer-demo-adapter` now support pre-run Case identity, dynamic result-directory
diffing, immutable Run capture, semantic result hashing and deterministic Baseline stability decisions.
Static analysis, CodePath/JDWP orchestration, evidence construction and explanation remain planned.

The approved OpenCode integration target keeps all product assets in this repository: one versioned
`algorithm-debug` Skill, an OpenCode agent/command/custom-tool adapter and the Java `ada` CLI. A one-time
OpenCode adapter installation will register references to the Agent installation without copying the
Skill into a global Skill directory. Normal use will then be:

```powershell
cd D:\path\to\target-algorithm
opencode
```

The user specifies a JUnit UT and asks a question. Each executed UT returns a bounded structured
summary plus immutable Artifact references; the Skill guides the model to answer from existing
evidence or request the next minimal action. The current phase does not implement an Algorithm Debug
MCP server or other CLI-runtime adapters.

The verified Reference Demo flow runs one dedicated UT twice, captures each result into a separate
Run directory and reaches `BASELINE_STABLE` only when both semantic hashes match.

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
