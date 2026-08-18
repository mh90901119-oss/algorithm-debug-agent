package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * 同一 Case 中由调用方显式建立的一段分析版本身份。
 *
 * <p>该记录不扫描或声明源码、输入、POM、Git revision 等内容是否相同。</p>
 *
 * @param schemaVersion Schema 版本
 * @param caseId 所属 Case
 * @param contextId Context ID
 * @param createdAt 创建时间
 */
public record ContextRecord(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        Instant createdAt) {

    /** 校验版本、身份与时间。 */
    public ContextRecord {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.CONTEXT_RECORD, "ContextRecord");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
