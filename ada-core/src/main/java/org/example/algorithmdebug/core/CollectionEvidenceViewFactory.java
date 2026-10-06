package org.example.algorithmdebug.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.evidence.EvidenceView;
import org.example.algorithmdebug.normalizer.BoundedJsonlReader;

/** 将已归一化、已校验的 Collector 产物投影为 Observation Evaluator 的有界 typed 视图。 */
final class CollectionEvidenceViewFactory {
    private static final String CODEPATH_INVOCATION = "CODEPATH_INVOCATION";
    private static final String METHOD_PATH_SUMMARY = "METHOD_PATH_SUMMARY";
    private static final String JDWP_SNAPSHOT = "JDWP_SNAPSHOT";
    private static final String METHOD_REF = "methodRef";
    private static final String METHOD_KEY = "methodKey";
    private static final String TRACEPOINT_ID = "tracepointId";
    private static final String FACT_BUDGET_LIMITATION = "OBSERVATION_FACT_BUDGET_REACHED";
    private static final String VALUE_UNAVAILABLE_PREFIX = "OBSERVATION_VALUE_UNAVAILABLE:";
    private static final String PATH_SCOPE_UNAVAILABLE = "OBSERVATION_PATH_SCOPE_UNAVAILABLE";
    private static final int MAX_FACT_VALUES = InvestigationLimits.MAX_REFERENCES;

    /** 从 MethodPath Summary 和 TRACE 模式的有界派生 invocation 构造视图。 */
    EvidenceView fromCodePath(
            CodePathCollectionPlan plan,
            MethodPathSummary summary,
            Optional<Path> invocationPath,
            EvidenceEligibility eligibility,
            ComparisonOutcome comparisonOutcome,
            List<ObservationPredicate> predicates) {
        requireIdentity(plan.caseId(), plan.analysisId(), summary.caseId(), summary.analysisId());
        Needs needs = Needs.from(predicates);
        MutableFacts facts = new MutableFacts(summary.sourceCoverage(), summary.limitations());
        List<String> trackedMethods = plan.methodSelections().stream()
                .map(value -> value.selector().methodKey()).sorted().toList();
        List<String> observedMethods = summary.methods().stream()
                .filter(value -> value.enterCount() > 0)
                .map(MethodPathSummary.MethodStatistic::methodKey).sorted().toList();

        if (needs.recordFields().contains(new RecordField(METHOD_PATH_SUMMARY, METHOD_KEY))) {
            facts.addRecord(METHOD_PATH_SUMMARY, METHOD_KEY, summary.methods().stream()
                    .map(value -> (ObservationSelector.ScalarValue)
                            new ObservationSelector.TextValue(value.methodKey()))
                    .toList(), summary.sourceCoverage());
        }
        if (needs.countTypes().contains(METHOD_PATH_SUMMARY)) {
            facts.addCount(METHOD_PATH_SUMMARY, summary.methods().size(),
                    summary.sourceCoverage() == EvidenceSourceCoverage.COMPLETE);
        }
        addPathFacts(summary, needs, facts);
        if (needs.maxPathLength() > 2 && summary.scope().isEmpty()) {
            facts.markPartial(PATH_SCOPE_UNAVAILABLE);
        }

        if (plan.captureMode() == CodePathCaptureMode.TRACE && invocationPath.isPresent()) {
            readInvocations(plan, invocationPath.orElseThrow(), needs, facts);
        } else if (plan.captureMode() == CodePathCaptureMode.AGGREGATE) {
            addAggregateFacts(summary, needs, facts);
        }
        return facts.view(
                summary.caseId(), summary.analysisId(), summary.evidenceId(), eligibility,
                comparisonOutcome, trackedMethods, observedMethods, summary.createdAt());
    }

