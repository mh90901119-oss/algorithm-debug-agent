package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.InputSnapshotStatus;

import java.nio.file.Path;
import java.util.Optional;

/** Adapter 交给 Context Builder 的输入定位事实，不包含输入内容。 */
public record ContextInputProbe(
        InputSnapshotStatus status,
        Optional<Path> path,
        String relativePath,
        String diagnostic) {

    /** 校验状态、可选路径和有界说明的一致性。 */
    public ContextInputProbe {
        if (status == null || path == null || relativePath == null || diagnostic == null) {
            throw new IllegalArgumentException("ContextInputProbe 字段不能为空");
        }
        path = path.map(value -> value.toAbsolutePath().normalize());
        if (status == InputSnapshotStatus.PRESENT && path.isEmpty()) {
            throw new IllegalArgumentException("PRESENT 输入必须包含路径");
        }
        if (status != InputSnapshotStatus.PRESENT && path.isPresent()) {
            throw new IllegalArgumentException(status + " 输入不得包含路径");
        }
        relativePath = checkedRelativePath(relativePath);
        diagnostic = diagnostic.strip();
        if (diagnostic.length() > 2_048) {
            throw new IllegalArgumentException("diagnostic 长度不能超过 2048");
        }
    }

    /** 创建输入存在的定位事实。 */
    public static ContextInputProbe present(Path path, String relativePath) {
        return new ContextInputProbe(
                InputSnapshotStatus.PRESENT, Optional.ofNullable(path), relativePath, "");
    }

    /** 创建输入缺失的定位事实。 */
    public static ContextInputProbe missing(String relativePath, String diagnostic) {
        return new ContextInputProbe(
                InputSnapshotStatus.MISSING, Optional.empty(), relativePath, diagnostic);
    }

    /** 创建目标 UT 不依赖独立输入文件的事实。 */
    public static ContextInputProbe notApplicable() {
        return new ContextInputProbe(
                InputSnapshotStatus.NOT_APPLICABLE, Optional.empty(), "", "");
    }

    /** 创建 Adapter 无法确定输入的事实。 */
    public static ContextInputProbe unresolved(String diagnostic) {
        return new ContextInputProbe(
                InputSnapshotStatus.UNRESOLVED, Optional.empty(), "", diagnostic);
    }

    private static String checkedRelativePath(String value) {
        if (value.isEmpty()) {
            return value;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("//")) {
            throw new IllegalArgumentException("relativePath 必须是可移植相对路径");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("relativePath 包含非法路径段");
            }
        }
        return normalized;
    }
}
