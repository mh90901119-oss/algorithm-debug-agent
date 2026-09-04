package org.example.algorithmdebug.methodpath;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;

/**
 * 方法路径 Collector 的运行时请求；计划内容由调用方在执行前单独归档和校验。
 */
public record MethodPathCollectionRequest(
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        CodePathCollectionPlan plan,
        CollectionId collectionId,
        Path moduleRoot,
        Path collectionDirectory,
        Path javaExecutable,
        List<String> targetClasspath,
        String targetTestSelector) {

    /** 校验身份、目录、class path 与 JUnit selector。 */
    public MethodPathCollectionRequest {
        caseId = Objects.requireNonNull(caseId, "caseId");
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        runId = Objects.requireNonNull(runId, "runId");
        plan = Objects.requireNonNull(plan, "plan");
        collectionId = Objects.requireNonNull(collectionId, "collectionId");
        if (!plan.caseId().equals(caseId) || !plan.analysisId().equals(analysisId)) {
            throw new IllegalArgumentException("The collection request identity does not match the CodePath plan");
        }
        moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot").toAbsolutePath().normalize();
        collectionDirectory = Objects.requireNonNull(collectionDirectory, "collectionDirectory")
                .toAbsolutePath().normalize();
        javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
        targetClasspath = List.copyOf(Objects.requireNonNull(targetClasspath, "targetClasspath"));
        if (targetClasspath.isEmpty() || targetClasspath.size() > 10_000
                || targetClasspath.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("targetClasspath is invalid");
        }
        if (targetTestSelector == null || targetTestSelector.isBlank()
                || targetTestSelector.length() > 1_024 || !targetTestSelector.contains("#")) {
            throw new IllegalArgumentException("targetTestSelector is invalid");
        }
    }
}
