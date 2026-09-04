package org.example.algorithmdebug.contracts;

import java.util.HashSet;
import java.util.List;

/** 精确方法选择器及该方法需要读取的标量投影。 */
public record CodePathMethodSelection(
        MethodSelector selector,
        List<CodePathProjection> projections) {

    public CodePathMethodSelection {
        selector = ContractChecks.requireNonNull(selector, "selector");
        projections = ContractChecks.immutableList(projections, "projections");
        if (projections.size() > 32) {
            throw new IllegalArgumentException("A method may contain at most 32 projections");
        }
        HashSet<String> names = new HashSet<>();
        if (projections.stream().anyMatch(projection -> !names.add(projection.name()))) {
            throw new IllegalArgumentException("Projection names must be unique within a method");
        }
    }
}
