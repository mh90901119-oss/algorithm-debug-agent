package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseFingerprint;

import java.util.Optional;

/** 使用运行前 Fingerprint 约束 LLM Case 意图的确定性决策服务。 */
public final class CaseResolutionService {

    /**
     * 判断当前请求应复用 Case、新建根 Case、创建 Revision，还是要求人工确认。
     */
    public CaseResolution resolve(
            ManagedCase current,
            CaseFingerprint requested,
            CaseIntent intent) {
        if (requested == null || intent == null) {
            throw new IllegalArgumentException("requested 和 intent 不能为空");
        }
        if (current == null) {
            return new CaseResolution(
                    CaseResolutionAction.NEW_CASE,
                    Optional.empty(),
                    Optional.empty(),
                    "当前没有可复用 Case");
        }
        if (intent == CaseIntent.FORCE_NEW_CASE) {
            return new CaseResolution(
                    CaseResolutionAction.NEW_CASE,
                    Optional.of(current.caseId()),
                    Optional.empty(),
                    "调用方明确要求创建独立 Case");
        }
        if (!current.fingerprint().testSelector().equals(requested.testSelector())) {
            return new CaseResolution(
                    CaseResolutionAction.NEW_CASE,
                    Optional.of(current.caseId()),
                    Optional.empty(),
                    "目标 UT 已变化");
        }
        if (current.fingerprint().equals(requested)) {
            if (intent == CaseIntent.FORCE_NEW_REVISION) {
                return new CaseResolution(
                        CaseResolutionAction.NEW_REVISION,
                        Optional.of(current.caseId()),
                        Optional.of(current.caseId()),
                        "调用方明确要求基于当前身份创建实验 Revision");
            }
            return new CaseResolution(
                    CaseResolutionAction.REUSE_CASE,
                    Optional.of(current.caseId()),
                    Optional.empty(),
                    "运行前 Fingerprint 完全一致");
        }
        if (intent == CaseIntent.FORCE_REUSE) {
            return new CaseResolution(
                    CaseResolutionAction.CONFIRMATION_REQUIRED,
                    Optional.of(current.caseId()),
                    Optional.empty(),
                    "调用方要求复用，但输入、源码或运行环境 Fingerprint 已变化");
        }
        return new CaseResolution(
                CaseResolutionAction.NEW_REVISION,
                Optional.of(current.caseId()),
                Optional.of(current.caseId()),
                "同一 UT 的输入、源码或运行环境 Fingerprint 已变化");
    }
}
