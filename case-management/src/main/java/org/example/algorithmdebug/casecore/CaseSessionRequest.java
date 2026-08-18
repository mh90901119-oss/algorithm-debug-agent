package org.example.algorithmdebug.casecore;

import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

/** 新建或显式续接一次 Case Analysis 所需的确定性输入。 */
public record CaseSessionRequest(
        Optional<CaseId> caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String adapterId,
        String question,
        ContextMode contextMode) {

    /** 校验身份、问题和显式 Context 模式，不扫描目标 Workspace。 */
    public CaseSessionRequest {
        if (caseId == null || projectId == null || targetTest == null
                || adapterId == null || question == null || contextMode == null) {
            throw new IllegalArgumentException("CaseSessionRequest 字段不能为空");
        }
        adapterId = adapterId.strip();
        question = question.strip();
        if (adapterId.isEmpty() || adapterId.length() > 512) {
            throw new IllegalArgumentException("adapterId 不能为空且不能超过 512");
        }
        if (question.isEmpty() || question.length() > 65_536) {
            throw new IllegalArgumentException("question 不能为空且不能超过 65536");
        }
    }
}
