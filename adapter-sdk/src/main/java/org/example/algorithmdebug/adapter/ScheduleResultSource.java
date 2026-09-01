package org.example.algorithmdebug.adapter;

import java.nio.file.Path;

/**
 * 目标 UT 动态调度结果的输出目录描述。
 *
 * <p>本契约故意不包含文件名时间戳、glob 或“最新文件”规则。Debug Harness 通过运行前后目录
 * 快照确定本次候选，再由 Adapter Parser 判断业务合法性。</p>
 *
 * @param outputDirectory 目标算法写出结果的绝对目录
 * @param recursive 是否递归扫描子目录
 */
public record ScheduleResultSource(Path outputDirectory, boolean recursive) {

    /** 校验并规范化输出目录。目录在 UT 运行前可以尚未创建。 */
    public ScheduleResultSource {
        outputDirectory = AdapterChecks.requireNonNull(outputDirectory, "outputDirectory");
        if (!outputDirectory.isAbsolute()) {
            throw new IllegalArgumentException("outputDirectory must be an absolute path");
        }
        outputDirectory = outputDirectory.normalize();
    }
}
