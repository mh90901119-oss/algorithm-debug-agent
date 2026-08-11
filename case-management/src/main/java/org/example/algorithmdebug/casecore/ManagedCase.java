package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseFingerprint;
import org.example.algorithmdebug.contracts.CaseId;

import java.util.Optional;

/** Case Resolution 使用的最小已持久化 Case 摘要。 */
public record ManagedCase(
        CaseId caseId,
        CaseFingerprint fingerprint,
        Optional<CaseId> parentCaseId) {

    /** 校验并冻结 Case 摘要。 */
    public ManagedCase {
        if (caseId == null || fingerprint == null || parentCaseId == null) {
            throw new IllegalArgumentException("ManagedCase 字段不能为空");
        }
    }
}
