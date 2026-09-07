package org.example.algorithmdebug.contracts;

import java.util.Optional;

/** 同一证据记录内一个命名投影必须满足的精确条件。 */
public record EvidenceValuePredicate(
        String valueName,
        Optional<String> scalarValue,
        Optional<String> valueStatus) {

    public EvidenceValuePredicate {
        valueName = ContractChecks.requireBoundedText(valueName, "valueName", 2_048, false);
        scalarValue = bounded(scalarValue, "scalarValue", 4_096);
        valueStatus = bounded(valueStatus, "valueStatus", 64);
        if (scalarValue.isEmpty() && valueStatus.isEmpty()) {
            throw new IllegalArgumentException(
                    "Evidence value predicate requires scalarValue or valueStatus");
        }
    }

    private static Optional<String> bounded(
            Optional<String> value, String name, int maximum) {
        if (value == null || value.isEmpty()) return Optional.empty();
        return Optional.of(ContractChecks.requireBoundedText(
                value.orElseThrow(), name, maximum, false));
    }
}
