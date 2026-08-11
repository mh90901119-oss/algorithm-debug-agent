package org.example.algorithmdebug.adapter;

import java.nio.file.Path;

/** 将具体算法的调度结果文件解析为类型化快照。 */
@FunctionalInterface
public interface ScheduleResultParser<T extends ScheduleResultSnapshot> {

    /**
     * 解析调度结果。
     *
     * @param resultPath 结果文件
     * @return 类型化结果快照
     * @throws AdapterException 文件缺失、格式错误或业务结构不支持
     */
    T parse(Path resultPath) throws AdapterException;
}

