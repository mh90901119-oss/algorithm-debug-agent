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
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceDimension;
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
import org.example.algorithmdebug.evidence.EvidenceSufficiencyEvaluator;
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
        try {
            return doProcessCodePath(collection, plan, manifest, baseline);
        } catch (RuntimeException failure) {
            return failed(collection.caseId(), collection.collectionId(), failure);
        }
    }

    CollectionPostProcessingResult processJdwp(
            JdwpCollectionRecord collection,
            JdwpCollectionPlan plan,
            JdwpCollectionManifest manifest,
            CollectionBaselineCheck baseline) {
        try {
            return doProcessJdwp(collection, plan, manifest, baseline);
        } catch (RuntimeException failure) {
            return failed(collection.caseId(), collection.collectionId(), failure);
        }
    }

    private CollectionPostProcessingResult doProcessCodePath(
            MethodPathCollectionRecord collection,
            CodePathCollectionPlan plan,
            MethodPathManifest collectorManifest,
            CollectionBaselineCheck baseline) {
        NormalizationBudget budget = budget(
                plan.budget().maxBytes(), plan.budget().maxEvents(),
                NormalizationBudget.defaults().maxHits());
        EvidenceId evidenceId = ids.newEvidenceId();
        Optional<EvidenceBuildRequest> request = baseline.referenceRunId().map(runId -> request(
                evidenceId, collection.caseId(), collection.analysisId(), runId,
                collection.collectionId(), EvidenceDimension.METHOD_PATH, budget));
        request.ifPresent(archive::createEvidenceRequest);
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, collection.caseId());
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
            archive.createNormalizationManifest(normalizationManifest(
                    evidenceId, collection, "CODEPATH",
                    plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                            ? "aggregate-method-path-normalizer" : "method-path-normalizer", raw,
                    Optional.empty(), budget, normalized, now));
            throw new CaseRunException(
                    normalized.failureCode().orElse("CODEPATH_NORMALIZATION_FAILED"),
                    "CodePath Raw Trace normalization failed");
        }
        MethodPathSummary summary = normalized.summary().orElseThrow();
        ArrayList<ArtifactReference> queryArtifacts = new ArrayList<>();
        if (plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.TRACE
                && Files.exists(invocationPath)) {
            ArtifactReference invocations = describe(
                    collection.caseId(), invocationPath,
                    collection.collectionId().value() + "-codepath-invocations",
                    "CODEPATH_INVOCATIONS", "application/x-ndjson");
            queryArtifacts.add(invocations);
        }
        Path summaryPath = archive.createMethodPathSummary(summary);
        ArtifactReference summaryReference = describe(
                collection.caseId(), summaryPath, evidenceId.value() + "-method-path-summary",
                "METHOD_PATH_SUMMARY", "application/json");
        NormalizationManifest normalization = normalizationManifest(
                evidenceId, collection, "CODEPATH",
                plan.captureMode() == org.example.algorithmdebug.contracts.CodePathCaptureMode.AGGREGATE
                        ? "aggregate-method-path-normalizer" : "method-path-normalizer", raw,
                Optional.of(summaryReference), budget, normalized, now);
        Path normalizationPath = archive.createNormalizationManifest(normalization);
        CollectionValidation validation = validator.validateMethodPath(new MethodPathValidationInput(
                collection, plan, collectorManifest, normalization, summary, baseline,
                raw, rawPath, summaryReference, summaryPath, clock.instant()));
        return complete(collection.caseId(), evidenceId, request, validation,
                summaryReference, normalizationPath, queryArtifacts);
    }

    private CollectionPostProcessingResult doProcessJdwp(
            JdwpCollectionRecord collection,
            JdwpCollectionPlan plan,
            JdwpCollectionManifest collectorManifest,
            CollectionBaselineCheck baseline) {
        NormalizationBudget budget = budget(
                plan.budget().maxBytes(), plan.budget().maxEvents() + 2L,
                plan.budget().maxEvents());
        EvidenceId evidenceId = ids.newEvidenceId();
        Optional<EvidenceBuildRequest> request = baseline.referenceRunId().map(runId -> request(
                evidenceId, collection.caseId(), collection.analysisId(), runId,
                collection.collectionId(), EvidenceDimension.RUNTIME_STATE, budget));
        request.ifPresent(archive::createEvidenceRequest);
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, collection.caseId());
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
            archive.createNormalizationManifest(normalizationManifest(
                    evidenceId, collection, "JDWP", "jdwp-snapshot-normalizer", raw,
                    Optional.empty(), budget, normalized, now));
            throw new CaseRunException(
                    normalized.failureCode().orElse("JDWP_NORMALIZATION_FAILED"),
                    "JDWP Raw Trace normalization failed");
        }
        JdwpSnapshotSummary summary = normalized.summary().orElseThrow();
        Path summaryPath = archive.createJdwpSnapshotSummary(summary);
        ArtifactReference summaryReference = describe(
                collection.caseId(), summaryPath, evidenceId.value() + "-jdwp-summary",
                "JDWP_SNAPSHOT_SUMMARY", "application/json");
        NormalizationManifest normalization = normalizationManifest(
                evidenceId, collection, "JDWP", "jdwp-snapshot-normalizer", raw,
                Optional.of(summaryReference), budget, normalized, now);
        Path normalizationPath = archive.createNormalizationManifest(normalization);
        CollectionValidation validation = validator.validateJdwp(new JdwpValidationInput(
                collection, plan, collectorManifest, normalization, summary, baseline,
                raw, rawPath, summaryReference, summaryPath, clock.instant()));
        return complete(collection.caseId(), evidenceId, request, validation,
                summaryReference, normalizationPath, List.of());
    }

    private CollectionPostProcessingResult complete(
            CaseId caseId,
            EvidenceId evidenceId,
            Optional<EvidenceBuildRequest> request,
            CollectionValidation validation,
            ArtifactReference summaryReference,
            Path normalizationPath,
            List<ArtifactReference> queryArtifacts) {
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, caseId);
        Path validationPath = archive.createCollectionValidation(validation);
        ArtifactReference validationReference = describe(
                caseId, validationPath, evidenceId.value() + "-validation",
                "COLLECTION_VALIDATION", "application/json");
        ArrayList<ArtifactReference> result = new ArrayList<>();
        result.addAll(queryArtifacts);
        result.add(summaryReference);
        result.add(describe(caseId, normalizationPath,
                evidenceId.value() + "-normalization", "NORMALIZATION_MANIFEST",
                "application/json"));
        result.add(validationReference);
        if (request.isEmpty()) {
            return new CollectionPostProcessingResult(
                    validation.status() != EvidenceValidationStatus.INVALID, result);
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
        var sufficiency = new EvidenceSufficiencyEvaluator().evaluate(buildRequest, bundle);
        Path sufficiencyPath = archive.createSufficiencyEvaluation(sufficiency);

        result.add(describe(caseId, layout.evidenceBuildRequest(evidenceId),
                evidenceId.value() + "-request", "EVIDENCE_BUILD_REQUEST",
                "application/json"));
        result.add(describe(caseId, bundlePath,
                evidenceId.value() + "-bundle", "EVIDENCE_BUNDLE", "application/json"));
        result.add(describe(caseId, sufficiencyPath,
                evidenceId.value() + "-sufficiency", "SUFFICIENCY_EVALUATION",
                "application/json"));
        return new CollectionPostProcessingResult(
                validation.status() != EvidenceValidationStatus.INVALID, result);
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

    private CollectionPostProcessingResult failed(
            CaseId caseId,
            org.example.algorithmdebug.contracts.CollectionId collectionId,
            RuntimeException failure) {
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
        return new CollectionPostProcessingResult(false, List.of(reference));
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
