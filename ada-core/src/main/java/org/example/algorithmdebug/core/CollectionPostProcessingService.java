package org.example.algorithmdebug.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveLayout;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseArtifactAccess;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.JdwpCollectionManifest;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.evidence.EvidenceBuildSources;
import org.example.algorithmdebug.evidence.EvidenceBundleBuilder;
import org.example.algorithmdebug.evidence.EvidenceEligibilityEvaluator;
import org.example.algorithmdebug.evidence.EvidenceSufficiencyEvaluator;
import org.example.algorithmdebug.evidence.EvidenceView;
import org.example.algorithmdebug.evidence.ValidatedCollectionSource;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathManifest;
import org.example.algorithmdebug.normalizer.CodePathNormalizationInput;
import org.example.algorithmdebug.normalizer.JdwpNormalizationInput;
import org.example.algorithmdebug.normalizer.JdwpSnapshotNormalizer;
import org.example.algorithmdebug.normalizer.MethodPathNormalizer;
import org.example.algorithmdebug.normalizer.NormalizationResult;
import org.example.algorithmdebug.validator.CollectionEvidenceValidator;
import org.example.algorithmdebug.validator.JdwpValidationInput;
import org.example.algorithmdebug.validator.MethodPathValidationInput;

/** 将已归档 Collection 确定性派生为摘要、校验、Evidence Bundle 与充分性结论。 */
final class CollectionPostProcessingService {
    private static final String NORMALIZER_VERSION = "1.0";
    private static final long MAX_EVIDENCE_BUNDLE_BYTES = 1024L * 1024;

    private final Path casesRoot;
    private final CaseArchiveRepository archive;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final OpaqueIdGenerator ids;
    private final Clock clock;
    private final CaseArtifactAccess artifacts;
    private final CollectionEvidenceValidator validator = new CollectionEvidenceValidator();

    CollectionPostProcessingService(
            Path casesRoot,
            CaseArchiveRepository archive,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            OpaqueIdGenerator ids,
            Clock clock) {
        if (casesRoot == null || archive == null || mapper == null || writer == null
                || ids == null || clock == null) {
            throw new IllegalArgumentException("Collection post-processing dependencies must not be null");
        }
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        this.archive = archive;
        this.mapper = mapper;
        this.writer = writer;
        this.ids = ids;
        this.clock = clock;
        this.artifacts = new CaseArtifactAccess(this.casesRoot);
    }

    CollectionPostProcessingResult processCodePath(
            MethodPathCollectionRecord collection,
            CodePathCollectionPlan plan,
            MethodPathManifest manifest,
            CollectionBaselineCheck baseline) {
        ArrayList<ArtifactReference> produced = new ArrayList<>();
        try {
            return doProcessCodePath(collection, plan, manifest, baseline, produced);
        } catch (RuntimeException failure) {
            return failed(
                    collection.caseId(), collection.collectionId(), failure, produced,
                    codePathEligibility(false, manifest, baseline));
        }
    }

    CollectionPostProcessingResult processJdwp(
            JdwpCollectionRecord collection,
            JdwpCollectionPlan plan,
            JdwpCollectionManifest manifest,
            CollectionBaselineCheck baseline) {
        ArrayList<ArtifactReference> produced = new ArrayList<>();
        try {
            return doProcessJdwp(collection, plan, manifest, baseline, produced);
        } catch (RuntimeException failure) {
            return failed(
                    collection.caseId(), collection.collectionId(), failure, produced,
                    jdwpEligibility(false, manifest, baseline));
        }
    }

