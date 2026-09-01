package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;

import java.nio.file.Path;

/** 本次运行窗口内捕获并复制完成的调度结果。 */
public record CapturedScheduleResult<T extends ScheduleResultSnapshot>(
        Path sourcePath,
        Path capturedPath,
        long sizeBytes,
        T snapshot) {

    /** 校验捕获结果关键字段。 */
    public CapturedScheduleResult {
        if (sourcePath == null || capturedPath == null || snapshot == null) {
            throw new IllegalArgumentException("path and snapshot must not be null");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
