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
        String question) {

    /** 校验身份与问题，不扫描目标 Workspace。 */
    public CaseSessionRequest {
        if (caseId == null || projectId == null || targetTest == null
                || adapterId == null || question == null) {
            throw new IllegalArgumentException("CaseSessionRequest fields must not be null");
        }
        adapterId = adapterId.strip();
        question = question.strip();
        if (adapterId.isEmpty() || adapterId.length() > 512) {
            throw new IllegalArgumentException("adapterId must not be empty and must not exceed 512");
        }
        if (question.isEmpty() || question.length() > 65_536) {
            throw new IllegalArgumentException("question must not be empty and must not exceed 65536");
        }
    }
}