    /** 从 JDWP Snapshot Summary 构造视图，不读取 Raw Trace。 */
    EvidenceView fromJdwp(
            JdwpCollectionPlan plan,
            JdwpSnapshotSummary summary,
            EvidenceEligibility eligibility,
            ComparisonOutcome comparisonOutcome,
            List<ObservationPredicate> predicates) {
        requireIdentity(plan.caseId(), plan.analysisId(), summary.caseId(), summary.analysisId());
        Needs needs = Needs.from(predicates);
        EvidenceSourceCoverage coverage = summary.truncated() || !summary.limitations().isEmpty()
                ? EvidenceSourceCoverage.PARTIAL : EvidenceSourceCoverage.COMPLETE;
        MutableFacts facts = new MutableFacts(coverage, summary.limitations());
        List<JdwpSnapshotSummary.TracepointHit> hits = summary.hits().stream()
                .sorted(Comparator.comparingLong(value -> value.provenance().sequence()
                        .orElse(value.provenance().jsonlLine())))
                .toList();
        if (needs.countTypes().contains(JDWP_SNAPSHOT)) {
            facts.addCount(JDWP_SNAPSHOT, hits.size(), coverage == EvidenceSourceCoverage.COMPLETE);
        }
        if (needs.recordFields().contains(new RecordField(JDWP_SNAPSHOT, TRACEPOINT_ID))) {
            facts.addRecord(JDWP_SNAPSHOT, TRACEPOINT_ID, hits.stream()
                    .map(value -> (ObservationSelector.ScalarValue)
                            new ObservationSelector.TextValue(value.tracepointId()))
                    .toList(), coverage);
        }
        for (String projection : needs.projections()) {
            ArrayList<ObservationSelector.ScalarValue> values = new ArrayList<>();
            boolean complete = coverage == EvidenceSourceCoverage.COMPLETE;
            for (JdwpSnapshotSummary.TracepointHit hit : hits) {
                for (JdwpSnapshotSummary.ProjectionFact fact : hit.projections()) {
                    if (!projection.equals(fact.valuePath())) {
                        continue;
                    }
                    Optional<ObservationSelector.ScalarValue> scalar = scalar(fact);
                    if (scalar.isPresent()) {
                        values.add(scalar.orElseThrow());
                    } else {
                        complete = false;
                        facts.limit(VALUE_UNAVAILABLE_PREFIX + projection);
                    }
                }
            }
            EvidenceSourceCoverage local = complete
                    ? EvidenceSourceCoverage.COMPLETE : EvidenceSourceCoverage.PARTIAL;
            facts.addValues(projection, values, true, local);
            if (needs.recordFields().contains(new RecordField(JDWP_SNAPSHOT, projection))) {
                facts.addRecord(JDWP_SNAPSHOT, projection, values, local);
            }
        }
        return facts.view(
                summary.caseId(), summary.analysisId(), summary.evidenceId(), eligibility,
                comparisonOutcome, List.of(), List.of(), summary.createdAt());
    }

    private static void addPathFacts(
            MethodPathSummary summary, Needs needs, MutableFacts facts) {
        if (!needs.pathRequired()) {
            return;
        }
        summary.scope().ifPresent(scope -> scope.pathVariants().forEach(value ->
                facts.addPath(value.representativeMethodSequence())));
        summary.observedPaths().forEach(value -> facts.addPath(List.of(
                value.ancestorMethodKey(), value.descendantMethodKey())));
    }

    private static void addAggregateFacts(
            MethodPathSummary summary, Needs needs, MutableFacts facts) {
        EvidenceSourceCoverage summaryCoverage = summary.sourceCoverage();
        if (needs.countTypes().contains(CODEPATH_INVOCATION)) {
            facts.addCount(
                    CODEPATH_INVOCATION,
                    summary.methods().stream()
                            .mapToLong(MethodPathSummary.MethodStatistic::enterCount).sum(),
                    summaryCoverage == EvidenceSourceCoverage.COMPLETE);
        }
        if (needs.recordFields().contains(new RecordField(CODEPATH_INVOCATION, METHOD_REF))) {
            facts.addRecord(CODEPATH_INVOCATION, METHOD_REF, summary.methods().stream()
                    .filter(value -> value.enterCount() > 0)
                    .map(value -> (ObservationSelector.ScalarValue)
                            new ObservationSelector.TextValue(value.methodKey()))
                    .toList(), summaryCoverage);
        }
        for (MethodPathSummary.ProjectionDistribution distribution
                : summary.projectionDistributions()) {
            if (!needs.projections().contains(distribution.projectionName())) {
                continue;
            }
            ArrayList<ObservationSelector.ScalarValue> values = new ArrayList<>();
            boolean complete = summary.sourceCoverage() == EvidenceSourceCoverage.COMPLETE
                    && distribution.otherCount() == 0
                    && !distribution.distinctLimitReached();
            for (MethodPathSummary.TrackedValueCount count
                    : distribution.trackedValueCounts()) {
                scalar(count).ifPresent(values::add);
                if (!"VALUE".equals(count.status()) && !"NULL".equals(count.status())) {
                    complete = false;
                }
            }
            EvidenceSourceCoverage local = complete
                    ? EvidenceSourceCoverage.COMPLETE : EvidenceSourceCoverage.PARTIAL;
            facts.addValues(distribution.projectionName(), values, false, local);
            if (needs.recordFields().contains(new RecordField(
                    CODEPATH_INVOCATION, distribution.projectionName()))) {
                facts.addRecord(CODEPATH_INVOCATION,
                        distribution.projectionName(), values, local);
            }
        }
    }