    private CollectionPostProcessingResult doProcessCodePath(
            MethodPathCollectionRecord collection,
            CodePathCollectionPlan plan,
            MethodPathManifest collectorManifest,
            CollectionBaselineCheck baseline,
            List<ArtifactReference> produced) {
        NormalizationBudget budget = budget(
                plan.budget().maxBytes(), plan.budget().maxEvents(),
                NormalizationBudget.defaults().maxHits());
        EvidenceId evidenceId = ids.newEvidenceId();
        Optional<EvidenceBuildRequest> request = baseline.referenceRunId().map(runId -> request(
                evidenceId, collection.caseId(), collection.analysisId(), runId,
                collection.collectionId(), EvidenceDimension.METHOD_PATH, budget));
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, collection.caseId());
        request.ifPresent(value -> {
            Path requestPath = archive.createEvidenceRequest(value);
            addUnique(produced, describe(
                    collection.caseId(), requestPath, evidenceId.value() + "-request",
                    "EVIDENCE_BUILD_REQUEST", "application/json"));
        });
        Path rawPath = layout.collectionRoot(collection.collectionId())
                .resolve(collectorManifest.rawTrace()).normalize();
        if (!rawPath.startsWith(layout.collectionRoot(collection.collectionId()))) {
            throw new CaseRunException(
                    "CODEPATH_RAW_PATH_INVALID", "CodePath manifest Raw path escapes the Collection");
        }
        Path invocationPath = layout.collectionRoot(collection.collectionId())
                .resolve("derived/codepath-invocations.jsonl");
        ArtifactReference raw = describe(
                collection.caseId(), rawPath, collection.collectionId().value() + "-raw",
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                        ? "CODEPATH_RAW_AGGREGATE" : "CODEPATH_RAW_TRACE",
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                        ? "application/json" : "application/x-ndjson");
        Instant now = clock.instant();
        CodePathNormalizationInput normalizationInput = new CodePathNormalizationInput(
                collection, plan, raw, rawPath, invocationPath, evidenceId, budget,
                collectorManifest.completion() == CollectionCompletion.TRUNCATED, now);
        NormalizationResult<MethodPathSummary> normalized =
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                ? new org.example.algorithmdebug.normalizer.AggregateMethodPathNormalizer()
                        .normalize(normalizationInput)
                : new MethodPathNormalizer().normalize(normalizationInput);
        if (normalized.summary().isEmpty()) {
            Path failedNormalization = archive.createNormalizationManifest(normalizationManifest(
                    evidenceId, collection, "CODEPATH",
                    plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                            ? "aggregate-method-path-normalizer" : "method-path-normalizer", raw,
                    Optional.empty(), budget, normalized, now));
            addUnique(produced, describe(
                    collection.caseId(), failedNormalization,
                    evidenceId.value() + "-normalization", "NORMALIZATION_MANIFEST",
                    "application/json"));
            throw new CaseRunException(
                    normalized.failureCode().orElse("CODEPATH_NORMALIZATION_FAILED"),
                    "CodePath Raw Trace normalization failed");
        }
        MethodPathSummary summary = normalized.summary().orElseThrow();
        if (plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.TRACE
                && Files.exists(invocationPath)) {
            ArtifactReference invocations = describe(
                    collection.caseId(), invocationPath,
                    collection.collectionId().value() + "-codepath-invocations",
                    "CODEPATH_INVOCATIONS", "application/x-ndjson");
            addUnique(produced, invocations);
        }
        Path summaryPath = archive.createMethodPathSummary(summary);
        ArtifactReference summaryReference = describe(
                collection.caseId(), summaryPath, evidenceId.value() + "-method-path-summary",
                "METHOD_PATH_SUMMARY", "application/json");
        addUnique(produced, summaryReference);
        NormalizationManifest normalization = normalizationManifest(
                evidenceId, collection, "CODEPATH",
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                        ? "aggregate-method-path-normalizer" : "method-path-normalizer", raw,
                Optional.of(summaryReference), budget, normalized, now);
        Path normalizationPath = archive.createNormalizationManifest(normalization);
        addUnique(produced, describe(
                collection.caseId(), normalizationPath,
                evidenceId.value() + "-normalization", "NORMALIZATION_MANIFEST",
                "application/json"));
        CollectionValidation validation = validator.validateMethodPath(new MethodPathValidationInput(
                collection, plan, collectorManifest, normalization, summary, baseline,
                raw, rawPath, summaryReference, summaryPath, clock.instant()));
        EvidenceEligibility eligibility = codePathEligibility(
                validation.status() != EvidenceValidationStatus.INVALID,
                collectorManifest, baseline);
        CollectionPostProcessingResult completed = complete(
                collection.caseId(), evidenceId, request, validation, eligibility, produced);
        evaluateCodePath(plan, summary,
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.TRACE
                        && Files.isRegularFile(invocationPath)
                        ? Optional.of(invocationPath) : Optional.empty(),
                eligibility, observationComparison(eligibility, baseline.outcome()));
        return new CollectionPostProcessingResult(
                completed.artifactReadable(), completed.artifacts(), eligibility);
    }

    private CollectionPostProcessingResult doProcessJdwp(
            JdwpCollectionRecord collection,
            JdwpCollectionPlan plan,
            JdwpCollectionManifest collectorManifest,
            CollectionBaselineCheck baseline,
            List<ArtifactReference> produced) {
        NormalizationBudget budget = budget(
                plan.budget().maxBytes(), plan.budget().maxEvents() + 2L,
                plan.budget().maxEvents());
        EvidenceId evidenceId = ids.newEvidenceId();
        Optional<EvidenceBuildRequest> request = baseline.referenceRunId().map(runId -> request(
                evidenceId, collection.caseId(), collection.analysisId(), runId,
                collection.collectionId(), EvidenceDimension.RUNTIME_STATE, budget));
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, collection.caseId());
        request.ifPresent(value -> {
            Path requestPath = archive.createEvidenceRequest(value);
            addUnique(produced, describe(
                    collection.caseId(), requestPath, evidenceId.value() + "-request",
                    "EVIDENCE_BUILD_REQUEST", "application/json"));
        });
        Path rawPath = layout.collectionRoot(collection.collectionId()).resolve("raw/jdwp.jsonl");
        ArtifactReference raw = describe(
                collection.caseId(), rawPath, collection.collectionId().value() + "-raw",
                "JDWP_RAW_TRACE", "application/x-ndjson");
        Instant now = clock.instant();
        NormalizationResult<JdwpSnapshotSummary> normalized = new JdwpSnapshotNormalizer().normalize(
                new JdwpNormalizationInput(
                        collection, plan, raw, rawPath, evidenceId, budget,
                        collectorManifest.completion() == JdwpCollectionCompletion.TRUNCATED, now));
        if (normalized.summary().isEmpty()) {
            Path failedNormalization = archive.createNormalizationManifest(normalizationManifest(
                    evidenceId, collection, "JDWP", "jdwp-snapshot-normalizer", raw,
                    Optional.empty(), budget, normalized, now));
            addUnique(produced, describe(
                    collection.caseId(), failedNormalization,
                    evidenceId.value() + "-normalization", "NORMALIZATION_MANIFEST",
                    "application/json"));
            throw new CaseRunException(
                    normalized.failureCode().orElse("JDWP_NORMALIZATION_FAILED"),
                    "JDWP Raw Trace normalization failed");
        }
        JdwpSnapshotSummary summary = normalized.summary().orElseThrow();
        Path summaryPath = archive.createJdwpSnapshotSummary(summary);
        ArtifactReference summaryReference = describe(
                collection.caseId(), summaryPath, evidenceId.value() + "-jdwp-summary",
                "JDWP_SNAPSHOT_SUMMARY", "application/json");
        addUnique(produced, summaryReference);
        NormalizationManifest normalization = normalizationManifest(
                evidenceId, collection, "JDWP", "jdwp-snapshot-normalizer", raw,
                Optional.of(summaryReference), budget, normalized, now);
        Path normalizationPath = archive.createNormalizationManifest(normalization);
        addUnique(produced, describe(
                collection.caseId(), normalizationPath,
                evidenceId.value() + "-normalization", "NORMALIZATION_MANIFEST",
                "application/json"));
        CollectionValidation validation = validator.validateJdwp(new JdwpValidationInput(
                collection, plan, collectorManifest, normalization, summary, baseline,
                raw, rawPath, summaryReference, summaryPath, clock.instant()));
        EvidenceEligibility eligibility = jdwpEligibility(
                validation.status() != EvidenceValidationStatus.INVALID,
                collectorManifest, baseline);
        CollectionPostProcessingResult completed = complete(
                collection.caseId(), evidenceId, request, validation, eligibility, produced);
        evaluateJdwp(
                plan, summary, eligibility,
                observationComparison(eligibility, baseline.outcome()));
        return new CollectionPostProcessingResult(
                completed.artifactReadable(), completed.artifacts(), eligibility);
    }

    private CollectionPostProcessingResult complete(
            CaseId caseId,
            EvidenceId evidenceId,
            Optional<EvidenceBuildRequest> request,
            CollectionValidation validation,
            EvidenceEligibility eligibility,
            List<ArtifactReference> result) {
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, caseId);
        Path validationPath = archive.createCollectionValidation(validation);
        ArtifactReference validationReference = describe(
                caseId, validationPath, evidenceId.value() + "-validation",
                "COLLECTION_VALIDATION", "application/json");
        addUnique(result, validationReference);
        if (request.isEmpty()) {
            return new CollectionPostProcessingResult(
                    validation.status() != EvidenceValidationStatus.INVALID, result, eligibility);
        }

        EvidenceBuildRequest buildRequest = request.orElseThrow();
        var runOutcome = archive.findRunOutcome(caseId, buildRequest.runId()).orElseThrow(() ->
                new CaseRunException("EVIDENCE_REFERENCE_RUN_INCOMPLETE",
                        "The uninstrumented Run referenced by Evidence is not completed"));
        ArtifactReference outcomeReference = describe(
                caseId, layout.runOutcome(buildRequest.runId()),
                buildRequest.runId().value() + "-outcome", "RUN_OUTCOME_SUMMARY", "application/json");
        Optional<RunResultFingerprint> fingerprint = archive.findLatestRunResultFingerprint(
                        caseId, buildRequest.analysisId())
                .filter(value -> value.runId().equals(buildRequest.runId()));
        Optional<ArtifactReference> fingerprintReference = fingerprint.map(value -> describe(
                caseId, layout.runResultFingerprint(value.runId()),
                value.runId().value() + "-fingerprint", "RUN_RESULT_FINGERPRINT",
                "application/json"));
        var sources = new EvidenceBuildSources(
                runOutcome, outcomeReference,
                fingerprint, fingerprintReference,
                List.of(new ValidatedCollectionSource(validation, validationReference)));
        var bundle = new EvidenceBundleBuilder().build(buildRequest, sources);
        Path bundlePath = archive.createEvidenceBundle(bundle);
        var sufficiency = new EvidenceSufficiencyEvaluator().evaluate(
                buildRequest, bundle, eligibility, Set.of());
        Path sufficiencyPath = archive.createSufficiencyEvaluation(sufficiency);

        addUnique(result, describe(caseId, bundlePath,
                evidenceId.value() + "-bundle", "EVIDENCE_BUNDLE", "application/json"));
        addUnique(result, describe(caseId, sufficiencyPath,
                evidenceId.value() + "-sufficiency", "SUFFICIENCY_EVALUATION",
                "application/json"));
        return new CollectionPostProcessingResult(
                validation.status() != EvidenceValidationStatus.INVALID, result, eligibility);
    }

    private EvidenceBuildRequest request(
            EvidenceId evidenceId,
            CaseId caseId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId,
            org.example.algorithmdebug.contracts.RunId runId,
            org.example.algorithmdebug.contracts.CollectionId collectionId,
            EvidenceDimension dynamicDimension,
            NormalizationBudget budget) {
        return new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, evidenceId, caseId, analysisId,
                runId, List.of(collectionId), List.of(), Set.of(
                        EvidenceDimension.TARGET_OUTCOME,
                        EvidenceDimension.VALIDATION,
                        dynamicDimension), budget.maxSummaryBytes(),
                MAX_EVIDENCE_BUNDLE_BYTES, clock.instant());
    }

    private static NormalizationBudget budget(
            long maxRawBytes, long maxRecords, int maxHits) {
        NormalizationBudget defaults = NormalizationBudget.defaults();
        return new NormalizationBudget(
                maxRawBytes, defaults.maxRecordBytes(), maxRecords,
                defaults.maxMethods(), defaults.maxRelationships(), maxHits,
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), defaults.maxSummaryBytes());
    }

    private NormalizationManifest normalizationManifest(
            EvidenceId evidenceId,
            MethodPathCollectionRecord collection,
            String collectorType,
            String normalizerName,
            ArtifactReference raw,
            Optional<ArtifactReference> summary,
            NormalizationBudget budget,
            NormalizationResult<?> result,
            Instant createdAt) {
        return normalizationManifest(
                evidenceId, collection.caseId(), collection.analysisId(),
                collection.runId(), collection.planId(), collection.collectionId(), collectorType,
                normalizerName, raw, summary, budget, result, createdAt);
    }

    private NormalizationManifest normalizationManifest(
            EvidenceId evidenceId,
            JdwpCollectionRecord collection,
            String collectorType,
            String normalizerName,
            ArtifactReference raw,
            Optional<ArtifactReference> summary,
            NormalizationBudget budget,
            NormalizationResult<?> result,
            Instant createdAt) {
        return normalizationManifest(
                evidenceId, collection.caseId(), collection.analysisId(),
                collection.runId(), collection.planId(), collection.collectionId(), collectorType,
                normalizerName, raw, summary, budget, result, createdAt);
    }

    private static NormalizationManifest normalizationManifest(
            EvidenceId evidenceId,
            CaseId caseId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId,
            org.example.algorithmdebug.contracts.RunId runId,
            org.example.algorithmdebug.contracts.PlanId planId,
            org.example.algorithmdebug.contracts.CollectionId collectionId,
            String collectorType,
            String normalizerName,
            ArtifactReference raw,
            Optional<ArtifactReference> summary,
            NormalizationBudget budget,
            NormalizationResult<?> result,
            Instant createdAt) {
        return new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST, evidenceId, caseId, analysisId,
                runId, planId, collectionId, collectorType, normalizerName, NORMALIZER_VERSION,
                result.status(), raw, summary, budget, result.inputRecordCount(),
                result.emittedFactCount(), result.truncationReasons(), result.failureCode(),
                result.failureDetail(), createdAt);
    }

    private void evaluateCodePath(
            CodePathCollectionPlan plan,
            MethodPathSummary summary,
            Optional<Path> invocationPath,
            EvidenceEligibility eligibility,
            ComparisonOutcome comparisonOutcome) {
        var binding = plan.investigationBinding().orElseThrow(() ->
                new CaseRunException(
                        "INVESTIGATION_BINDING_MISSING",
                        "Current CodePath plan has no Investigation binding"));
        InvestigationApplicationService investigation = investigation();
        var predicates = boundPredicates(
                investigation.currentState(plan.caseId(), plan.analysisId()), binding);
        EvidenceView view = new CollectionEvidenceViewFactory().fromCodePath(
                plan, summary, invocationPath, eligibility, comparisonOutcome, predicates);
        investigation.recordCollectedEvidence(binding, view);
    }

    private void evaluateJdwp(
            JdwpCollectionPlan plan,
            JdwpSnapshotSummary summary,
            EvidenceEligibility eligibility,
            ComparisonOutcome comparisonOutcome) {
        var binding = plan.investigationBinding().orElseThrow(() ->
                new CaseRunException(
                        "INVESTIGATION_BINDING_MISSING",
                        "Current JDWP plan has no Investigation binding"));
        InvestigationApplicationService investigation = investigation();
        var predicates = boundPredicates(
                investigation.currentState(plan.caseId(), plan.analysisId()), binding);
        EvidenceView view = new CollectionEvidenceViewFactory().fromJdwp(
                plan, summary, eligibility, comparisonOutcome, predicates);
        investigation.recordCollectedEvidence(binding, view);
    }

    private InvestigationApplicationService investigation() {
        return new InvestigationApplicationService(casesRoot, mapper, writer, clock);
    }

    private static List<org.example.algorithmdebug.contracts.investigation.ObservationPredicate>
            boundPredicates(
                    org.example.algorithmdebug.contracts.investigation.InvestigationState state,
                    org.example.algorithmdebug.contracts.investigation.InvestigationBinding binding) {
        return binding.predicateIds().stream().map(id -> state.predicates().stream()
                .filter(value -> value.predicateId().equals(id))
                .findFirst()
                .orElseThrow(() -> new CaseRunException(
                        "INVESTIGATION_PREDICATE_MISSING",
                        "Bound Predicate is absent from the Investigation Ledger: "
                                + id.value())))
                .toList();
    }

    private static EvidenceEligibility codePathEligibility(
            boolean artifactReadable,
            MethodPathManifest manifest,
            CollectionBaselineCheck baseline) {
        boolean collectionComplete =
                (manifest.completion() == CollectionCompletion.SUCCESS
                        || manifest.completion() == CollectionCompletion.TARGET_FAILED)
                        && manifest.truncationReasons().isEmpty();
        return eligibility(
                artifactReadable, collectionComplete,
                manifest.completion() == CollectionCompletion.TARGET_FAILED,
                baseline.outcome(), manifest.capturedEventCount() > 0);
    }

    private static EvidenceEligibility jdwpEligibility(
            boolean artifactReadable,
            JdwpCollectionManifest manifest,
            CollectionBaselineCheck baseline) {
        boolean collectionComplete =
                (manifest.completion() == JdwpCollectionCompletion.SUCCESS
                        || manifest.completion() == JdwpCollectionCompletion.TARGET_FAILED)
                        && !manifest.truncated();
        long capturedHits = manifest.capturedHitCounts().values().stream()
                .mapToLong(Integer::longValue).sum();
        return eligibility(
                artifactReadable, collectionComplete,
                manifest.completion() == JdwpCollectionCompletion.TARGET_FAILED,
                baseline.outcome(), capturedHits > 0);
    }

    private static EvidenceEligibility eligibility(
            boolean artifactReadable,
            boolean collectionComplete,
            boolean targetFailed,
            ComparisonOutcome comparisonOutcome,
            boolean obligationSatisfied) {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        artifactReadable, collectionComplete, targetFailed,
                        comparisonOutcome, obligationSatisfied));
    }

    private static ComparisonOutcome observationComparison(
            EvidenceEligibility eligibility, ComparisonOutcome baselineOutcome) {
        return eligibility.baselineRequired()
                ? baselineOutcome : ComparisonOutcome.NOT_COMPARED;
    }

    private CollectionPostProcessingResult failed(
            CaseId caseId,
            org.example.algorithmdebug.contracts.CollectionId collectionId,
            RuntimeException failure,
            List<ArtifactReference> produced,
            EvidenceEligibility eligibility) {
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, caseId);
        Path document = layout.collectionRoot(collectionId)
                .resolve("validation/post-processing-failure.json");
        AgentFailureDiagnostic diagnostic = new AgentFailureDiagnostic(
                "COLLECTION_POST_PROCESSING_FAILED",
                "Collection post-processing failed: " + failureCode(failure),
                failure.getClass().getName());
        writer.writeNew(document, mapper.writeJson(diagnostic));
        ArtifactReference reference = describe(
                caseId, document, collectionId.value() + "-post-processing-failure",
                "POST_PROCESSING_FAILURE", "application/json");
        addUnique(produced, reference);
        return new CollectionPostProcessingResult(false, produced, eligibility);
    }

    private static void addUnique(
            List<ArtifactReference> artifacts, ArtifactReference candidate) {
        if (artifacts.stream().noneMatch(value ->
                value.artifactId().equals(candidate.artifactId()))) {
            artifacts.add(candidate);
        }
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof CaseRunException caseFailure) {
            return caseFailure.code();
        }
        if (failure instanceof WorkspaceException workspaceFailure) {
            return workspaceFailure.code();
        }
        return "UNEXPECTED_RUNTIME_FAILURE";
    }

    private ArtifactReference describe(
            CaseId caseId,
            Path path,
            String id,
            String type,
            String mediaType) {
        if (!Files.isRegularFile(path)) {
            throw new CaseRunException("COLLECTION_POST_PROCESSING_ARTIFACT_MISSING",
                    "Collection post-processing artifact does not exist");
        }
        return artifacts.describe(caseId, id, type, mediaType, path);
    }
}
