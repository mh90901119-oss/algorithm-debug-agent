# ADA launcher

Build the repository-owned CLI and collectors:

```powershell
mvn -Pcodepath-launcher package
```

`bin/ada.cmd` derives the Agent repository from its own location and selects the repository-owned CLI,
CodePath Launcher, and JDWP Collector JARs. It does not load a local override script or user JAR path.
Missing files produce an English error with the expected repository-relative location.

The launcher passes resolved paths internally to Java through arguments and environment variables.
Those values are subprocess transport and are not user configuration. Normal users configure paths
only in `config/agent-settings.json` and use the OpenCode integration.
