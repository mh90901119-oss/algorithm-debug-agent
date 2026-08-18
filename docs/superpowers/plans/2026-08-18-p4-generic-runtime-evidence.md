# P4 Generic Runtime Evidence Implementation Plan

> **Required sub-skill:** Use `superpowers:test-driven-development` for every behavior change, `superpowers:systematic-debugging` for unexpected failures, and `superpowers:verification-before-completion` before every completion or commit claim.

**Goal:** Convert archived CodePathTracer and JDWP raw artifacts into bounded, deterministic, provenance-backed evidence that OpenCode can inspect without loading a complete trace.

**Architecture:** `trace-normalizer` streams tool JSONL into domain-neutral summaries; `trace-validator` verifies identity, hashes, plans, source, baseline and provenance; `evidence-engine` combines valid same-context facts into an Evidence Bundle and evaluates only the dimensions requested by the model. `ada-core` orchestrates these deterministic modules and `case-management` owns append-only paths. No P4 component infers algorithm business meaning.

**Tech Stack:** Java 21, Maven reactor, JUnit 5, Jackson 2.17.2, JDK NIO, SHA-256.

**Spec:** `docs/designs/2026-08-18-p4-generic-runtime-evidence-design.md`

## Global Constraints

- Do not modify the target algorithm source, UT, POM or production configuration.
- Do not add token/password detection, sensitive-field rules, value-path allowlists, deny rules or automatic redaction. Target UT values already captured into the local Case are authorized analysis data.
- Keep event, record, frame, value, scalar-preview and output-byte budgets. These bounds prevent large-algorithm data explosion; they are not content filters.
- Raw artifacts are immutable. Every derivation receives a new `EvidenceId` and uses create-new writes below `collections/<collectionId>/derived/<evidenceId>/` and `evidence/<evidenceId>/`.
- CodePath filtered events establish only `NEAREST_RETAINED_ANCESTOR`, never a complete direct-call graph.
- Deterministic P4 code emits only `CONFIRMED_FACT`, `VALIDATOR_CONCLUSION` and `MISSING_EVIDENCE`.
- `SUFFICIENT` means requested dimensions are technically covered; it never means that a root cause is confirmed.
- A reproducible target UT failure is a valid `TARGET_OUTCOME`. Missing Gantt only withholds `SCHEDULE_RESULT`; it does not invalidate method/runtime facts collected before the failure.
- Each task follows RED → GREEN → REFACTOR, runs affected tests, audits the changed module, fixes discovered defects with regression tests, and commits independently.

## Task 1: Define versioned P4 contracts and JSON Schemas

**Files:**

- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/NormalizationStatus.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceValidationStatus.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SufficiencyStatus.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceDimension.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ClaimClassification.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/TraceProvenance.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/NormalizationBudget.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/NormalizationManifest.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodPathSummary.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/JdwpSnapshotSummary.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ValidationFinding.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionValidation.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceBuildRequest.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceFact.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceBundle.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SufficiencyEvaluation.java`
- Create seven Schema files under `schemas/trace/` and `schemas/evidence/`
- Create contract, JSON round-trip and Schema tests under `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/`

**Public contract shape:**

```java
public record TraceProvenance(
        CaseId caseId, ContextId contextId, RunId runId, CollectionId collectionId,
        ArtifactReference rawArtifact, long jsonlLine,
        Optional<Long> eventId, Optional<Long> sequence, String observationKind) {}

public record EvidenceBuildRequest(
        String schemaVersion, EvidenceId evidenceId, CaseId caseId, ContextId contextId,
        AnalysisId analysisId, List<CollectionId> collectionIds,
        List<CollectionId> comparisonCollectionIds,
        Set<EvidenceDimension> requiredDimensions,
        long maxSummaryBytes, long maxEvidenceBundleBytes, Instant createdAt) {}