    private static void readInvocations(
            CodePathCollectionPlan plan,
            Path invocationPath,
            Needs needs,
            MutableFacts facts) {
        LinkedHashMap<String, List<ObservationSelector.ScalarValue>> projectionValues =
                new LinkedHashMap<>();
        needs.projections().forEach(value -> projectionValues.put(value, new ArrayList<>()));
        ArrayList<ObservationSelector.ScalarValue> methodRefs = new ArrayList<>();
        long[] count = {0};
        new BoundedJsonlReader().read(
                invocationPath,
                plan.budget().maxBytes(),
                NormalizationBudget.defaults().maxRecordBytes(),
                plan.budget().maxEvents(),
                (line, json) -> {
                    count[0]++;
                    JsonNode methodRef = json.get(METHOD_REF);
                    if (methodRef == null || !methodRef.isTextual()) {
                        throw new IllegalArgumentException(
                                "Normalized CodePath invocation is missing methodRef at line " + line);
                    }
                    methodRefs.add(new ObservationSelector.TextValue(methodRef.textValue()));
                    JsonNode projections = json.path("projections");
                    if (!projections.isArray()) {
                        throw new IllegalArgumentException(
                                "Normalized CodePath invocation projections are invalid at line " + line);
                    }
                    for (JsonNode projection : projections) {
                        String name = projection.path("name").asText();
                        List<ObservationSelector.ScalarValue> values = projectionValues.get(name);
                        if (values == null) {
                            continue;
                        }
                        Optional<ObservationSelector.ScalarValue> value = scalar(
                                projection.path("status").asText(), projection.get("value"));
                        if (value.isPresent()) {
                            values.add(value.orElseThrow());
                        } else {
                            facts.limit(VALUE_UNAVAILABLE_PREFIX + name);
                        }
                    }
                });
        EvidenceSourceCoverage coverage = facts.coverage();
        if (needs.countTypes().contains(CODEPATH_INVOCATION)) {
            facts.addCount(CODEPATH_INVOCATION, count[0],
                    coverage == EvidenceSourceCoverage.COMPLETE);
        }
        if (needs.recordFields().contains(new RecordField(CODEPATH_INVOCATION, METHOD_REF))) {
            facts.addRecord(CODEPATH_INVOCATION, METHOD_REF, methodRefs, coverage);
        }
        projectionValues.forEach((name, values) -> {
            EvidenceSourceCoverage local = facts.hasLimitation(
                    VALUE_UNAVAILABLE_PREFIX + name)
                    ? EvidenceSourceCoverage.PARTIAL : coverage;
            facts.addValues(name, values, true, local);
            if (needs.recordFields().contains(new RecordField(CODEPATH_INVOCATION, name))) {
                facts.addRecord(CODEPATH_INVOCATION, name, values, local);
            }
        });
    }

    private static Optional<ObservationSelector.ScalarValue> scalar(
            JdwpSnapshotSummary.ProjectionFact fact) {
        if (fact.status() != JdwpSnapshotSummary.ProjectionStatus.CAPTURED) {
            return Optional.empty();
        }
        String kind = fact.kind().orElse("");
        if ("NULL".equals(kind)) {
            return Optional.of(new ObservationSelector.NullValue());
        }
        String value = fact.scalarValue().orElse(null);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return switch (kind) {
                case "STRING", "CHAR" -> Optional.of(
                        new ObservationSelector.TextValue(value));
                case "BYTE", "SHORT", "INTEGER", "LONG" -> Optional.of(
                        new ObservationSelector.LongValue(Long.parseLong(value)));
                case "FLOAT", "DOUBLE", "DECIMAL" -> Optional.of(
                        new ObservationSelector.DecimalValue(new BigDecimal(value)));
                case "BOOLEAN" -> Optional.of(
                        new ObservationSelector.BooleanValue(Boolean.parseBoolean(value)));
                default -> Optional.empty();
            };
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
    }

