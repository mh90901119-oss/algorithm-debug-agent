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
            throw new IllegalArgumentException("Collection 后处理依赖不能为空");
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
        var evidenceRunId = baseline.referenceRunId().orElseThrow(() ->
                new CaseRunException("EVIDENCE_REFERENCE_RUN_MISSING",
                        "Collection has no completed uninstrumented reference run"));
        EvidenceBuildRequest request = request(
                evidenceId, collection.caseId(), collection.contextId(), collection.analysisId(),
                evidenceRunId, collection.collectionId(), EvidenceDimension.METHOD_PATH, budget);
        archive.createEvidenceRequest(request);
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, collection.caseId());
        Path rawPath = layout.collectionRoot(collection.collectionId()).resolve("raw/codepath.jsonl");
        ArtifactReference raw = describe(
                collection.caseId(), rawPath, collection.collectionId().value() + "-raw",
                "CODEPATH_RAW_TRACE", "application/x-ndjson");
        Instant now = clock.instant();
        NormalizationResult<MethodPathSummary> normalized = new MethodPathNormalizer().normalize(
                new CodePathNormalizationInput(
                        collection, plan, raw, rawPath, evidenceId, budget,
                        collectorManifest.completion() == CollectionCompletion.TRUNCATED, now));
        if (normalized.summary().isEmpty()) {
            archive.createNormalizationManifest(normalizationManifest(
                    evidenceId, collection, "CODEPATH", "method-path-normalizer", raw,
                    Optional.empty(), budget, normalized, now));
            throw new CaseRunException(
                    normalized.failureCode().orElse("CODEPATH_NORMALIZATION_FAILED"),
                    "CodePath Raw Trace 规范化失败");
        }
        MethodPathSummary summary = normalized.summary().orElseThrow();
        Path summaryPath = archive.createMethodPathSummary(summary);
        ArtifactReference summaryReference = describe(
                collection.caseId(), summaryPath, evidenceId.value() + "-method-path-summary",
                "METHOD_PATH_SUMMARY", "application/json");
        NormalizationManifest normalization = normalizationManifest(
                evidenceId, collection, "CODEPATH", "method-path-normalizer", raw,
                Optional.of(summaryReference), budget, normalized, now);
        Path normalizationPath = archive.createNormalizationManifest(normalization);
        CollectionValidation validation = validator.validateMethodPath(new MethodPathValidationInput(
                collection, plan, collectorManifest, normalization, summary, baseline,
                raw, rawPath, summaryReference, summaryPath, clock.instant()));
        return complete(request, validation, summaryReference, normalizationPath);
    }

    private CollectionPostProcessingResult doProcessJdwp(
            JdwpCollectionRecord collection,
            JdwpCollectionPlan plan,
            JdwpCollectionManifest collectorManifest,
            CollectionBaselineCheck baseline) {
        NormalizationBudget budget = budget(
                plan.budget().maxBytes(), plan.budget().maxEvents(),
                plan.budget().maxEvents());
        EvidenceId evidenceId = ids.newEvidenceId();
        var evidenceRunId = baseline.referenceRunId().orElseThrow(() ->
                new CaseRunException("EVIDENCE_REFERENCE_RUN_MISSING",
                        "Collection has no completed uninstrumented reference run"));
        EvidenceBuildRequest request = request(
                evidenceId, collection.caseId(), collection.contextId(), collection.analysisId(),
                evidenceRunId, collection.collectionId(), EvidenceDimension.RUNTIME_STATE, budget);
        archive.createEvidenceRequest(request);
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
                    "JDWP Raw Trace 规范化失败");
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
        return complete(request, validation, summaryReference, normalizationPath);
    }

    private CollectionPostProcessingResult complete(
            EvidenceBuildRequest request,
            CollectionValidation validation,
            ArtifactReference summaryReference,
            Path normalizationPath) {
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, request.caseId());
        Path validationPath = archive.createCollectionValidation(validation);
        ArtifactReference validationReference = describe(
                request.caseId(), validationPath, request.evidenceId().value() + "-validation",
                "COLLECTION_VALIDATION", "application/json");
        var runOutcome = archive.findRunOutcome(request.caseId(), request.runId()).orElseThrow(() ->
                new CaseRunException("EVIDENCE_REFERENCE_RUN_INCOMPLETE",
                        "Evidence 引用的无采集 Run 尚未完成"));
        ArtifactReference outcomeReference = describe(
                request.caseId(), layout.runOutcome(request.runId()),
                request.runId().value() + "-outcome", "RUN_OUTCOME_SUMMARY", "application/json");
        var context = archive.requireContext(request.caseId(), request.contextId());
        ArtifactReference contextReference = describe(
                request.caseId(), layout.contextDocument(request.contextId()),
                request.contextId().value() + "-context", "CONTEXT_RECORD", "application/json");
        Optional<RunResultFingerprint> fingerprint = archive.findReproduction(
                        request.caseId(), request.contextId())
                .filter(value -> value.runId().equals(request.runId()));
        Optional<ArtifactReference> fingerprintReference = fingerprint.map(value -> describe(
                request.caseId(), layout.runResultFingerprint(value.runId()),
                value.runId().value() + "-fingerprint", "RUN_RESULT_FINGERPRINT",
                "application/json"));
        var sources = new EvidenceBuildSources(
                runOutcome, outcomeReference, context, contextReference,
                fingerprint, fingerprintReference,
                List.of(new ValidatedCollectionSource(validation, validationReference)));
        var bundle = new EvidenceBundleBuilder().build(request, sources);
        Path bundlePath = archive.createEvidenceBundle(bundle);
        var sufficiency = new EvidenceSufficiencyEvaluator().evaluate(request, bundle);
        Path sufficiencyPath = archive.createSufficiencyEvaluation(sufficiency);

        ArrayList<ArtifactReference> result = new ArrayList<>();
        result.add(describe(request.caseId(), layout.evidenceBuildRequest(request.evidenceId()),
                request.evidenceId().value() + "-request", "EVIDENCE_BUILD_REQUEST",
                "application/json"));
        result.add(summaryReference);
        result.add(describe(request.caseId(), normalizationPath,
                request.evidenceId().value() + "-normalization", "NORMALIZATION_MANIFEST",
                "application/json"));
        result.add(validationReference);
        result.add(describe(request.caseId(), bundlePath,
                request.evidenceId().value() + "-bundle", "EVIDENCE_BUNDLE", "application/json"));
        result.add(describe(request.caseId(), sufficiencyPath,
                request.evidenceId().value() + "-sufficiency", "SUFFICIENCY_EVALUATION",
                "application/json"));
        return new CollectionPostProcessingResult(
                validation.status() == EvidenceValidationStatus.VALID, result);
    }

    private EvidenceBuildRequest request(
            EvidenceId evidenceId,
            org.example.algorithmdebug.contracts.CaseId caseId,
            org.example.algorithmdebug.contracts.ContextId contextId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId,
            org.example.algorithmdebug.contracts.RunId runId,
            org.example.algorithmdebug.contracts.CollectionId collectionId,
            EvidenceDimension dynamicDimension,
            NormalizationBudget budget) {
        return new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, evidenceId, caseId, contextId, analysisId,
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
                evidenceId, collection.caseId(), collection.contextId(), collection.analysisId(),
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
                evidenceId, collection.caseId(), collection.contextId(), collection.analysisId(),
                collection.runId(), collection.planId(), collection.collectionId(), collectorType,
                normalizerName, raw, summary, budget, result, createdAt);
    }

    private static NormalizationManifest normalizationManifest(
            EvidenceId evidenceId,
            org.example.algorithmdebug.contracts.CaseId caseId,
            org.example.algorithmdebug.contracts.ContextId contextId,
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
                SchemaVersions.NORMALIZATION_MANIFEST, evidenceId, caseId, contextId, analysisId,
                runId, planId, collectionId, collectorType, normalizerName, NORMALIZER_VERSION,
                result.status(), raw, summary, budget, result.inputRecordCount(),
                result.emittedFactCount(), result.truncationReasons(), result.failureCode(),
                result.failureDetail(), createdAt);
    }

    private CollectionPostProcessingResult failed(
            org.example.algorithmdebug.contracts.CaseId caseId,
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
            org.example.algorithmdebug.contracts.CaseId caseId,
            Path path,
            String id,
            String type,
            String mediaType) {
        if (!Files.isRegularFile(path)) {
            throw new CaseRunException("COLLECTION_POST_PROCESSING_ARTIFACT_MISSING",
                    "Collection 后处理产物不存在");
        }
        return artifacts.describe(caseId, id, type, mediaType, path);
    }
}