```

`MethodPathSummary` owns nested immutable `MethodStatistic`, `ObservedPath` and `PathAnomaly` records. `JdwpSnapshotSummary` owns nested immutable `TracepointHit`, `StackFrame`, `ValueFact` and `CollectorLimitFact` records. Lists have explicit maximum counts; scalar preview is at most 1,024 characters. Value facts contain path, kind, optional runtime type, preview, collector markers and provenance, with no field-name interpretation.

**TDD steps:**

1. RED: add construction tests that reject unsupported versions, mixed identities, absolute/traversal paths, negative counters, oversized collections and invalid SHA-256 values.
2. RED: add tests proving `EvidenceBuildRequest` accepts 0–16 current collections, 0–16 comparison collections, 1–7 requested dimensions, automatically requires callers to include no policy field, and rejects duplicate IDs across the two roles.
3. RED: add a test proving a `TARGET_OUTCOME` fact may describe `TestOutcome.ERROR` with a `TargetFailureDiagnostic` and no Gantt fact.
4. RED: add round-trip tests with the repository ObjectMapper modules and strict Schema tests that reject unknown fields.
5. GREEN: implement immutable records, enums, validation and `SchemaVersions` constants with Chinese Javadoc.
6. GREEN: implement seven draft-2020-12 Schemas matching Java constraints and examples.
7. REFACTOR: centralize only genuinely repeated bounds in `NormalizationBudget`; do not expose implementation types.
8. Verify: `mvn -pl ada-contracts -am test` and parse every `schemas/**/*.schema.json` with Jackson.
9. Audit: DTO immutability, enum semantics, version compatibility, no business-domain fields and no sensitive-value policy fields.
10. Commit: `feat: define generic runtime evidence contracts`.

## Task 2: Add append-only Case paths and safe artifact access

**Files:**

- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Create `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArtifactAccess.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseDigestReader.java`
- Modify `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`
- Create `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArtifactAccessTest.java`
- Modify `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseDigestReaderTest.java`

**Public API:**

```java
public Path createEvidenceRequest(EvidenceBuildRequest request);
public Path createNormalizationManifest(NormalizationManifest manifest);
public Path createMethodPathSummary(MethodPathSummary summary);
public Path createJdwpSnapshotSummary(JdwpSnapshotSummary summary);
public Path createCollectionValidation(CollectionValidation validation);
public Path createEvidenceBundle(EvidenceBundle bundle);
public Path createSufficiencyEvaluation(SufficiencyEvaluation evaluation);
public <T> T requireCaseDocument(CaseId caseId, String relativePath, Class<T> type);

public final class CaseArtifactAccess {
    public Path requireRegularArtifact(CaseId caseId, String relativePath, long maxBytes);
    public ArtifactReference describe(CaseId caseId, String artifactId,
                                      String artifactType, String mediaType, Path path);
}
```

**TDD steps:**

1. RED: assert exact paths for the derived and evidence layouts from the approved design.
2. RED: assert every create operation is create-new and that a second derivation uses a different `EvidenceId` rather than overwriting.
3. RED: reject absolute paths, `..`, symlinks, directories, artifacts outside the Case and files over the supplied limit.
4. RED: prove a legacy Case without P4 children remains readable and its digest reports no evidence instead of failing.
5. GREEN: add typed layout methods and repository create/read methods, reusing `AtomicDocumentWriter` and `BoundedDocumentMapper`.
6. GREEN: implement `CaseArtifactAccess` as the only public conversion from Case-relative artifact names to trusted local `Path` objects.
7. REFACTOR: share creation of `raw/derived/logs/validation` between CodePath and JDWP collection start methods; add missing JDWP `derived/` without changing old Case reads.
8. Verify: `mvn -pl case-management -am test`.
9. Audit: path containment, symlink behavior, write-once guarantees, cause preservation and compatibility.
10. Commit: `feat: archive append-only evidence artifacts`.

## Task 3: Stream and normalize CodePathTracer events

**Files:**

- Expand `trace-normalizer/pom.xml`
- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/BoundedJsonlReader.java`
- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/NormalizationException.java`
- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/MethodPathNormalizer.java`
- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/NormalizationResult.java`
- Create CodePath fixtures and tests under `trace-normalizer/src/test/`

**Public API:**

```java
public final class BoundedJsonlReader {
    public void read(Path input, long maxInputBytes, int maxRecordBytes,
                     long maxRecords, JsonRecordConsumer consumer);
}

