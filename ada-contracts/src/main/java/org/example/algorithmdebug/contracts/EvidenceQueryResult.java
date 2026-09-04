package org.example.algorithmdebug.contracts;

import java.nio.charset.StandardCharsets;

/** 面向大模型的有界动态证据查询结果。 */
public record EvidenceQueryResult(
        String schemaVersion,
        ArtifactReference artifact,
        String recordType,
        EvidenceQueryFilter filter,
        long scannedRecords,
        long matchedRecords,
        int offset,
        int limit,
        int returnedRecords,
        boolean truncated,
        String recordsJsonl) {

    /** 校验统计关系和 64 KiB 输出硬上限。 */
    public EvidenceQueryResult {
        if (!SchemaVersions.EVIDENCE_QUERY_RESULT.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Evidence Query result schemaVersion");
        }
        artifact = ContractChecks.requireNonNull(artifact, "artifact");
        recordType = ContractChecks.requireBoundedText(recordType, "recordType", 64, false);
        filter = ContractChecks.requireNonNull(filter, "filter");
        if (scannedRecords < 0 || matchedRecords < 0 || matchedRecords > scannedRecords
                || offset < 0 || limit < 1 || limit > 50
                || returnedRecords < 0 || returnedRecords > limit
                || returnedRecords > Math.max(0, matchedRecords - offset)) {
            throw new IllegalArgumentException("Evidence Query result counters are invalid");
        }
        if (truncated != ((long) offset + returnedRecords < matchedRecords)) {
            throw new IllegalArgumentException("Evidence Query truncated flag is inconsistent");
        }
        if (recordsJsonl == null
                || recordsJsonl.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("recordsJsonl exceeds the output byte budget");
        }
    }
}
