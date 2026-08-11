package org.example.algorithmdebug.adapter.waferdemo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ScheduleResultParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 使用 Jackson 将 Demo 甘特图 JSON 解析为独立结果快照。 */
public final class WaferScheduleResultParser implements ScheduleResultParser<WaferScheduleSnapshot> {

    private final ObjectMapper objectMapper;

    /** 创建允许未来新增可选字段的结果 Parser。 */
    public WaferScheduleResultParser() {
        this(new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    WaferScheduleResultParser(ObjectMapper objectMapper) {
        this.objectMapper = WaferDemoChecks.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public WaferScheduleSnapshot parse(Path resultPath) throws AdapterException {
        if (resultPath == null || !Files.isRegularFile(resultPath)) {
            throw new AdapterException(
                    "ADAPTER_RESULT_NOT_FOUND",
                    "Wafer Demo 调度结果不存在: " + resultPath);
        }
        try {
            RawScheduleResult raw = objectMapper.readValue(resultPath.toFile(), RawScheduleResult.class);
            return new WaferScheduleSnapshot(
                    WaferScheduleSnapshot.CURRENT_SCHEMA_VERSION,
                    raw.snapshotId(),
                    raw.triggerReason(),
                    raw.algorithm(),
                    raw.equipmentId(),
                    raw.jobProcessingMode(),
                    raw.makespan(),
                    raw.resources(),
                    raw.operations(),
                    raw.finalWaferLocations());
        } catch (IOException | IllegalArgumentException | NullPointerException exception) {
            throw new AdapterException(
                    "ADAPTER_RESULT_PARSE_FAILED",
                    "无法解析 Wafer Demo 调度结果: " + resultPath,
                    exception);
        }
    }

    private record RawScheduleResult(
            String snapshotId,
            String triggerReason,
            String algorithm,
            String equipmentId,
            String jobProcessingMode,
            int makespan,
            List<String> resources,
            List<WaferOperationSnapshot> operations,
            Map<String, String> finalWaferLocations) {
    }
}

