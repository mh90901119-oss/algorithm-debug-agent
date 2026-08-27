# Agent path configuration

`agent-settings.json` is the only user-edited path configuration file.

Every path is explicit so users can see and change the defaults. The installer expands only
`%USERPROFILE%` and `%LOCALAPPDATA%`; every resolved value must be an absolute path.

- `openCodeConfigDirectory`: OpenCode global configuration directory.
- `workspaceDirectory`: append-only Case, Run, Collection, Evidence, and Report storage.
- `dfxDirectory`: fallback diagnostic storage for interactions that fail before a Case identity is available.
- `evalDirectory`: development-only Eval Harness output storage.
- `resultJsonDirectory`: business algorithm JSON result directory. Use `${runDate}` for a daily
  `yyyy-MM-dd` directory, for example `D:\\log\\scheduler\\${runDate}\\gant`. A fixed absolute path
  remains supported. The token is resolved immediately before each UT run, not during installation.
- `agentJavaHome`: optional JDK 21 home used to build and run the Agent. Empty uses the current environment.
- `targetJavaHome`: optional target JDK home used by the algorithm Maven/JUnit process and CodePath Launcher.
- `mavenExecutable`: optional absolute path to the target-environment Maven executable. Empty resolves Maven normally.
- `dfxEnabled`: whether the implemented Case interaction recorder and fallback diagnostics are enabled.

After changing this file, run `scripts/install-opencode.ps1 -Mode Install` and restart OpenCode.
Changing `workspaceDirectory` does not migrate or delete evidence in the previous Workspace.
Do not add an Agent configuration file to a target algorithm repository.

On a target-environment computer, extract JDK 21 without changing system environment variables, set
`agentJavaHome` to that directory, keep `targetJavaHome` on the target algorithm JDK 17, and set
`mavenExecutable` only when the target-environment Maven command is not already discoverable. The installer
prints the effective values. Invalid configured paths fail with an explicit English error.
