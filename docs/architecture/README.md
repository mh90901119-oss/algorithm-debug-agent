# Architecture documentation index

Recommended reading order:

1. [Tool validation baseline](tool-validation-baseline.md) — what CodePathTracer and JDWP have actually proved.
2. [Complete architecture and development plan](algorithm-debug-agent-complete-design.md) — product goals and end-to-end architecture.
3. [Module detailed design](algorithm-debug-agent-module-detailed-design-v1.md) — repository modules, contracts and implementation phases.
4. [Architecture document audit](architecture-document-audit-2026-08-10.md) — why each document was revised.
5. [JDWP refactoring and usage](jdwp-mcp-collector-refactoring-design-and-usage.md) — implemented Collector MVP and manual workflow.
6. [JDWP P0 performance hardening](jdwp-collector-p0-performance-hardening-design.md) — designed but not yet completed large-algorithm hardening.
7. [Explicit Context and exact CodePath ADR](../decisions/ADR-010-explicit-context-and-exact-codepath.md) — current Context transition and CodePath collection decision.
8. [Context and CodePath simplification design](../designs/2026-08-18-context-codepath-simplification-design.md) — approved v2 implementation contract.

Interpretation rule:

```text
tool-validation-baseline = verified facts
complete design          = product and architecture target
module detailed design   = Agent implementation contract
JDWP refactoring         = current tool MVP
JDWP P0                  = pending performance design
ADR-010 + simplification = current Context/CodePath implementation contract
```

When documents appear to conflict, do not assume the more ambitious field or workflow is already
implemented. Check the validation baseline and the status section of the owning document.
