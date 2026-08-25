# Self-contained JDWP Runtime Implementation Plan

**Goal:** Make the Agent repository portable by using a bundled JDWP Collector without machine-local path configuration.

**Architecture:** Vendor the verified fat JAR and license under `tools/jdwp-collector`; resolve it from `bin/ada.cmd`, while retaining the environment variable only as an explicit override.

**Tech Stack:** Java 21, Maven, Windows batch, PowerShell verification.

### Task 1: Archive the verified runtime

- [ ] Copy `jdwp-batch-collector.jar` into `tools/jdwp-collector`.
- [ ] Copy the upstream license and add provenance documentation.

### Task 2: Add default discovery

- [ ] Update `bin/ada.cmd` to set the bundled path only when no override exists.
- [ ] Keep Doctor and Adapter behavior unchanged.

### Task 3: Update user-facing documentation

- [ ] Remove mandatory external-path setup from current docs.
- [ ] Document the optional developer override.

### Task 4: Verify portability

- [ ] Run Doctor with no process environment variable and no local config.
- [ ] Run affected Maven and OpenCode integration tests.
- [ ] Run installer discovery check.
