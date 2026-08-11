package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;

import java.util.Optional;

/**
 * 确定性 Case 选择结果。
 *
 * @param action 动作
 * @param existingCaseId 被复用或作为参照的 Case
 * @param parentCaseId 新 Revision 的父 Case
 * @param reason 可审计原因
 */
public record CaseResolution(
        CaseResolutionAction action,
        Optional<CaseId> existingCaseId,
        Optional<CaseId> parentCaseId,
        String reason) {

    /** 校验决策字段。 */
    public CaseResolution {
        if (action == null || existingCaseId == null || parentCaseId == null
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("CaseResolution 字段不能为空");
        }
    }
}
