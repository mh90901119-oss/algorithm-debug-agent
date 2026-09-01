package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.Optional;

/**
 * 一轮 Analysis 的算法输入定位结果和当前 Case 唯一不可变输入引用。
 *
 * <p>该契约只保存模块内源码锚点和输入文件名，不保存目标机器绝对路径。输入内容身份由
 * {@link ArtifactReference#sha256()} 表示；该 SHA 只用于输入复用判断，不证明算法结论。</p>
 */
public record AlgorithmInputCapture(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        String variableName,
        String sourceFile,
        long sourceLine,
        AlgorithmInputPathKind pathKind,
        String fileName,
        AlgorithmInputComparison comparison,
        Optional<AnalysisId> previousAnalysisId,
        ArtifactReference artifact,
        Instant capturedAt) {

    /** 校验身份、便携源码锚点、比较关系和 Artifact 类型。 */
    public AlgorithmInputCapture {
        if (!SchemaVersions.ALGORITHM_INPUT_CAPTURE.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported AlgorithmInputCapture schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        variableName = ContractChecks.requireJavaMethodName(variableName, "variableName");
        sourceFile = ContractChecks.requirePortableRelativePath(sourceFile, "sourceFile");
        if (sourceLine < 1) {
            throw new IllegalArgumentException("sourceLine must be positive");
        }
        pathKind = ContractChecks.requireNonNull(pathKind, "pathKind");
        fileName = ContractChecks.requireBoundedText(fileName, "fileName", 512, false);
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")) {
            throw new IllegalArgumentException("fileName must be a single portable segment");
        }
        comparison = ContractChecks.requireNonNull(comparison, "comparison");
        previousAnalysisId = ContractChecks.requireNonNull(previousAnalysisId, "previousAnalysisId");
        if (comparison == AlgorithmInputComparison.FIRST_CAPTURE && previousAnalysisId.isPresent()) {
            throw new IllegalArgumentException("FIRST_CAPTURE must not reference a previous Analysis");
        }
        if (comparison != AlgorithmInputComparison.FIRST_CAPTURE && previousAnalysisId.isEmpty()) {
            throw new IllegalArgumentException("Compared input must reference a previous Analysis");
        }
        artifact = ContractChecks.requireNonNull(artifact, "artifact");
        String expectedPath = "input/" + fileName;
        if (!"algorithm-input".equals(artifact.artifactId())
                || !"ALGORITHM_INPUT".equals(artifact.artifactType())
                || !"application/json".equals(artifact.mediaType())
                || !expectedPath.equals(artifact.relativePath())) {
            throw new IllegalArgumentException("Algorithm input Artifact identity is invalid");
        }
        capturedAt = ContractChecks.requireNonNull(capturedAt, "capturedAt");
    }
}
