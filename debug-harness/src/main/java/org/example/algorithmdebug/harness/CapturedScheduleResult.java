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
            throw new IllegalArgumentException("路径和 snapshot 不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
    }
}
