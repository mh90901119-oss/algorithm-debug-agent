package org.example.algorithmdebug.methodpath;

import java.nio.file.Path;
import java.util.Objects;

/** Collector 返回给 Core 的 Manifest 与单一原始 Trace 路径。 */
public record MethodPathCollectionResult(
        MethodPathCollectionRequest request,
        MethodPathManifest manifest,
        Path rawTrace,
        Path stdoutLog,
        Path stderrLog) {
    /** 强制所有返回路径位于调用方分配的 Collection 目录。 */
    public MethodPathCollectionResult {
        request = Objects.requireNonNull(request);
        Path root = request.collectionDirectory();
        rawTrace = inside(root, rawTrace, "rawTrace"); stdoutLog = inside(root, stdoutLog, "stdoutLog");
        stderrLog = inside(root, stderrLog, "stderrLog"); manifest = Objects.requireNonNull(manifest);
        if (!manifest.caseId().equals(request.caseId()) || !manifest.contextId().equals(request.contextId())
                || !manifest.analysisId().equals(request.analysisId())
                || !manifest.collectionId().equals(request.collectionId())
                || !manifest.planId().equals(request.plan().planId())
                || !manifest.runId().equals(request.runId())) {
            throw new IllegalArgumentException("MethodPath 结果身份与请求不一致");
        }
    }
    private static Path inside(Path root, Path value, String name) {
        Path checked = Objects.requireNonNull(value, name).toAbsolutePath().normalize();
        if (!checked.startsWith(root) || checked.equals(root)) throw new IllegalArgumentException(name + " 越过 Collection 目录");
        return checked;
    }
}
