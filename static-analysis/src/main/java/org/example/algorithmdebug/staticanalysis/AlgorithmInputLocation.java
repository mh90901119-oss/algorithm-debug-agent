package org.example.algorithmdebug.staticanalysis;

import java.nio.file.Path;
import java.util.Objects;
import org.example.algorithmdebug.contracts.AlgorithmInputPathKind;

/** 目标测试方法第一层直接 String 字面量解析出的单一算法输入位置。 */
public record AlgorithmInputLocation(
        String variableName,
        Path sourceFile,
        long sourceLine,
        AlgorithmInputPathKind pathKind,
        Path resolvedPath) {
    /** 校验定位结果完整且使用规范化绝对路径。 */
    public AlgorithmInputLocation {
        if (variableName == null || variableName.isBlank() || sourceLine < 1) {
            throw new IllegalArgumentException("Algorithm input source anchor is invalid");
        }
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile").toAbsolutePath().normalize();
        pathKind = Objects.requireNonNull(pathKind, "pathKind");
        resolvedPath = Objects.requireNonNull(resolvedPath, "resolvedPath").toAbsolutePath().normalize();
    }
}
