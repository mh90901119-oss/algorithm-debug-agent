package org.example.algorithmdebug.evidence;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;

/** 七种冻结 Observation 操作符的完整不可变注册表。 */
public final class ObservationOperatorRegistry {
    private static final Map<ObservationOperator, ObservationOperatorEvaluator> DEFAULTS =
            complete(List.of(
                    new MethodObservedEvaluator(),
                    new RecordExistsEvaluator(),
                    new ValueEqualsEvaluator(),
                    new ValueChangedEvaluator(),
                    new CountCompareEvaluator(),
                    new PathContainsEvaluator(),
                    new FailureFingerprintEvaluator()));

    private final Map<ObservationOperator, ObservationOperatorEvaluator> evaluators;

    /** 创建包含全部冻结操作符的生产注册表。 */
    public ObservationOperatorRegistry() {
        this.evaluators = DEFAULTS;
    }

    ObservationOperatorRegistry(List<ObservationOperatorEvaluator> evaluators) {
        this.evaluators = complete(evaluators);
    }

    /**
     * 返回操作符实现；注册表构造时已证明完整，因此未知值只可能来自空输入。
     */
    public ObservationOperatorEvaluator require(ObservationOperator operator) {
        if (operator == null) {
            throw new IllegalArgumentException("operator must not be null");
        }
        ObservationOperatorEvaluator evaluator = evaluators.get(operator);
        if (evaluator == null) {
            throw new IllegalStateException("Observation operator is not registered: " + operator);
        }
        return evaluator;
    }

    private static Map<ObservationOperator, ObservationOperatorEvaluator> complete(
            List<ObservationOperatorEvaluator> values) {
        if (values == null) {
            throw new IllegalArgumentException("evaluators must not be null");
        }
        EnumMap<ObservationOperator, ObservationOperatorEvaluator> byOperator =
                new EnumMap<>(ObservationOperator.class);
        for (ObservationOperatorEvaluator evaluator : values) {
            if (evaluator == null || byOperator.putIfAbsent(evaluator.operator(), evaluator) != null) {
                throw new IllegalArgumentException(
                        "Observation operator evaluators must be non-null and unique");
            }
        }
        if (!byOperator.keySet().equals(EnumSet.allOf(ObservationOperator.class))) {
            throw new IllegalArgumentException("Observation operator registry must be complete");
        }
        return Collections.unmodifiableMap(byOperator);
    }
}
