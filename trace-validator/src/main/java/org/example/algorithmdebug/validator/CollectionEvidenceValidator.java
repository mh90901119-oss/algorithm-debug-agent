package org.example.algorithmdebug.validator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.example.algorithmdebug.contracts.ValidationFinding;
import org.example.algorithmdebug.methodpath.CollectionCompletion;

/** 对动态采集产物执行身份、完整性、来源、基线与可追溯性校验。 */
public final class CollectionEvidenceValidator {

    private static final int MAX_FINDINGS = 128;
    private final ArtifactIntegrityVerifier integrityVerifier;
    private final ProvenanceVerifier provenanceVerifier;

    public CollectionEvidenceValidator() {
        this(new ArtifactIntegrityVerifier(), new ProvenanceVerifier());
    }

    CollectionEvidenceValidator(
            ArtifactIntegrityVerifier integrityVerifier,
            ProvenanceVerifier provenanceVerifier) {
        this.integrityVerifier = integrityVerifier;
        this.provenanceVerifier = provenanceVerifier;
    }

    /** 校验一次 CodePath 采集；只有全部确定性门禁通过时才覆盖 METHOD_PATH。 */
    public CollectionValidation validateMethodPath(MethodPathValidationInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        ArrayList<ValidationFinding> findings = new ArrayList<>();
        validateIdentity(input, findings);
        add(findings, integrityVerifier.verify(input.rawReference(), input.rawPath()));
        add(findings, integrityVerifier.verify(input.summaryReference(), input.summaryPath()));
        validateCompletion(input.collectorManifest().completion(), findings);
        validateNormalization(input.normalizationManifest(), input.summary().truncated(),
                false, input.summaryReference(), findings);
        validateBaseline(input.baselineCheck(), input.summaryReference(), findings);
        if (input.summary().methods().isEmpty()) {
            add(findings, finding("NO_USEFUL_FACTS", EvidenceValidationStatus.INCONCLUSIVE,
                    "The method-path summary contains no analyzable method facts", input.summaryReference()));
        }
        validateScope(input, findings);
        add(findings, provenanceVerifier.verify(
                provenances(input.summary()), input.rawReference(), input.rawPath()));

        EvidenceValidationStatus status = aggregate(findings);
        Set<EvidenceDimension> dimensions = status == EvidenceValidationStatus.VALID
                ? Set.of(EvidenceDimension.VALIDATION, EvidenceDimension.METHOD_PATH)
                : Set.of();
        return new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION,
                input.summary().evidenceId(), input.collection().caseId(),
                input.collection().analysisId(),
                input.collection().runId(), input.collection().planId(),
                input.collection().collectionId(), "CODEPATH", status,
                List.copyOf(findings), dimensions, Optional.of(input.summaryReference()),
                input.validatedAt());
    }

    private static void validateScope(
            MethodPathValidationInput input, List<ValidationFinding> findings) {
        if (input.plan().scopeMethodKey().isEmpty()) {
            return;
        }
        if (input.summary().scope().isEmpty()) {
            add(findings, finding("SCOPE_SUMMARY_MISSING", EvidenceValidationStatus.INCONCLUSIVE,
                    "The configured CodePath scope has no derived summary",
                    input.summaryReference()));
            return;
        }
        MethodPathSummary.ScopeSummary scope = input.summary().scope().orElseThrow();
        if (!input.plan().scopeMethodKey().orElseThrow().equals(scope.methodKey())) {
            add(findings, finding("SCOPE_IDENTITY_MISMATCH", EvidenceValidationStatus.INVALID,
                    "The derived CodePath scope does not match the archived plan",
                    input.summaryReference()));
        } else if (scope.incompleteInvocationCount() > 0) {
            add(findings, finding("SCOPE_INVOCATION_INCOMPLETE",
                    EvidenceValidationStatus.INCONCLUSIVE,
                    "One or more CodePath scope invocations have no matching exit",
                    input.summaryReference()));
        }
    }

    /** 校验一次 JDWP 采集；只有全部确定性门禁通过时才覆盖 RUNTIME_STATE。 */
    public CollectionValidation validateJdwp(JdwpValidationInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        ArrayList<ValidationFinding> findings = new ArrayList<>();
        validateJdwpIdentity(input, findings);
        add(findings, integrityVerifier.verify(input.rawReference(), input.rawPath()));
        add(findings, integrityVerifier.verify(input.summaryReference(), input.summaryPath()));
        validateJdwpCompletion(input.collectorManifest().completion(), findings);
        validateNormalization(input.normalizationManifest(), input.summary().truncated(),
                !input.summary().hits().isEmpty(), input.summaryReference(), findings);
        validateBaseline(input.baselineCheck(), input.summaryReference(), findings);
        if (input.summary().hits().isEmpty()) {
            add(findings, finding("NO_USEFUL_FACTS", EvidenceValidationStatus.INCONCLUSIVE,
                    "The JDWP summary contains no analyzable hit facts", input.summaryReference()));
        }
        add(findings, provenanceVerifier.verify(
                provenances(input.summary()), input.rawReference(), input.rawPath()));

        EvidenceValidationStatus status = aggregate(findings);
        Set<EvidenceDimension> dimensions = status == EvidenceValidationStatus.VALID
                ? Set.of(EvidenceDimension.VALIDATION, EvidenceDimension.RUNTIME_STATE)
                : Set.of();
        return new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION,
                input.summary().evidenceId(), input.collection().caseId(),
                input.collection().analysisId(),
                input.collection().runId(), input.collection().planId(),
                input.collection().collectionId(), "JDWP", status,
                List.copyOf(findings), dimensions, Optional.of(input.summaryReference()),
                input.validatedAt());
    }

    private static void validateJdwpIdentity(
            JdwpValidationInput input, List<ValidationFinding> findings) {
        var collection = input.collection();
        var manifest = input.collectorManifest();
        var normalization = input.normalizationManifest();
        var summary = input.summary();
        boolean mismatch = !collection.caseId().equals(input.plan().caseId())
                || !collection.analysisId().equals(input.plan().analysisId())
                || !collection.planId().equals(input.plan().planId())
                || !collection.targetTest().equals(input.plan().targetTest())
                || !collection.caseId().equals(manifest.caseId())
                || !collection.analysisId().equals(manifest.analysisId())
                || !collection.runId().equals(manifest.runId())
                || !collection.planId().equals(manifest.planId())
                || !collection.collectionId().equals(manifest.collectionId())
                || !collection.caseId().equals(normalization.caseId())
                || !collection.analysisId().equals(normalization.analysisId())
                || !collection.runId().equals(normalization.runId())
                || !collection.planId().equals(normalization.planId())
                || !collection.collectionId().equals(normalization.collectionId())
                || !"JDWP".equals(normalization.collectorType())
                || !collection.caseId().equals(summary.caseId())
                || !collection.analysisId().equals(summary.analysisId())
                || !collection.runId().equals(summary.runId())
                || !collection.planId().equals(summary.planId())
                || !collection.collectionId().equals(summary.collectionId())
                || !collection.collectionId().equals(input.baselineCheck().collectionId())
                || !collection.runId().equals(input.baselineCheck().runId())
                || !collection.caseId().equals(input.baselineCheck().caseId())
                || !collection.analysisId().equals(input.baselineCheck().analysisId())
                || !summary.rawTrace().equals(input.rawReference())
                || !normalization.rawArtifact().equals(input.rawReference())
                || normalization.summaryArtifact().isEmpty()
                || !normalization.summaryArtifact().orElseThrow().equals(input.summaryReference());
        if (mismatch) add(findings, finding(
                "COLLECTION_IDENTITY_MISMATCH", EvidenceValidationStatus.INVALID,
                "Collection, Plan, Manifest, summaryor Baseline identity does not match",
                input.summaryReference()));
    }

    private static void validateJdwpCompletion(
            JdwpCollectionCompletion completion, List<ValidationFinding> findings) {
        switch (completion) {
            case SUCCESS, TARGET_FAILED -> { }
            case TRUNCATED, TIMED_OUT -> add(findings, finding(
                    "COLLECTION_INCOMPLETE", EvidenceValidationStatus.INCONCLUSIVE,
                    "Dynamic collection did not complete", null));
            case TOOL_FAILED, AGENT_FAILED -> add(findings, finding(
                    "COLLECTION_FAILED", EvidenceValidationStatus.INVALID,
                    "Collector or Agent execution failed", null));
        }
    }

    private static List<TraceProvenance> provenances(JdwpSnapshotSummary summary) {
        LinkedHashSet<TraceProvenance> result = new LinkedHashSet<>();
        summary.hits().forEach(hit -> {
            result.add(hit.provenance());
            hit.values().forEach(value -> result.add(value.provenance()));
        });
        summary.limits().forEach(limit -> result.add(limit.provenance()));
        return List.copyOf(result);
    }

    private static void validateIdentity(
            MethodPathValidationInput input, List<ValidationFinding> findings) {
        var collection = input.collection();
        boolean mismatch = !collection.caseId().equals(input.plan().caseId())
                || !collection.analysisId().equals(input.plan().analysisId())
                || !collection.planId().equals(input.plan().planId())
                || !collection.targetTest().equals(input.plan().targetTest())
                || !collection.caseId().equals(input.collectorManifest().caseId())
                || !collection.analysisId().equals(input.collectorManifest().analysisId())
                || !collection.runId().equals(input.collectorManifest().runId())
                || !collection.planId().equals(input.collectorManifest().planId())
                || !collection.collectionId().equals(input.collectorManifest().collectionId())
                || !sameIdentity(collection, input.normalizationManifest())
                || !sameIdentity(collection, input.summary())
                || !collection.collectionId().equals(input.baselineCheck().collectionId())
                || !collection.runId().equals(input.baselineCheck().runId())
                || !collection.caseId().equals(input.baselineCheck().caseId())
                || !collection.analysisId().equals(input.baselineCheck().analysisId())
                || !input.summary().rawTrace().equals(input.rawReference())
                || !input.normalizationManifest().rawArtifact().equals(input.rawReference())
                || input.normalizationManifest().summaryArtifact().isEmpty()
                || !input.normalizationManifest().summaryArtifact().orElseThrow()
                        .equals(input.summaryReference());
        if (mismatch) add(findings, finding(
                "COLLECTION_IDENTITY_MISMATCH", EvidenceValidationStatus.INVALID,
                "Collection, Plan, Manifest, summaryor Baseline identity does not match",
                input.summaryReference()));
    }

    private static void validateCompletion(
            CollectionCompletion completion, List<ValidationFinding> findings) {
        switch (completion) {
            case SUCCESS, TARGET_FAILED -> { }
            case TRUNCATED, TIMED_OUT -> add(findings, finding(
                    "COLLECTION_INCOMPLETE", EvidenceValidationStatus.INCONCLUSIVE,
                    "Dynamic collection did not complete", null));
            case TOOL_FAILED, AGENT_FAILED -> add(findings, finding(
                    "COLLECTION_FAILED", EvidenceValidationStatus.INVALID,
                    "Collector or Agent execution failed", null));
        }
    }

    private static void validateNormalization(
            NormalizationManifest manifest, boolean summaryTruncated,
            boolean boundedPartialUsable,
            ArtifactReference summaryReference, List<ValidationFinding> findings) {
        if (manifest.status() == NormalizationStatus.PARTIAL || summaryTruncated) {
            add(findings, finding("NORMALIZATION_PARTIAL",
                    boundedPartialUsable
                            ? EvidenceValidationStatus.VALID
                            : EvidenceValidationStatus.INCONCLUSIVE,
                    "The normalized summary is incomplete because of a budget or upstream truncation",
                    summaryReference));
        } else if (manifest.status() == NormalizationStatus.FAILED) {
            add(findings, finding("NORMALIZATION_FAILED",
                    EvidenceValidationStatus.INVALID, "Normalization failed",
                    summaryReference));
        }
    }

    private static void validateBaseline(
            CollectionBaselineCheck baseline, ArtifactReference summaryReference,
            List<ValidationFinding> findings) {
        if (baseline.outcome() == ComparisonOutcome.CHANGED) {
            add(findings, finding("BASELINE_CHANGED",
                    EvidenceValidationStatus.CONTRADICTED,
                    "The collected run result does not match the uncollected Baseline",
                    summaryReference));
        } else if (!baseline.evidenceUsable()) {
            add(findings, finding("BASELINE_NOT_CONFIRMED",
                    EvidenceValidationStatus.INCONCLUSIVE,
                    "Consistency between the collected run and uncollected Baseline has not been confirmed",
                    summaryReference));
        }
    }

    private static List<TraceProvenance> provenances(MethodPathSummary summary) {
        LinkedHashSet<TraceProvenance> result = new LinkedHashSet<>();
        summary.methods().forEach(method -> {
            result.add(method.firstObservation());
            result.add(method.lastObservation());
        });
        summary.observedPaths().forEach(path -> result.add(path.firstObservation()));
        summary.anomalies().forEach(anomaly -> result.add(anomaly.provenance()));
        return List.copyOf(result);
    }

    private static boolean sameIdentity(
            org.example.algorithmdebug.contracts.MethodPathCollectionRecord collection,
            NormalizationManifest manifest) {
        return collection.caseId().equals(manifest.caseId())
                && collection.analysisId().equals(manifest.analysisId())
                && collection.runId().equals(manifest.runId())
                && collection.planId().equals(manifest.planId())
                && collection.collectionId().equals(manifest.collectionId())
                && "CODEPATH".equals(manifest.collectorType());
    }

    private static boolean sameIdentity(
            org.example.algorithmdebug.contracts.MethodPathCollectionRecord collection,
            MethodPathSummary summary) {
        return collection.caseId().equals(summary.caseId())
                && collection.analysisId().equals(summary.analysisId())
                && collection.runId().equals(summary.runId())
                && collection.planId().equals(summary.planId())
                && collection.collectionId().equals(summary.collectionId());
    }

    private static EvidenceValidationStatus aggregate(List<ValidationFinding> findings) {
        if (has(findings, EvidenceValidationStatus.INVALID)) return EvidenceValidationStatus.INVALID;
        if (has(findings, EvidenceValidationStatus.CONTRADICTED)) {
            return EvidenceValidationStatus.CONTRADICTED;
        }
        if (has(findings, EvidenceValidationStatus.INCONCLUSIVE)) {
            return EvidenceValidationStatus.INCONCLUSIVE;
        }
        return EvidenceValidationStatus.VALID;
    }

    private static boolean has(
            List<ValidationFinding> findings, EvidenceValidationStatus status) {
        return findings.stream().anyMatch(finding -> finding.status() == status);
    }

    private static ValidationFinding finding(
            String code, EvidenceValidationStatus status, String detail,
            ArtifactReference artifact) {
        return new ValidationFinding(code, status, detail,
                artifact == null ? List.of() : List.of(artifact), Optional.empty());
    }

    private static void add(
            List<ValidationFinding> target, ValidationFinding finding) {
        if (target.size() < MAX_FINDINGS) target.add(finding);
    }

    private static void add(
            List<ValidationFinding> target, List<ValidationFinding> findings) {
        for (ValidationFinding finding : findings) add(target, finding);
    }
}
