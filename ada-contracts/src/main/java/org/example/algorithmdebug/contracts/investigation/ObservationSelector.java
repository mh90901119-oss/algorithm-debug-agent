package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.util.List;

/**
 * 七种操作符的一对一 typed selector，禁止任意表达式 Map。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "selectorType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ObservationSelector.MethodObserved.class, name = "METHOD_OBSERVED"),
    @JsonSubTypes.Type(value = ObservationSelector.RecordExists.class, name = "RECORD_EXISTS"),
    @JsonSubTypes.Type(value = ObservationSelector.ValueEquals.class, name = "VALUE_EQUALS"),
    @JsonSubTypes.Type(value = ObservationSelector.ValueChanged.class, name = "VALUE_CHANGED"),
    @JsonSubTypes.Type(value = ObservationSelector.CountCompare.class, name = "COUNT_COMPARE"),
    @JsonSubTypes.Type(value = ObservationSelector.PathContains.class, name = "PATH_CONTAINS"),
    @JsonSubTypes.Type(
            value = ObservationSelector.FailureFingerprintMatches.class,
            name = "FAILURE_FINGERPRINT_MATCHES")
})
public sealed interface ObservationSelector permits
        ObservationSelector.MethodObserved,
        ObservationSelector.RecordExists,
        ObservationSelector.ValueEquals,
        ObservationSelector.ValueChanged,
        ObservationSelector.CountCompare,
        ObservationSelector.PathContains,
        ObservationSelector.FailureFingerprintMatches {

    /** @return 与 selector 匹配的唯一操作符 */
    ObservationOperator operator();

    /** 方法是否被观测。 */
    record MethodObserved(String methodKey) implements ObservationSelector {
        public MethodObserved {
            methodKey = validateMethodKey(methodKey);
        }
        @Override public ObservationOperator operator() { return ObservationOperator.METHOD_OBSERVED; }
    }

    /** 归一化记录是否存在。 */
    record RecordExists(String recordType, String fieldPath, ScalarValue expectedValue)
            implements ObservationSelector {
        public RecordExists {
            recordType = InvestigationContractChecks.text(
                    recordType, "recordType", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
            fieldPath = InvestigationContractChecks.text(
                    fieldPath, "fieldPath", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
            expectedValue = InvestigationContractChecks.notNull(expectedValue, "expectedValue");
        }
        @Override public ObservationOperator operator() { return ObservationOperator.RECORD_EXISTS; }
    }

    /** 命名投影是否等于 typed 标量。 */
    record ValueEquals(String projection, ScalarValue expectedValue) implements ObservationSelector {
        public ValueEquals {
            projection = InvestigationContractChecks.text(
                    projection, "projection", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
            expectedValue = InvestigationContractChecks.notNull(expectedValue, "expectedValue");
        }
        @Override public ObservationOperator operator() { return ObservationOperator.VALUE_EQUALS; }
    }

    /** 命名投影是否从 before 变为 after。 */
    record ValueChanged(String projection, ScalarValue before, ScalarValue after)
            implements ObservationSelector {
        public ValueChanged {
            projection = InvestigationContractChecks.text(
                    projection, "projection", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
            before = InvestigationContractChecks.notNull(before, "before");
            after = InvestigationContractChecks.notNull(after, "after");
            if (before.equals(after)) {
                throw new IllegalArgumentException("before and after must differ");
            }
        }
        @Override public ObservationOperator operator() { return ObservationOperator.VALUE_CHANGED; }
    }

    /** 有界计数比较。 */
    record CountCompare(String recordType, Comparator comparator, long expectedCount)
            implements ObservationSelector {
        public CountCompare {
            recordType = InvestigationContractChecks.text(
                    recordType, "recordType", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
            comparator = InvestigationContractChecks.notNull(comparator, "comparator");
            if (expectedCount < 0) {
                throw new IllegalArgumentException("expectedCount must not be negative");
            }
        }
        @Override public ObservationOperator operator() { return ObservationOperator.COUNT_COMPARE; }
    }

    /** 方法路径应包含的有序方法键。 */
    record PathContains(List<String> methodKeys) implements ObservationSelector {
        public PathContains {
            methodKeys = InvestigationContractChecks.uniqueTexts(
                    methodKeys, "methodKeys", InvestigationLimits.MAX_SCOPE_ANCHORS);
            if (methodKeys.isEmpty()) {
                throw new IllegalArgumentException("methodKeys must not be empty");
            }
        }
        @Override public ObservationOperator operator() { return ObservationOperator.PATH_CONTAINS; }
    }

    /** 当前 Collection 是否匹配失败指纹。 */
    record FailureFingerprintMatches() implements ObservationSelector {
        @Override public ObservationOperator operator() {
            return ObservationOperator.FAILURE_FINGERPRINT_MATCHES;
        }
    }

    /** COUNT_COMPARE 的固定比较符。 */
    enum Comparator { EQ, NE, LT, LE, GT, GE }

    /** typed 标量，避免将表达式和值退化为自由 Map。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "valueType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextValue.class, name = "TEXT"),
        @JsonSubTypes.Type(value = LongValue.class, name = "LONG"),
        @JsonSubTypes.Type(value = DecimalValue.class, name = "DECIMAL"),
        @JsonSubTypes.Type(value = BooleanValue.class, name = "BOOLEAN"),
        @JsonSubTypes.Type(value = NullValue.class, name = "NULL")
    })
    sealed interface ScalarValue permits TextValue, LongValue, DecimalValue, BooleanValue, NullValue { }

    record TextValue(String value) implements ScalarValue {
        public TextValue {
            value = InvestigationContractChecks.text(
                    value, "value", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
        }
    }
    record LongValue(long value) implements ScalarValue { }
    record DecimalValue(BigDecimal value) implements ScalarValue {
        public DecimalValue {
            value = InvestigationContractChecks.notNull(value, "value");
        }
    }
    record BooleanValue(boolean value) implements ScalarValue { }
    record NullValue() implements ScalarValue { }

    private static String validateMethodKey(String value) {
        return InvestigationContractChecks.text(
                value, "methodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH);
    }
}
