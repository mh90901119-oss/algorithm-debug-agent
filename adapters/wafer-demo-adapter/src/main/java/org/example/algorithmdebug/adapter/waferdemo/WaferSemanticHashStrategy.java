package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.SemanticHashStrategy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 计算 Wafer Demo 调度结果的稳定语义 SHA-256。 */
public final class WaferSemanticHashStrategy
        implements SemanticHashStrategy<WaferScheduleSnapshot> {

    @Override
    public String semanticHash(WaferScheduleSnapshot snapshot) throws AdapterException {
        WaferDemoChecks.requireNonNull(snapshot, "snapshot");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "WAFER_SCHEDULE_SEMANTIC_HASH_V1");
            update(digest, snapshot.algorithm());
            update(digest, snapshot.equipmentId());
            update(digest, snapshot.jobProcessingMode());
            update(digest, snapshot.makespan());

            List<String> resources = snapshot.resources().stream().sorted().toList();
            update(digest, resources.size());
            resources.forEach(value -> update(digest, value));

            List<WaferOperationSnapshot> operations = snapshot.operations().stream()
                    .sorted(Comparator.comparing(WaferOperationSnapshot::operationId))
                    .toList();
            update(digest, operations.size());
            operations.forEach(operation -> updateOperation(digest, operation));

            List<Map.Entry<String, String>> locations = snapshot.finalWaferLocations().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            update(digest, locations.size());
            locations.forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AdapterException(
                    "ADAPTER_SEMANTIC_HASH_FAILED",
                    "当前 JVM 不支持 SHA-256",
                    exception);
        }
    }

    private static void updateOperation(MessageDigest digest, WaferOperationSnapshot operation) {
        update(digest, operation.operationId());
        update(digest, operation.waferId());
        update(digest, operation.jobId());
        update(digest, operation.sequenceId());
        update(digest, operation.jobStartOrder());
        update(digest, operation.waferOrder());
        update(digest, operation.operationIndex());
        update(digest, operation.sequenceStepId());
        update(digest, operation.sequenceStepIndex());
        update(digest, operation.operationType());
        List<String> resourceIds = operation.resourceIds().stream().sorted().toList();
        update(digest, resourceIds.size());
        resourceIds.forEach(resource -> update(digest, resource));
        update(digest, operation.fromLocation());
        update(digest, operation.toLocation());
        update(digest, operation.start());
        update(digest, operation.end());
        update(digest, operation.duration());
        update(digest, operation.source());
        // schedulingReason 是说明文本，不改变实际资源占用和调度时间，因此明确排除。
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}

