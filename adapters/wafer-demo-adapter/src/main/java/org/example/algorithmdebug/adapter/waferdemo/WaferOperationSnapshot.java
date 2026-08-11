package org.example.algorithmdebug.adapter.waferdemo;

import java.util.List;

/**
 * 甘特图中的一个晶圆操作快照。
 *
 * @param operationId 操作 ID
 * @param waferId 晶圆 ID
 * @param jobId Job ID
 * @param sequenceId Sequence ID
 * @param jobStartOrder Job 启动顺序
 * @param waferOrder 同 Job 抽片顺序
 * @param operationIndex 晶圆操作链序号
 * @param sequenceStepId Sequence step ID
 * @param sequenceStepIndex Sequence step 序号
 * @param operationType PICK、PLACE 或 RECIPE
 * @param resourceIds 同时占用的资源
 * @param fromLocation 起点
 * @param toLocation 终点
 * @param start 相对开始时间
 * @param end 相对结束时间
 * @param duration 持续时间
 * @param source SCHEDULED 或 RUNNING_JOB
 * @param schedulingReason Demo 输出的解释文本
 */
public record WaferOperationSnapshot(
        String operationId,
        String waferId,
        String jobId,
        String sequenceId,
        int jobStartOrder,
        int waferOrder,
        int operationIndex,
        String sequenceStepId,
        int sequenceStepIndex,
        String operationType,
        List<String> resourceIds,
        String fromLocation,
        String toLocation,
        int start,
        int end,
        int duration,
        String source,
        String schedulingReason) {

    /** 校验操作基本字段和时间区间自洽性。 */
    public WaferOperationSnapshot {
        operationId = WaferDemoChecks.requireNonBlank(operationId, "operationId");
        waferId = WaferDemoChecks.requireNonBlank(waferId, "waferId");
        jobId = WaferDemoChecks.requireNonBlank(jobId, "jobId");
        sequenceId = WaferDemoChecks.requireNonBlank(sequenceId, "sequenceId");
        sequenceStepId = WaferDemoChecks.requireNonBlank(sequenceStepId, "sequenceStepId");
        operationType = WaferDemoChecks.requireNonBlank(operationType, "operationType");
        resourceIds = WaferDemoChecks.immutableNonBlankStrings(resourceIds, "resourceIds");
        fromLocation = WaferDemoChecks.requireNonBlank(fromLocation, "fromLocation");
        toLocation = WaferDemoChecks.requireNonBlank(toLocation, "toLocation");
        source = WaferDemoChecks.requireNonBlank(source, "source");
        schedulingReason = WaferDemoChecks.requireNonBlank(schedulingReason, "schedulingReason");
        if (jobStartOrder < 0 || waferOrder < 0 || operationIndex < 0 || sequenceStepIndex < 0) {
            throw new IllegalArgumentException("操作顺序字段不能为负数: " + operationId);
        }
        if (start < 0 || end < start || duration < 0 || duration != end - start) {
            throw new IllegalArgumentException("操作时间区间不自洽: " + operationId);
        }
    }
}