public final class MethodPathNormalizer {
    public NormalizationResult<MethodPathSummary> normalize(
            MethodPathCollectionRecord request, CodePathCollectionPlan plan,
            MethodPathManifest manifest, ArtifactReference filteredTrace,
            Path filteredTracePath, EvidenceId evidenceId, NormalizationBudget budget);
}
```

**TDD steps:**

1. RED: `BoundedJsonlReaderTest` covers LF, CRLF, final line without newline, UTF-8 crossing buffer boundaries, malformed UTF-8, invalid JSON, 4 MiB record hard limit, 50 MiB file hard limit and record count hard limit.
2. RED: `MethodPathNormalizerTest` covers balanced enter/exit, multiple threads, nested retained ancestors, duplicate/reversed event IDs, unmatched exit, EOF-open enter, illegal depth and zero retained events.
3. RED: assert that skipped methods cannot create `DIRECT_CALL`; every relation is `NEAREST_RETAINED_ANCESTOR` and points to exact raw line/event IDs.
4. RED: assert `CLASS_METHOD_SUPERSET` descriptor degradation and output-budget truncation produce `PARTIAL`, explicit limit facts and no fake empty success.
5. GREEN: implement fixed-buffer JSONL streaming and bounded per-thread retained stacks/statistics.
6. GREEN: return DTOs only; keep Jackson event DTOs package-private and never depend on the CodePath implementation module.
7. REFACTOR: deterministic ordering by method key, thread name, first event and anomaly code.
8. Verify: `mvn -pl trace-normalizer -am test`.
9. Audit: memory is bounded by configured aggregate limits, provenance is exact, raw file is not loaded wholly, no business inference or value filtering exists.
10. Commit: `feat: normalize bounded codepath traces`.

## Task 4: Stream and normalize JDWP snapshots

**Files:**

- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/JdwpSnapshotNormalizer.java`
- Create `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/JdwpValueFlattener.java`
- Create JDWP lifecycle/value fixtures and tests under `trace-normalizer/src/test/`

**Public API:**

```java
public final class JdwpSnapshotNormalizer {
    public NormalizationResult<JdwpSnapshotSummary> normalize(
            JdwpCollectionRecord request, JdwpCollectionPlan plan,
            JdwpCollectionManifest manifest, ArtifactReference rawTrace,
            Path rawTracePath, EvidenceId evidenceId, NormalizationBudget budget);
}
```

**TDD steps:**

1. RED: cover lifecycle-only, stack-only hit, multiple tracepoints/hits/threads, locals, `this`, primitives, strings, objects, arrays and nested fields.
2. RED: preserve `$type/$id/$cycle/$truncated/$remaining/$remainingFields/$collected/$error` as structural/limit facts.
3. RED: assert paths such as `locals.context.fields.job.fields.jobId` are stable and values are preserved up to the scalar preview budget regardless of field name.
4. RED: assert event sequence gaps/duplicates, unknown lifecycle event, missing required location, per-hit frame overflow, zero hit and output/value budgets produce explicit `PARTIAL` or failure semantics.
5. GREEN: implement bounded recursive flattening limited by facts, depth inherited from captured structure, scalar preview and output estimates; do not perform sensitive-name classification.
6. GREEN: keep external Collector JSON types package-private and convert only verified fields into public contracts.
7. REFACTOR: deterministic ordering by sequence, frame index and value path.
8. Verify: `mvn -pl trace-normalizer -am test`.
9. Audit: every value/stack fact has provenance, lifecycle is not presented as business evidence, no complete raw line is retained beyond parsing.
10. Commit: `feat: normalize bounded jdwp snapshots`.

## Task 5: Validate collection evidence deterministically

**Files:**

