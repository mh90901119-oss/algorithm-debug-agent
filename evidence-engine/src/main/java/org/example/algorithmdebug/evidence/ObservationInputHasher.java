package org.example.algorithmdebug.evidence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;

/** Observation 输入的稳定长度前缀编码和 SHA-256 计算。 */
final class ObservationInputHasher {
    private static final String HASH_ALGORITHM = "SHA-256";

    private ObservationInputHasher() {
    }

    static String hash(ObservationPredicate predicate, EvidenceView evidence) {
        CanonicalText text = new CanonicalText();
        appendPredicate(text, predicate);
        appendEvidence(text, evidence);
        return sha256(text.value());
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendPredicate(CanonicalText text, ObservationPredicate predicate) {
        text.add("predicate")
                .add(predicate.schemaVersion())
                .add(predicate.predicateId().value())
                .add(predicate.caseId().value())
                .add(predicate.analysisId().value())
                .add(predicate.hypothesisId().value())
                .add(predicate.gapId().value())
                .add(predicate.operator().name())
                .add(predicate.role().name())
                .add(predicate.onTrue().name())
                .add(predicate.onFalse().name())
                .add(predicate.onUnknown().name())
                .add(predicate.createdAt().toString());
        appendSelector(text, predicate.selector());
    }

    private static void appendSelector(CanonicalText text, ObservationSelector selector) {
        switch (selector) {
            case ObservationSelector.MethodObserved value ->
                    text.add("METHOD_OBSERVED").add(value.methodKey());
            case ObservationSelector.RecordExists value -> {
                text.add("RECORD_EXISTS").add(value.recordType()).add(value.fieldPath());
                appendScalar(text, value.expectedValue());
            }
            case ObservationSelector.ValueEquals value -> {
                text.add("VALUE_EQUALS").add(value.projection());
                appendScalar(text, value.expectedValue());
            }
            case ObservationSelector.ValueChanged value -> {
                text.add("VALUE_CHANGED").add(value.projection());
                appendScalar(text, value.before());
                appendScalar(text, value.after());
            }
            case ObservationSelector.CountCompare value -> text.add("COUNT_COMPARE")
                    .add(value.recordType()).add(value.comparator().name())
                    .add(Long.toString(value.expectedCount()));
            case ObservationSelector.PathContains value -> {
                text.add("PATH_CONTAINS").add(Integer.toString(value.methodKeys().size()));
                value.methodKeys().forEach(text::add);
            }
            case ObservationSelector.FailureFingerprintMatches ignored ->
                    text.add("FAILURE_FINGERPRINT_MATCHES");
        }
    }

    private static void appendEvidence(CanonicalText text, EvidenceView evidence) {
        text.add("evidence")
                .add(evidence.caseId().value())
                .add(evidence.analysisId().value())
                .add(evidence.sourceCoverage().name())
                .add(evidence.failureComparisonOutcome().name())
                .add(evidence.observedAt().toString())
                .add("evidenceIds").add(Integer.toString(evidence.evidenceIds().size()));
        evidence.evidenceIds().forEach(value -> text.add(value.value()));
        appendEligibility(text, evidence.eligibility());
        text.add("trackedMethods").add(Integer.toString(evidence.trackedMethodKeys().size()));
        evidence.trackedMethodKeys().forEach(value -> text.add("tracked").add(value));
        text.add("observedMethods").add(Integer.toString(evidence.observedMethodKeys().size()));
        evidence.observedMethodKeys().forEach(value -> text.add("observed").add(value));
        text.add("recordSets").add(Integer.toString(evidence.recordSets().size()));
        for (EvidenceView.RecordSet value : evidence.recordSets()) {
            text.add("record").add(value.recordType()).add(value.fieldPath())
                    .add(value.coverage().name()).add(Integer.toString(value.values().size()));
            value.values().forEach(scalar -> appendScalar(text, scalar));
        }
        text.add("valueSeries").add(Integer.toString(evidence.valueSeries().size()));
        for (EvidenceView.ValueSeries value : evidence.valueSeries()) {
            text.add("series").add(value.projection()).add(Boolean.toString(value.ordered()))
                    .add(value.coverage().name()).add(Integer.toString(value.values().size()));
            value.values().forEach(scalar -> appendScalar(text, scalar));
        }
        text.add("countFacts").add(Integer.toString(evidence.countFacts().size()));
        for (EvidenceView.CountFact value : evidence.countFacts()) {
            text.add("count").add(value.recordType()).add(Long.toString(value.count()))
                    .add(Boolean.toString(value.exact()));
        }
        text.add("pathFacts").add(Integer.toString(evidence.pathFacts().size()));
        for (EvidenceView.PathFact value : evidence.pathFacts()) {
            text.add("path").add(Integer.toString(value.methodKeys().size()));
            value.methodKeys().forEach(text::add);
        }
        text.add("limitations").add(Integer.toString(evidence.limitations().size()));
        evidence.limitations().forEach(value -> text.add("limitation").add(value));
    }

    private static void appendEligibility(CanonicalText text, EvidenceEligibility value) {
        text.add(value.schemaVersion())
                .add(Boolean.toString(value.artifactReadable()))
                .add(Boolean.toString(value.collectionComplete()))
                .add(Boolean.toString(value.baselineRequired()))
                .add(Boolean.toString(value.baselineComparable()))
                .add(Boolean.toString(value.failureFingerprintMatched()))
                .add(Boolean.toString(value.obligationSatisfied()))
                .add(Boolean.toString(value.confirmationEligible()))
                .add("eligibilityReasons").add(Integer.toString(value.reasonCodes().size()));
        value.reasonCodes().forEach(text::add);
    }

    private static void appendScalar(
            CanonicalText text, ObservationSelector.ScalarValue scalar) {
        switch (scalar) {
            case ObservationSelector.TextValue value -> text.add("TEXT").add(value.value());
            case ObservationSelector.LongValue value ->
                    text.add("LONG").add(Long.toString(value.value()));
            case ObservationSelector.DecimalValue value -> text.add("DECIMAL")
                    .add(canonicalDecimal(value.value()));
            case ObservationSelector.BooleanValue value ->
                    text.add("BOOLEAN").add(Boolean.toString(value.value()));
            case ObservationSelector.NullValue ignored -> text.add("NULL");
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static final class CanonicalText {
        private final StringBuilder value = new StringBuilder();

        private CanonicalText add(String item) {
            value.append(item.length()).append(':').append(item);
            return this;
        }

        private String value() {
            return value.toString();
        }
    }
}