    private static Optional<ObservationSelector.ScalarValue> scalar(
            MethodPathSummary.TrackedValueCount value) {
        if ("NULL".equals(value.status())) {
            return Optional.of(new ObservationSelector.NullValue());
        }
        if (!"VALUE".equals(value.status()) || value.value().isEmpty()) {
            return Optional.empty();
        }
        String text = value.value().orElseThrow();
        try {
            return switch (value.scalarType()) {
                case "STRING", "CHAR" -> Optional.of(new ObservationSelector.TextValue(text));
                case "BYTE", "SHORT", "INTEGER", "LONG" -> Optional.of(
                        new ObservationSelector.LongValue(Long.parseLong(text)));
                case "FLOAT", "DOUBLE", "DECIMAL" -> Optional.of(
                        new ObservationSelector.DecimalValue(new BigDecimal(text)));
                case "BOOLEAN" -> Optional.of(
                        new ObservationSelector.BooleanValue(Boolean.parseBoolean(text)));
                default -> Optional.empty();
            };
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
    }

    private static Optional<ObservationSelector.ScalarValue> scalar(
            String status, JsonNode value) {
        if ("NULL".equals(status)) {
            return Optional.of(new ObservationSelector.NullValue());
        }
        if (!"VALUE".equals(status) || value == null || value.isContainerNode()) {
            return Optional.empty();
        }
        if (value.isTextual()) {
            return Optional.of(new ObservationSelector.TextValue(value.textValue()));
        }
        if (value.isIntegralNumber()) {
            return Optional.of(new ObservationSelector.LongValue(value.longValue()));
        }
        if (value.isFloatingPointNumber()) {
            return Optional.of(new ObservationSelector.DecimalValue(value.decimalValue()));
        }
        if (value.isBoolean()) {
            return Optional.of(new ObservationSelector.BooleanValue(value.booleanValue()));
        }
        if (value.isNull()) {
            return Optional.of(new ObservationSelector.NullValue());
        }
        return Optional.empty();
    }

    private static void requireIdentity(
            org.example.algorithmdebug.contracts.CaseId expectedCase,
            org.example.algorithmdebug.contracts.AnalysisId expectedAnalysis,
            org.example.algorithmdebug.contracts.CaseId actualCase,
            org.example.algorithmdebug.contracts.AnalysisId actualAnalysis) {
        if (!expectedCase.equals(actualCase) || !expectedAnalysis.equals(actualAnalysis)) {
            throw new IllegalArgumentException("Collection plan and summary identity mismatch");
        }
    }

    private record RecordField(String recordType, String fieldPath) {
    }

    private record Needs(
            Set<RecordField> recordFields,
            Set<String> projections,
            Set<String> countTypes,
            int maxPathLength) {
        private static Needs from(List<ObservationPredicate> predicates) {
            if (predicates == null || predicates.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("predicates must not be null");
            }
            LinkedHashSet<RecordField> records = new LinkedHashSet<>();
            LinkedHashSet<String> projections = new LinkedHashSet<>();
            LinkedHashSet<String> counts = new LinkedHashSet<>();
            int[] maxPathLength = {0};
            predicates.forEach(predicate -> {
                switch (predicate.selector()) {
                    case ObservationSelector.RecordExists value -> {
                        records.add(new RecordField(value.recordType(), value.fieldPath()));
                        if (!METHOD_REF.equals(value.fieldPath())
                                && !METHOD_KEY.equals(value.fieldPath())
                                && !TRACEPOINT_ID.equals(value.fieldPath())) {
                            projections.add(value.fieldPath());
                        }
                    }
                    case ObservationSelector.ValueEquals value ->
                            projections.add(value.projection());
                    case ObservationSelector.ValueChanged value ->
                            projections.add(value.projection());
                    case ObservationSelector.CountCompare value ->
                            counts.add(value.recordType());
                    case ObservationSelector.PathContains value ->
                            maxPathLength[0] = Math.max(
                                    maxPathLength[0], value.methodKeys().size());
                    case ObservationSelector.MethodObserved ignored -> { }
                    case ObservationSelector.FailureFingerprintMatches ignored -> { }
                }
            });
            return new Needs(Set.copyOf(records), Set.copyOf(projections),
                    Set.copyOf(counts), maxPathLength[0]);
        }

        private boolean pathRequired() {
            return maxPathLength > 0;
        }
    }

    private static final class MutableFacts {
        private EvidenceSourceCoverage coverage;
        private final ArrayList<EvidenceView.RecordSet> records = new ArrayList<>();
        private final ArrayList<EvidenceView.ValueSeries> values = new ArrayList<>();
        private final ArrayList<EvidenceView.CountFact> counts = new ArrayList<>();
        private final ArrayList<EvidenceView.PathFact> paths = new ArrayList<>();
        private final LinkedHashSet<String> limitations = new LinkedHashSet<>();

        private MutableFacts(EvidenceSourceCoverage coverage, List<String> limitations) {
            this.coverage = coverage;
            this.limitations.addAll(limitations);
        }

        private EvidenceSourceCoverage coverage() {
            return coverage;
        }

        private void addRecord(
                String type, String field, List<ObservationSelector.ScalarValue> input,
                EvidenceSourceCoverage localCoverage) {
            BoundedValues bounded = bounded(input, localCoverage);
            records.add(new EvidenceView.RecordSet(
                    type, field, bounded.values(), bounded.coverage()));
        }

        private void addValues(
                String projection, List<ObservationSelector.ScalarValue> input,
                boolean ordered, EvidenceSourceCoverage localCoverage) {
            BoundedValues bounded = bounded(input, localCoverage);
            values.add(new EvidenceView.ValueSeries(
                    projection, bounded.values(), ordered, bounded.coverage()));
        }

        private BoundedValues bounded(
                List<ObservationSelector.ScalarValue> input,
                EvidenceSourceCoverage localCoverage) {
            if (input.size() <= MAX_FACT_VALUES) {
                return new BoundedValues(List.copyOf(input), localCoverage);
            }
            limit(FACT_BUDGET_LIMITATION);
            coverage = EvidenceSourceCoverage.PARTIAL;
            return new BoundedValues(
                    List.copyOf(input.subList(0, MAX_FACT_VALUES)),
                    EvidenceSourceCoverage.PARTIAL);
        }

        private void addCount(String type, long count, boolean exact) {
            counts.add(new EvidenceView.CountFact(type, count, exact));
        }

        private void addPath(List<String> methodKeys) {
            if (paths.size() >= MAX_FACT_VALUES) {
                limit(FACT_BUDGET_LIMITATION);
                coverage = EvidenceSourceCoverage.PARTIAL;
                return;
            }
            EvidenceView.PathFact value = new EvidenceView.PathFact(methodKeys);
            if (!paths.contains(value)) {
                paths.add(value);
            }
        }

        private void limit(String limitation) {
            if (limitations.size() < InvestigationLimits.MAX_LIMITATIONS) {
                limitations.add(limitation);
            }
        }

        private void markPartial(String limitation) {
            coverage = EvidenceSourceCoverage.PARTIAL;
            limit(limitation);
        }

        private boolean hasLimitation(String limitation) {
            return limitations.contains(limitation);
        }

        private EvidenceView view(
                org.example.algorithmdebug.contracts.CaseId caseId,
                org.example.algorithmdebug.contracts.AnalysisId analysisId,
                org.example.algorithmdebug.contracts.EvidenceId evidenceId,
                EvidenceEligibility eligibility,
                ComparisonOutcome comparisonOutcome,
                List<String> trackedMethods,
                List<String> observedMethods,
                java.time.Instant observedAt) {
            return new EvidenceView(
                    caseId, analysisId, List.of(evidenceId), coverage, eligibility,
                    comparisonOutcome, trackedMethods, observedMethods, records, values,
                    counts, paths, List.copyOf(limitations), observedAt);
        }
    }

    private record BoundedValues(
            List<ObservationSelector.ScalarValue> values,
            EvidenceSourceCoverage coverage) {
    }
}
