package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** 一个固定 Observation 操作符的无副作用三值实现。 */
public interface ObservationOperatorEvaluator {

    /** @return 该实现唯一负责的操作符 */
    ObservationOperator operator();

    /**
     * 在 typed Evidence 上计算三值结果。
     *
     * @param selector 与 {@link #operator()} 一致的 typed selector
     * @param evidence 已验证证据视图
     * @return truth 与稳定限制码
     */
    Result evaluate(ObservationSelector selector, EvidenceView evidence);

    /** 操作符内部结果，不复制任何 Raw 值。 */
    record Result(ObservationTruth truth, List<String> limitations) {
        /** 校验 truth 和不可变限制列表。 */
        public Result {
            if (truth == null) {
                throw new IllegalArgumentException("truth must not be null");
            }
            if (limitations == null || limitations.stream().anyMatch(value ->
                    value == null || value.isBlank())) {
                throw new IllegalArgumentException("limitations must be valid");
            }
            limitations = List.copyOf(limitations);
            if (truth == ObservationTruth.UNKNOWN && limitations.isEmpty()) {
                throw new IllegalArgumentException("UNKNOWN requires a limitation");
            }
        }
    }
}
