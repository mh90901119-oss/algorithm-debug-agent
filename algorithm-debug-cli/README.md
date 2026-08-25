# Algorithm Debug Java CLI

This module is the internal deterministic process boundary used by OpenCode Custom Tools. It validates
commands, executes Java services, and returns one bounded ToolResponse 2.0 JSON document.

Normal users do not configure paths through this CLI. The OpenCode installer resolves
`config/agent-settings.json`, and the Tool adapter supplies the validated Workspace, target module,
result directory, and temporary request paths to the subprocess.

The CLI reads repository-owned CodePath and JDWP JAR locations set by `bin/ada.cmd`. External JAR
overrides are not supported.
