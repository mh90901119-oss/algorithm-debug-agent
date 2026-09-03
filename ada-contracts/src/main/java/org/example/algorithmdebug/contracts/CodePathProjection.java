package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 已编译的 CodePath 标量投影。
 *
 * @param name 面向模型的稳定字段名
 * @param source 参数或返回值
 * @param argumentIndex 参数来源时的零基下标
 * @param fieldPath 从根对象开始读取的普通字段路径
 * @param required 读取失败时是否构成证据缺口
 */
public record CodePathProjection(
        String name,
        CodePathProjectionSource source,
        Optional<Integer> argumentIndex,
        List<String> fieldPath,
        boolean required) {

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    public CodePathProjection {
        name = ContractChecks.requireBoundedText(name, "name", 128, false);
        source = ContractChecks.requireNonNull(source, "source");
        argumentIndex = argumentIndex == null ? Optional.empty() : argumentIndex;
        fieldPath = ContractChecks.immutableList(fieldPath, "fieldPath");
        if (fieldPath.size() > 8 || fieldPath.stream().anyMatch(field -> !FIELD_NAME.matcher(field).matches())) {
            throw new IllegalArgumentException("fieldPath must contain at most 8 Java field names");
        }
        if (source == CodePathProjectionSource.ARGUMENT) {
            if (argumentIndex.isEmpty() || argumentIndex.orElseThrow() < 0) {
                throw new IllegalArgumentException("argumentIndex is required for an argument projection");
            }
        } else if (argumentIndex.isPresent()) {
            throw new IllegalArgumentException("argumentIndex must be absent for a return projection");
        }
    }
}
