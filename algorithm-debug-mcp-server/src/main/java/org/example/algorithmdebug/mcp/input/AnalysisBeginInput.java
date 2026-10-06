package org.example.algorithmdebug.mcp.input;

import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.ActionInputLimits;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;

/** 创建新 Analysis 的不可变 Problem Frame 输入；Case/Analysis 身份由 Server 分配。 */
public record AnalysisBeginInput(
        String symptom,
        String expectedBehavior,
        String actualBehavior,
        TargetTest targetTest,
        List<SourceAnchor> scopeAnchors,
        List<String> knownFactRefs,
        List<String> initialUnknowns,
        String existingCaseId,
        String adapterId) {
    /** 拒绝空集合元素；完整业务预算由 {@code ProblemFrame} 再校验。 */
    public AnalysisBeginInput {
        symptom = McpInputChecks.required(symptom, "symptom");
        expectedBehavior = McpInputChecks.required(expectedBehavior, "expectedBehavior");
        actualBehavior = McpInputChecks.required(actualBehavior, "actualBehavior");
        targetTest = McpInputChecks.required(targetTest, "targetTest");
        scopeAnchors = McpInputChecks.list(scopeAnchors, "scopeAnchors");
        knownFactRefs = McpInputChecks.list(knownFactRefs, "knownFactRefs");
        initialUnknowns = McpInputChecks.list(initialUnknowns, "initialUnknowns");
        McpInputChecks.optionalText(
                existingCaseId, "existingCaseId", InvestigationLimits.MAX_ID_LENGTH);
        McpInputChecks.optionalText(
                adapterId, "adapterId", ActionInputLimits.MAX_ADAPTER_ID_LENGTH);
    }

    /** @return 可选既有 Case */
    public Optional<CaseId> existingCase() {
        return McpInputChecks.optionalText(
                existingCaseId, "existingCaseId", InvestigationLimits.MAX_ID_LENGTH)
                .map(CaseId::new);
    }

    /** @return 可选目标 Adapter */
    public Optional<String> adapter() {
        return McpInputChecks.optionalText(
                adapterId, "adapterId", ActionInputLimits.MAX_ADAPTER_ID_LENGTH);
    }
}