- Expand `trace-validator/pom.xml`
- Create `trace-validator/src/main/java/org/example/algorithmdebug/validator/ArtifactIntegrityVerifier.java`
- Create `trace-validator/src/main/java/org/example/algorithmdebug/validator/ProvenanceVerifier.java`
- Create `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Create tests and tamper fixtures under `trace-validator/src/test/`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- Modify the two corresponding core test classes

**Validation API:**

```java
public final class CollectionEvidenceValidator {
    public CollectionValidation validateMethodPath(MethodPathValidationInput input);
    public CollectionValidation validateJdwp(JdwpValidationInput input);
}
```

**TDD steps:**

1. RED: validate successful CodePath and JDWP fixture identities, raw size/SHA, plan SHA, source snapshot, manifest completion, baseline and summary provenance.
2. RED: missing/tampered artifact, schema mismatch, identity mismatch and invalid provenance yield `INVALID`.
3. RED: source drift, plan mismatch or baseline `CHANGED` yield `CONTRADICTED`; truncation, zero useful event/hit or partial summary yield `INCONCLUSIVE`.
4. RED regression: an uninstrumented run with only `targetFailureSha256` and a collection run with the same normalized failure fingerprint produce baseline `MATCHED`; pre-failure CodePath/JDWP facts can remain usable when other checks pass.
5. RED regression: changed failure fingerprint is `CHANGED`; failure without an extractable fingerprint remains `INCOMPARABLE`. Absence of Gantt with a matching failure must not itself invalidate `TARGET_OUTCOME`.
6. GREEN: add the smallest shared target-outcome fingerprint extraction/comparison path to the two collection services. Reuse existing Surefire diagnostics and `RunResultFingerprint`; do not enumerate business exception classes.
7. GREEN: implement integrity, provenance and collection validation with stable finding codes and bounded details.
8. REFACTOR: remove duplicated deterministic hash/path checks only after both collector suites are green.
9. Verify: `mvn -pl trace-validator,ada-core -am test`.
10. Audit: target failure remains analyzable, Agent/tool failure is distinct, all contradictions block confirmation and validation never calls an LLM.
11. Commit: `feat: validate runtime evidence and failure baselines`.

## Task 6: Build bounded Evidence Bundles and evaluate sufficiency

**Files:**

- Expand `evidence-engine/pom.xml`
- Create `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceBundleBuilder.java`
- Create `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceSufficiencyEvaluator.java`
- Create focused tests under `evidence-engine/src/test/`

**Public API:**

```java
public final class EvidenceBundleBuilder {
    public EvidenceBundle build(EvidenceBuildRequest request, EvidenceBuildSources sources);
}

public final class EvidenceSufficiencyEvaluator {
    public SufficiencyEvaluation evaluate(EvidenceBuildRequest request, EvidenceBundle bundle);
}
```

**TDD steps:**

1. RED: a valid current `RunOutcomeSummary` covers `TARGET_OUTCOME`; a present input hash covers `INPUT`; source snapshot/catalog covers `SOURCE`.
2. RED: only `VALID` CodePath/JDWP validations cover `METHOD_PATH`/`RUNTIME_STATE`; `INCONCLUSIVE` and `INVALID` facts stay visible as diagnostics but do not cover.
3. RED: Gantt artifact plus verifiable normalized result fingerprint covers `SCHEDULE_RESULT`; a target exception without Gantt covers only `TARGET_OUTCOME`.
4. RED: same-context old Analysis collections may cover current requirements; different-context collections are accepted only as comparison facts and never cover current dynamic dimensions.
5. RED: any blocking contradiction returns `CONTRADICTED`; missing requested dimensions return `INSUFFICIENT`; all requested dimensions plus `VALIDATION` return `SUFFICIENT`.
6. RED: reject `SOURCE_INFERENCE` and `LLM_HYPOTHESIS` as deterministic builder output or dimension coverage.
7. GREEN: build bounded facts and artifact catalogs ordered by dimension, identity and source; truncate only non-required comparison details before failing the hard bundle limit.
8. REFACTOR: keep coverage rules in one exhaustive switch over `EvidenceDimension`.
9. Verify: `mvn -pl evidence-engine -am test`.
10. Audit: sufficiency does not claim root cause, cross-context facts cannot masquerade as current facts, bundle size and list limits are deterministic.
11. Commit: `feat: build bounded evidence bundles`.

## Task 7: Orchestrate P4 in Core and expose stable CLI commands

**Files:**

- Modify `ada-core/pom.xml`
- Create `ada-core/src/main/java/org/example/algorithmdebug/core/EvidenceApplicationService.java`
- Create `ada-core/src/main/java/org/example/algorithmdebug/core/EvidenceBuildSummary.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Create `ada-core/src/test/java/org/example/algorithmdebug/core/EvidenceApplicationServiceTest.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommand.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify CLI tests

**Commands:**

```text
ada evidence build --workspace <dir> --request-file <bounded-json>
ada evidence inspect --workspace <dir> --project-id <id> --case-id <id> --evidence-id <id>
```

**TDD steps:**

1. RED: reject unknown options, inline unbounded JSON, duplicate collection roles, wrong Case/Context/Analysis identity and unavailable legacy evidence.
2. RED: build assigns a new `EvidenceId`, archives the request first, normalizes and validates each explicit collection, then archives Bundle and Sufficiency using create-new paths.
3. RED: malformed raw trace still leaves request and failed normalization manifest; it creates no fake summary and returns a structured domain error with artifact references.
4. RED: `inspect` reads only bounded P4 documents and returns status, covered/missing dimensions and Case-relative paths, never raw values or absolute paths.
5. GREEN: implement `EvidenceApplicationService` with injected clock/ID/archive/normalizer/validator/engine dependencies and add it to `ControlPlaneServices`.
6. GREEN: add sealed CLI records and strict argument parsing consistent with existing commands.
7. REFACTOR: keep CLI as a thin JSON control surface; no normalization or validation rules in CLI.
8. Verify: `mvn -pl ada-core,algorithm-debug-cli -am test`.
9. Audit: orchestration order, partial-failure archive, error causes/codes, bounded ToolResponse and backward-compatible existing commands.
10. Commit: `feat: expose evidence build and inspect commands`.

## Task 8: End-to-end verification, documentation and module audit

**Files:**

- Create/modify P4 integration tests under `integration-tests/src/test/`
- Create `trace-normalizer/README.md`, `trace-validator/README.md`, `evidence-engine/README.md`
- Modify `schemas/README.md`
- Modify `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify `docs/designs/2026-08-18-p4-generic-runtime-evidence-design.md`
- Modify the repository-owned OpenCode Skill guidance under `integrations/opencode/`
- Modify root `README.md` only where user-visible commands/layout need updating

