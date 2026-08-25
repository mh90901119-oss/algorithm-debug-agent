# Agent path configuration

`agent-settings.json` is the only user-edited path configuration file.

Every path is explicit so users can see and change the defaults. The installer expands only
`%USERPROFILE%` and `%LOCALAPPDATA%`; every resolved value must be an absolute path.

- `openCodeConfigDirectory`: OpenCode global configuration directory.
- `workspaceDirectory`: append-only Case, Run, Collection, Evidence, and Report storage.
- `dfxDirectory`: future local Agent diagnostic log storage.
- `evalDirectory`: development-only Eval Harness output storage.
- `resultJsonDirectory`: business algorithm JSON result directory. An absolute path is supported.
- `agentJavaHome`: optional JDK 21 home used to build and run the Agent. Empty uses the current environment.
- `targetJavaHome`: optional target JDK home used by the algorithm Maven/JUnit process and CodePath Launcher.
- `mavenExecutable`: optional absolute path to the corporate Maven executable. Empty resolves Maven normally.
- `dfxEnabled`: whether the future DFX plugin is enabled.

After changing this file, run `scripts/install-opencode.ps1 -Mode Install` and restart OpenCode.
Changing `workspaceDirectory` does not migrate or delete evidence in the previous Workspace.
Do not add an Agent configuration file to a target algorithm repository.

On a company computer, extract JDK 21 without changing system environment variables, set
`agentJavaHome` to that directory, keep `targetJavaHome` on the company algorithm JDK 17, and set
`mavenExecutable` only when the corporate Maven command is not already discoverable. The installer
prints the effective values. Invalid configured paths fail with an explicit English error.
