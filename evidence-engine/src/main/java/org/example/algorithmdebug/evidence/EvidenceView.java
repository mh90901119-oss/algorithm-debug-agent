package org.example.algorithmdebug.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;

/**
 * Observation Evaluator 的不可变 typed 输入视图。
 *
 * <p>该视图只承载经过归一化和校验的有界事实，不接受 Raw JSON、任意 Map、表达式或文件路径。
 * 上游适配器负责将 CodePath、JDWP 或 Evidence Query 的结果投影为这些固定事实。</p>
 *
 * @param caseId Case 身份
 * @param analysisId Analysis 身份
 * @param evidenceIds 输入 Evidence 身份
 * @param sourceCoverage 输入范围覆盖状态
 * @param eligibility 动态证据正交资格
 * @param failureComparisonOutcome 失败指纹比较结果
 * @param trackedMethodKeys 本次证据明确跟踪的方法
 * @param observedMethodKeys 实际命中的方法
 * @param recordSets 有明确查询范围的记录集合
 * @param valueSeries 命名投影的有序或无序标量序列
 * @param countFacts 确定性计数或下界
 * @param pathFacts 已观测方法路径
 * @param limitations 输入限制
 * @param observedAt 证据确定性时间，用作 Evaluation 时间
 */
public record EvidenceView(
        CaseId caseId,
        AnalysisId analysisId,
        List<EvidenceId> evidenceIds,
        EvidenceSourceCoverage sourceCoverage,
        EvidenceEligibility eligibility,
        ComparisonOutcome failureComparisonOutcome,
        List<String> trackedMethodKeys,
        List<String> observedMethodKeys,
        List<RecordSet> recordSets,
        List<ValueSeries> valueSeries,
        List<CountFact> countFacts,
        List<PathFact> pathFacts,
        List<String> limitations,
        Instant observedAt) {

    /** 校验身份、资格与 typed facts，并将无序输入规范化为稳定顺序。 */
    public EvidenceView {
        caseId = requireNonNull(caseId, "caseId");
        analysisId = requireNonNull(analysisId, "analysisId");
        evidenceIds = sortedUnique(
                evidenceIds, EvidenceId::value, "evidenceIds",
                InvestigationLimits.MAX_REFERENCES);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
        sourceCoverage = requireNonNull(sourceCoverage, "sourceCoverage");
        eligibility = requireNonNull(eligibility, "eligibility");
        failureComparisonOutcome = requireNonNull(
                failureComparisonOutcome, "failureComparisonOutcome");
        requireComparisonConsistency(eligibility, failureComparisonOutcome);

        trackedMethodKeys = sortedUniqueTexts(
                trackedMethodKeys, "trackedMethodKeys", InvestigationLimits.MAX_REFERENCES);
        observedMethodKeys = sortedUniqueTexts(
                observedMethodKeys, "observedMethodKeys", InvestigationLimits.MAX_REFERENCES);
        if (!new HashSet<>(trackedMethodKeys).containsAll(observedMethodKeys)) {
            throw new IllegalArgumentException(
                    "observedMethodKeys must be a subset of trackedMethodKeys");
        }
        recordSets = sortedUnique(
                recordSets, value -> value.recordType() + '\u0000' + value.fieldPath(),
                "recordSets", InvestigationLimits.MAX_REFERENCES);
        valueSeries = sortedUnique(
                valueSeries, ValueSeries::projection, "valueSeries",
                InvestigationLimits.MAX_REFERENCES);
        countFacts = sortedUnique(
                countFacts, CountFact::recordType, "countFacts",
                InvestigationLimits.MAX_REFERENCES);
        pathFacts = sortedUnique(
                pathFacts, value -> String.join("\u0000", value.methodKeys()),
                "pathFacts", InvestigationLimits.MAX_REFERENCES);
        limitations = sortedUniqueTexts(
                limitations, "limitations", InvestigationLimits.MAX_LIMITATIONS);
        observedAt = requireNonNull(observedAt, "observedAt");
    }

    /** @return 指定方法是否处于本次采集明确跟踪范围 */
    public boolean tracksMethod(String methodKey) {
        return trackedMethodKeys.contains(methodKey);
    }

    /** @return 指定方法是否具有至少一次确定性命中 */
    public boolean observesMethod(String methodKey) {
        return observedMethodKeys.contains(methodKey);
    }

    /** 查找精确记录范围。 */
    public Optional<RecordSet> recordSet(String recordType, String fieldPath) {
        return recordSets.stream().filter(value ->
                value.recordType().equals(recordType) && value.fieldPath().equals(fieldPath))
                .findFirst();
    }

    /** 查找命名投影序列。 */
    public Optional<ValueSeries> values(String projection) {
        return valueSeries.stream().filter(value -> value.projection().equals(projection))
                .findFirst();
    }

    /** 查找指定记录类型的计数。 */
    public Optional<CountFact> count(String recordType) {
        return countFacts.stream().filter(value -> value.recordType().equals(recordType))
                .findFirst();
    }

    /**
     * 判断局部范围和整个输入是否都完整。FALSE 只能在该方法返回 true 时证明缺失。
     */
    public boolean isComplete(EvidenceSourceCoverage localCoverage) {
        return sourceCoverage == EvidenceSourceCoverage.COMPLETE
                && localCoverage == EvidenceSourceCoverage.COMPLETE;
    }

    /** 比较两个 typed scalar 的业务值；DECIMAL 忽略无意义的小数位 scale。 */
    public static boolean scalarEquals(
            ObservationSelector.ScalarValue left,
            ObservationSelector.ScalarValue right) {
        requireNonNull(left, "left");
        requireNonNull(right, "right");
        if (left instanceof ObservationSelector.DecimalValue leftDecimal
                && right instanceof ObservationSelector.DecimalValue rightDecimal) {
            return leftDecimal.value().compareTo(rightDecimal.value()) == 0;
        }
        return left.equals(right);
    }

    /** 一类记录中某个字段的已查询 typed 值集合。 */
    public record RecordSet(
            String recordType,
            String fieldPath,
            List<ObservationSelector.ScalarValue> values,
            EvidenceSourceCoverage coverage) {
        /** 校验记录查询范围和值预算。 */
        public RecordSet {
            recordType = requireText(recordType, "recordType");
            fieldPath = requireText(fieldPath, "fieldPath");
            values = immutableScalarSet(values, "values");
            coverage = requireNonNull(coverage, "coverage");
        }
    }

    /** 命名投影的有界标量序列。 */
    public record ValueSeries(
            String projection,
            List<ObservationSelector.ScalarValue> values,
            boolean ordered,
            EvidenceSourceCoverage coverage) {
        /** 校验投影和值预算。 */
        public ValueSeries {
            projection = requireText(projection, "projection");
            values = immutableScalars(values, "values");
            coverage = requireNonNull(coverage, "coverage");
        }
    }

    /** 记录类型的确定性精确计数或已知下界。 */
    public record CountFact(String recordType, long count, boolean exact) {
        /** 校验记录类型与非负计数。 */
        public CountFact {
            recordType = requireText(recordType, "recordType");
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }
    }

    /** 一条保持运行时顺序的方法路径。 */
    public record PathFact(List<String> methodKeys) {
        /** 校验路径非空、有界且保留顺序。 */
        public PathFact {
            if (methodKeys == null || methodKeys.isEmpty()) {
                throw new IllegalArgumentException("methodKeys must not be empty");
            }
            if (methodKeys.size() > InvestigationLimits.MAX_SCOPE_ANCHORS) {
                throw new IllegalArgumentException("methodKeys exceeds the limit");
            }
            ArrayList<String> copied = new ArrayList<>(methodKeys.size());
            for (String methodKey : methodKeys) {
                copied.add(requireText(methodKey, "methodKey"));
            }
            methodKeys = List.copyOf(copied);
        }
    }

    private static List<ObservationSelector.ScalarValue> immutableScalars(
            List<ObservationSelector.ScalarValue> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (values.size() > InvestigationLimits.MAX_REFERENCES) {
            throw new IllegalArgumentException(field + " exceeds the limit");
        }
        ArrayList<ObservationSelector.ScalarValue> copied = new ArrayList<>(values.size());
        for (ObservationSelector.ScalarValue value : values) {
            copied.add(requireNonNull(value, field + " entry"));
        }
        return List.copyOf(copied);
    }

    private static List<ObservationSelector.ScalarValue> immutableScalarSet(
            List<ObservationSelector.ScalarValue> values, String field) {
        List<ObservationSelector.ScalarValue> checked = immutableScalars(values, field);
        ArrayList<ObservationSelector.ScalarValue> sorted = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ObservationSelector.ScalarValue value : checked) {
            if (keys.add(scalarKey(value))) {
                sorted.add(value);
            }
        }
        sorted.sort(Comparator.comparing(EvidenceView::scalarKey));
        return List.copyOf(sorted);
    }

    private static String scalarKey(ObservationSelector.ScalarValue value) {
        return switch (value) {
            case ObservationSelector.TextValue scalar -> "TEXT:" + scalar.value();
            case ObservationSelector.LongValue scalar -> "LONG:" + scalar.value();
            case ObservationSelector.DecimalValue scalar -> {
                java.math.BigDecimal normalized = scalar.value().stripTrailingZeros();
                yield "DECIMAL:" + (normalized.signum() == 0
                        ? "0" : normalized.toPlainString());
            }
            case ObservationSelector.BooleanValue scalar -> "BOOLEAN:" + scalar.value();
            case ObservationSelector.NullValue ignored -> "NULL";
        };
    }

    private static void requireComparisonConsistency(
            EvidenceEligibility eligibility, ComparisonOutcome outcome) {
        if (!eligibility.baselineRequired() && outcome != ComparisonOutcome.NOT_COMPARED) {
            throw new IllegalArgumentException(
                    "A successful target must use NOT_COMPARED failure outcome");
        }
        if (eligibility.failureFingerprintMatched() && outcome != ComparisonOutcome.MATCHED) {
            throw new IllegalArgumentException(
                    "Matched eligibility requires MATCHED failure outcome");
        }
        if (eligibility.baselineRequired()) {
            boolean consistent = switch (outcome) {
                case MATCHED -> eligibility.failureFingerprintMatched();
                case CHANGED -> eligibility.baselineComparable()
                        && !eligibility.failureFingerprintMatched();
                case INCOMPARABLE, NOT_COMPARED -> !eligibility.baselineComparable()
                        && !eligibility.failureFingerprintMatched();
            };
            if (!consistent) {
                throw new IllegalArgumentException(
                        "Failure comparison outcome contradicts EvidenceEligibility");
            }
        }
    }

    private static List<String> sortedUniqueTexts(
            List<String> values, String field, int maximum) {
        return sortedUnique(values, value -> requireText(value, field), field, maximum);
    }

    private static <T> List<T> sortedUnique(
            List<T> values,
            Function<T, String> key,
            String field,
            int maximum) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (values.size() > maximum) {
            throw new IllegalArgumentException(field + " exceeds the limit");
        }
        ArrayList<T> copied = new ArrayList<>(values.size());
        Set<String> keys = new HashSet<>();
        for (T value : values) {
            T checked = requireNonNull(value, field + " entry");
            String itemKey = key.apply(checked);
            if (!keys.add(itemKey)) {
                throw new IllegalArgumentException(field + " must not contain duplicate keys");
            }
            copied.add(checked);
        }
        copied.sort(Comparator.comparing(key));
        return List.copyOf(copied);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()
                || value.length() > InvestigationLimits.MAX_SHORT_TEXT_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be bounded visible text");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
