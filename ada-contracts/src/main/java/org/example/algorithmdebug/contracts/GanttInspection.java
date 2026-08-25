package org.example.algorithmdebug.contracts;

import java.util.List;

/** 面向 LLM 的有界 Gantt JSON 结构读取结果，不包含业务语义判断。 */
public record GanttInspection(
        String schemaVersion,
        CaseId caseId,
        String artifactId,
        String operation,
        String jsonPointer,
        String nodeType,
        long totalItems,
        int offset,
        int returnedItems,
        boolean truncated,
        List<String> fields,
        String json) {
    public GanttInspection {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported schemaVersion");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        artifactId = ContractChecks.requireOpaqueId(artifactId, "artifactId");
        operation = ContractChecks.requireBoundedText(operation, "operation", 32, false);
        jsonPointer = jsonPointer == null ? "" : jsonPointer;
        nodeType = ContractChecks.requireBoundedText(nodeType, "nodeType", 32, false);
        if (totalItems < 0 || offset < 0 || returnedItems < 0 || returnedItems > 100) {
            throw new IllegalArgumentException("Gantt inspection budget is invalid");
        }
        fields = List.copyOf(ContractChecks.requireNonNull(fields, "fields"));
        json = json == null ? "" : json;
        if (json.length() > 1_048_576) throw new IllegalArgumentException("json is too large");
    }
}
