package org.example.algorithmdebug.contracts;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 面向大模型的有界动态证据查询结果。 */
public record EvidenceQueryResult(
        String schemaVersion,
        ArtifactReference artifact,
        String recordType,
        EvidenceQueryMode mode,
        EvidenceQueryOutcome outcome,
        EvidenceSourceCoverage sourceCoverage,
        EvidenceQueryRequest request,
        long scannedRecords,
        long matchedRecords,
        int returnedRecords,
        boolean queryMoreAvailable,
        List<String> limitations,
        EvidenceQueryNextAction nextAction,
        String recordsJsonl) {

    /** 校验统计关系和 64 KiB 输出硬上限。 */
    public EvidenceQueryResult {
        if (!SchemaVersions.EVIDENCE_QUERY_RESULT.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Evidence Query result schemaVersion");
        }
        artifact = ContractChecks.requireNonNull(artifact, "artifact");
        recordType = ContractChecks.requireBoundedText(recordType, "recordType", 64, false);
        mode = ContractChecks.requireNonNull(mode, "mode");
        outcome = ContractChecks.requireNonNull(outcome, "outcome");
        sourceCoverage = ContractChecks.requireNonNull(sourceCoverage, "sourceCoverage");
        request = ContractChecks.requireNonNull(request, "request");
        if (scannedRecords < 0 || matchedRecords < 0 || matchedRecords > scannedRecords
                || returnedRecords < 0 || returnedRecords > request.limit()) {
            throw new IllegalArgumentException("Evidence Query result counters are invalid");
        }
        limitations = ContractChecks.immutableBoundedStrings(limitations, "limitations", 256);
        if (limitations.size() > 32) throw new IllegalArgumentException("limitations exceeds the limit");
        nextAction = ContractChecks.requireNonNull(nextAction, "nextAction");
        if (recordsJsonl == null
                || recordsJsonl.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("recordsJsonl exceeds the output byte budget");
        }
    }

    /** 兼容原 FILTER 查询调用方。 */
    public EvidenceQueryResult(
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
        this(schemaVersion, artifact, recordType, EvidenceQueryMode.FILTER,
                matchedRecords > 0 ? EvidenceQueryOutcome.MATCHED : EvidenceQueryOutcome.NO_MATCH,
                EvidenceSourceCoverage.UNKNOWN,
                EvidenceQueryRequest.filter(filter, List.of(), offset, limit, 65_536),
                scannedRecords, matchedRecords, returnedRecords, truncated,
                List.of("SOURCE_COVERAGE_UNKNOWN"),
                truncated ? EvidenceQueryNextAction.NARROW_OR_PAGE
                        : matchedRecords > 0 ? EvidenceQueryNextAction.INSPECT_MATCHED_RECORDS
                        : EvidenceQueryNextAction.RECOLLECT_WITH_BETTER_SCOPE,
                recordsJsonl);
    }

    public EvidenceQueryFilter filter() {
        return request.filter();
    }

    public int offset() {
        return request.offset();
    }

    public int limit() {
        return request.limit();
    }

    public boolean truncated() {
        return queryMoreAvailable;
    }
}
