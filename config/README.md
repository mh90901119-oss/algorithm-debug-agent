# Agent path configuration

`agent-settings.json` remains the legacy CLI/OpenCode path file. `mcp-agent-settings.json` is the
only path configuration consumed by the Java-native MCP launcher; host adapters do not add path
settings to target repositories.

Every path is explicit so users can see and change the defaults. The installer expands only
`%USERPROFILE%` and `%LOCALAPPDATA%`; every resolved value must be an absolute path.

- `openCodeConfigDirectory`: OpenCode global configuration directory.
- `workspaceDirectory`: append-only Case, Run, Collection, Evidence, and Report storage.
- `dfxDirectory`: fallback diagnostic storage for interactions and Java CLI failures that occur before a Case identity is available. Normal Java execution logs are written under each Case.
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

## MCP settings

`mcp-agent-settings.json` contains only shared runtime settings:

- `workspaceDirectory`: append-only Case/Run/Collection/Evidence/Report root.
- `agentJavaHome`: optional JDK 21+ home; empty uses `JAVA_HOME` or `PATH`.
- `targetJavaHome`: optional target-test JDK home; empty uses Agent Java.
- `mavenExecutable`: optional explicit Maven executable.
- `knowledgeDirectory`: optional knowledge-hint directory. Missing or empty knowledge never blocks
  analysis. The host adapter accepts only bounded UTF-8 Markdown, rejects symbolic links and invalid
  files, sorts entries by relative path, and injects them into the generated subagent prompt with
  relative-path/SHA-256/size provenance. The exact file-count, depth, per-file and total-byte limits
  come from the Canonical Agent Definition. Knowledge never becomes Evidence and is never read by the
  Java Coordinator or Conclusion Gate.

The launcher derives every repository-owned JAR from its installation root. The file therefore has
no server JAR, Main class, Collector JAR, credentials, host profile or target-project field.
Changing `knowledgeDirectory` regenerates only host-owned Agent files; it never migrates, rewrites or
deletes Case Workspace artifacts.