**TDD and audit steps:**

1. RED/GREEN: end-to-end temporary Case for CodePath raw → summary → validation → bundle → sufficiency.
2. RED/GREEN: end-to-end temporary Case for JDWP raw → summary → validation → bundle → sufficiency.
3. RED/GREEN: assertion failure with Gantt, reproducible algorithm exception without Gantt, missing input failure, collector failure, source drift, baseline change and same-context history reuse.
4. Add generated 1,000,000-event CodePath and 1,000-hit JDWP boundary tests behind the existing performance-test convention; verify bounded output and record deterministic counters rather than universal latency claims.
5. Update the OpenCode Skill: inspect existing Evidence first; read summaries/validation/bundle; use provenance to request a raw slice only when necessary; generate another collection plan only when dimensions are missing.
6. Document that P4 has no sensitive-field classification and that budgets protect stability, not authorization.
7. Run `mvn test`; when external tool locks are available, run the existing CodePath and JDWP real-smoke profiles plus the P4 derivation smoke.
8. Run `git diff --check`, parse every JSON/Schema/config document, and scan for `TODO`, placeholders, absolute local paths, domain-specific Wafer terms in P4 production modules and obsolete sensitive-value policy terms.
9. Audit each changed module against `docs/development/development-rules.md`; add regression tests before every defect fix.
10. Update the design completion record with exact tests, smoke commands, known limitations and commit identities.
11. Use `superpowers:requesting-code-review`, address actionable findings, then use `superpowers:verification-before-completion` on a clean final verification run.
12. Commit: `docs: complete P4 runtime evidence pipeline`.

## Plan Self-Review

- Spec coverage: contracts, streaming normalization, deterministic validation, same-context reuse, comparison-only history, append-only archive, CLI, budgets, failure preservation and Skill guidance are each assigned to a task.
- Product-boundary coverage: reproducible UT failures remain analyzable; Agent/tool errors stay separate; Gantt absence removes only the schedule-result dimension.
- Simplification coverage: no domain mapping DSL, no Wafer event model, no sensitive-value rule system and no new database.
- Dependency direction: contracts stay implementation-free; normalizer and evidence engine depend only on contracts; validator additionally depends on the stable method-path SPI; Core composes implementations; CLI depends on Core.
- Placeholder scan requirement: the implementation must not contain `TODO`, `TBD`, ellipses as missing code, fake return values or skipped non-conditional tests.
- Type consistency: all public identities use existing opaque ID records; all artifact paths are portable Case-relative strings; all optionals are explicit; lists/sets are immutable and bounded.
