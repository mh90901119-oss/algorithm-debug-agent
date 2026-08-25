package org.example.algorithmdebug.staticanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.TargetTest;

/** 构建目标 UT 静态方法目录所需的有界输入。 */
public record StaticAnalysisRequest(
        Path moduleRoot,
        TargetTest targetTest,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        StaticAnalysisBudget budget,
        Instant requestedAt) {

    /** 校验模块目录、身份和预算。 */
    public StaticAnalysisRequest {
        moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(moduleRoot) || Files.isSymbolicLink(moduleRoot)) {
            throw new IllegalArgumentException("moduleRoot 必须是非符号链接目录");
        }
        targetTest = Objects.requireNonNull(targetTest, "targetTest");
        caseId = Objects.requireNonNull(caseId, "caseId");
        contextId = Objects.requireNonNull(contextId, "contextId");
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        budget = Objects.requireNonNull(budget, "budget");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
