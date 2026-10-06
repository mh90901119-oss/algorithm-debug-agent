package org.example.algorithmdebug.casecore;

import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;

/** 新建或显式续接一次 Case Analysis 所需的确定性输入。 */
public record CaseSessionRequest(
        Optional<CaseId> caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String adapterId,
        String question,
        Optional<ProblemFrame> problemFrame) {

    /** 旧 CLI 迁移期构造器；Coordinator 必须使用 {@link #initialized}。 */
    public CaseSessionRequest(
            Optional<CaseId> caseId,
            ProjectId projectId,
            TargetTest targetTest,
            String adapterId,
            String question) {
        this(caseId, projectId, targetTest, adapterId, question, Optional.empty());
    }

    /** 创建由服务端预分配 Case/Analysis 身份的 Problem Frame 初始化请求。 */
    public static CaseSessionRequest initialized(
            Optional<CaseId> existingCaseId,
            ProjectId projectId,
            String adapterId,
            ProblemFrame problemFrame) {
        if (problemFrame == null) {
            throw new IllegalArgumentException("problemFrame must not be null");
        }
        return new CaseSessionRequest(
                existingCaseId, projectId, problemFrame.targetTest(), adapterId,
                problemFrame.symptom(), Optional.of(problemFrame));
    }

    /** 校验身份与问题，不扫描目标 Workspace。 */
    public CaseSessionRequest {
        if (caseId == null || projectId == null || targetTest == null
                || adapterId == null || question == null || problemFrame == null) {
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
        problemFrame = problemFrame.map(frame -> {
            if (!frame.targetTest().equals(targetTest)
                    || (caseId.isPresent() && !caseId.orElseThrow().equals(frame.caseId()))) {
                throw new IllegalArgumentException(
                        "ProblemFrame identity and target must match the Case session request");
            }
            return frame;
        });
    }
}
