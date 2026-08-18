package org.example.algorithmdebug.staticanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.TargetTest;

/**
 * 构建目标 UT 静态方法目录所需的全部输入。
 *
 * @param moduleRoot 可独立执行目标 UT 的 Maven 模块目录
 * @param targetTest 目标 UT 方法
 * @param caseId Case 身份
 * @param contextId 源码 Context 身份
 * @param analysisId 本轮 Analysis 身份
 * @param sourceFingerprintSha256 Context 源码指纹
 * @param budget 硬预算
 * @param requestedAt 请求时间，写入目录的创建时间
 */
public record StaticAnalysisRequest(
        Path moduleRoot,
        TargetTest targetTest,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        String sourceFingerprintSha256,
        StaticAnalysisBudget budget,
        Instant requestedAt) {

    /** 校验模块目录、身份与源码指纹。 */
    public StaticAnalysisRequest {
        moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(moduleRoot) || Files.isSymbolicLink(moduleRoot)) {
            throw new IllegalArgumentException("moduleRoot 必须是非符号链接目录");
        }
        targetTest = Objects.requireNonNull(targetTest, "targetTest");
        caseId = Objects.requireNonNull(caseId, "caseId");
        contextId = Objects.requireNonNull(contextId, "contextId");
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        if (sourceFingerprintSha256 == null
                || !sourceFingerprintSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sourceFingerprintSha256 必须是 SHA-256");
        }
        sourceFingerprintSha256 = sourceFingerprintSha256.toLowerCase(java.util.Locale.ROOT);
        budget = Objects.requireNonNull(budget, "budget");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
