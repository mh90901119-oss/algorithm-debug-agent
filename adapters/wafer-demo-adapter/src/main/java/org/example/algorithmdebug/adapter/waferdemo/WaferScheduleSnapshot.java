package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wafer Demo 调度结果的 Adapter 自有不可变快照。
 *
 * @param schemaVersion Adapter 快照 Schema 版本
 * @param snapshotId 输入快照 ID
 * @param triggerReason 调度触发原因
 * @param algorithm 算法标识
 * @param equipmentId 设备 ID
 * @param jobProcessingMode SERIAL 或 PARALLEL
 * @param makespan 总排程时长
 * @param resources 甘特图资源集合
 * @param operations 完整操作集合
 * @param finalWaferLocations 最终晶圆位置
 */
public record WaferScheduleSnapshot(
        String schemaVersion,
        String snapshotId,
        String triggerReason,
        String algorithm,
        String equipmentId,
        String jobProcessingMode,
        int makespan,
        List<String> resources,
        List<WaferOperationSnapshot> operations,
        Map<String, String> finalWaferLocations) implements ScheduleResultSnapshot {

    /** 当前 Adapter 快照 Schema 版本。 */
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    /** 校验结果级字段、操作 ID 唯一性和 makespan 一致性。 */
    public WaferScheduleSnapshot {
        schemaVersion = WaferDemoChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 WaferScheduleSnapshot schemaVersion: " + schemaVersion);
        }
        snapshotId = WaferDemoChecks.requireNonBlank(snapshotId, "snapshotId");
        triggerReason = WaferDemoChecks.requireNonBlank(triggerReason, "triggerReason");
        algorithm = WaferDemoChecks.requireNonBlank(algorithm, "algorithm");
        equipmentId = WaferDemoChecks.requireNonBlank(equipmentId, "equipmentId");
        jobProcessingMode = WaferDemoChecks.requireNonBlank(jobProcessingMode, "jobProcessingMode");
        resources = WaferDemoChecks.immutableNonBlankStrings(resources, "resources");
        operations = WaferDemoChecks.immutableList(operations, "operations");
        finalWaferLocations = WaferDemoChecks.immutableNonBlankMap(
                finalWaferLocations, "finalWaferLocations");
        if (makespan < 0) {
            throw new IllegalArgumentException("makespan 不能为负数");
        }
        Set<String> operationIds = new HashSet<>();
        for (WaferOperationSnapshot operation : operations) {
            if (!operationIds.add(operation.operationId())) {
                throw new IllegalArgumentException("存在重复 operationId: " + operation.operationId());
            }
        }
        int maximumEnd = operations.stream().mapToInt(WaferOperationSnapshot::end).max().orElse(0);
        if (maximumEnd != makespan) {
            throw new IllegalArgumentException(
                    "makespan 与最大操作结束时间不一致: " + makespan + " != " + maximumEnd);
        }
    }
}

