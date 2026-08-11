package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;

/**
 * 定位固定名称调度结果文件的旧 SPI。
 *
 * @deprecated 动态输出项目应使用 {@link ScheduleResultSource}，由 Debug Harness 通过运行窗口差分采集。
 */
@Deprecated(forRemoval = false, since = "0.2.0")
@FunctionalInterface
public interface ScheduleResultLocator {

    /**
     * 返回本次运行期望读取的结果路径。
     *
     * @param project 目标项目
     * @param targetTest 目标 UT
     * @param runWorkspace Agent 为本次运行分配的工作目录
     * @return 结果文件路径
     * @throws AdapterException 结果规则无法解析
     */
    Path locate(ProjectDescriptor project, TargetTest targetTest, Path runWorkspace)
            throws AdapterException;
}
