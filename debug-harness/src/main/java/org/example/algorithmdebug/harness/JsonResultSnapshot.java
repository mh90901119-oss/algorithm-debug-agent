package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;

/** 领域无关 JSON 结果经过确定性 token 解析后的最小快照。 */
public record JsonResultSnapshot(String schemaVersion)
        implements ScheduleResultSnapshot {

    public JsonResultSnapshot {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("JSON 结果快照无效");
        }
    }
}
