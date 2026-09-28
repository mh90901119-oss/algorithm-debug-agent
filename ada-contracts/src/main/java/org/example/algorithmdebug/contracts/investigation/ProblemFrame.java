package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;

/**
 * 单个 Analysis 不可修改的问题边界。
 *
 * @param schemaVersion Schema 版本
 * @param problemFrameId Problem Frame ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param symptom 待解释症状
 * @param expectedBehavior 预期行为
 * @param actualBehavior 实际行为
 * @param targetTest 目标 UT
 * @param scopeAnchors 初始源码范围
 * @param knownFactRefs 已知事实引用
 * @param initialUnknowns 初始未知项
 * @param createdAt 创建时间
 */
public record ProblemFrame(
        String schemaVersion,
        ProblemFrameId problemFrameId,
        CaseId caseId,
        AnalysisId analysisId,
        String symptom,
        String expectedBehavior,
        String actualBehavior,
        TargetTest targetTest,
        List<SourceAnchor> scopeAnchors,
        List<String> knownFactRefs,
        List<String> initialUnknowns,
        Instant createdAt) {

    /** 校验问题、目标、源码范围和初始未知项。 */
    public ProblemFrame {
        InvestigationContractChecks.version(schemaVersion, SchemaVersions.PROBLEM_FRAME, "ProblemFrame");
        problemFrameId = InvestigationContractChecks.notNull(problemFrameId, "problemFrameId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        symptom = InvestigationContractChecks.text(
                symptom, "symptom", InvestigationLimits.MAX_TEXT_LENGTH);
        expectedBehavior = InvestigationContractChecks.text(
                expectedBehavior, "expectedBehavior", InvestigationLimits.MAX_TEXT_LENGTH);
        actualBehavior = InvestigationContractChecks.text(
                actualBehavior, "actualBehavior", InvestigationLimits.MAX_TEXT_LENGTH);
        targetTest = InvestigationContractChecks.notNull(targetTest, "targetTest");
        scopeAnchors = InvestigationContractChecks.unique(
                scopeAnchors, "scopeAnchors", InvestigationLimits.MAX_SCOPE_ANCHORS);
        if (scopeAnchors.isEmpty()) {
            throw new IllegalArgumentException("scopeAnchors must not be empty");
        }
        knownFactRefs = InvestigationContractChecks.uniqueTexts(
                knownFactRefs, "knownFactRefs", InvestigationLimits.MAX_REFERENCES);
        initialUnknowns = InvestigationContractChecks.uniqueTexts(
                initialUnknowns, "initialUnknowns", InvestigationLimits.MAX_GAPS);
        if (initialUnknowns.isEmpty()) {
            throw new IllegalArgumentException("initialUnknowns must not be empty");
        }
        createdAt = InvestigationContractChecks.notNull(createdAt, "createdAt");
    }
}
