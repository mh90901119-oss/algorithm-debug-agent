# OpenCode adapter scaffold

This directory is the original repository-local scaffold. The approved target layout moves the single
canonical Skill to `skills/algorithm-debug/` and the OpenCode-specific Agent, commands and thin custom tool
to `integrations/opencode/`.

The adapter calls the stable `ada` CLI and never implements Maven, Code Path Tracer, JDWP, exception
interpretation or Case persistence itself. A one-time OpenCode adapter installation registers paths to
the Agent installation; normal users then enter the target algorithm repository and run `opencode`.
No Skill is copied to the global Skill directory, and the current phase has no Algorithm Debug MCP
server. These legacy files remain non-executable and exist only to point maintainers at the canonical assets.
