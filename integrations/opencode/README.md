# OpenCode integration

The active chain is OpenCode/LLM -> algorithm-debug Agent -> Skill -> Custom Tool -> Java CLI ->
deterministic Java services. OpenCode plans and explains; Java executes, validates, and archives.

## Paths

All user-editable paths come from `config/agent-settings.json`. The installer resolves that file and
generates `lib/installation.mjs` in the OpenCode configuration directory. The Tool reads Workspace
and algorithm-result paths from that generated module. It does not infer paths, read a target-project
configuration file, or accept user path arguments.

Temporary question, plan, and analysis-result files are internal bounded process transport. They are
deleted after each Tool call. The internal Java CLI still receives resolved paths because it is a
subprocess boundary, not a user configuration interface.

## Case-local DFX

When `dfxEnabled` is true, the Tool Runtime writes bounded diagnostic events to
`<workspaceDirectory>/projects/<projectId>/cases/<caseId>/interaction.jsonl`. Events before a new Case
is known are buffered and flushed after `analysis_begin`; only a Case-creation failure uses
`<dfxDirectory>/unassigned/<sessionId>.jsonl`.

Open the JSONL file directly to review Tool and Java CLI order. It is diagnostic metadata, not an
Artifact or Evidence source. Recorder failures never replace the original ToolResponse.

## Install

```powershell
.\scripts\install-opencode.ps1 -Mode Install
.\scripts\install-opencode.ps1 -Mode Check
```

Edit `config/agent-settings.json` before installation when a default path must change. `Check` prints
all effective paths and verifies OpenCode capability discovery without binding to a CLI version.
Restart OpenCode after installation or configuration changes.

## Source checkout and dual JDK

The GitHub source checkout is the installed Agent. Run `scripts/build-agent.ps1` before the installer.
The script reads `agentJavaHome`, `targetJavaHome`, and `mavenExecutable` from the same repository
configuration and never changes system environment variables. The Agent and JDWP Collector use the
Agent JDK; algorithm Maven/JUnit and CodePath use the target JDK.

Start OpenCode from the company algorithm module. OpenCode can edit that repository when the user
requests a fix or optimization; the Agent Custom Tools remain responsible for deterministic UT
execution, collection, validation, and Workspace archiving.
