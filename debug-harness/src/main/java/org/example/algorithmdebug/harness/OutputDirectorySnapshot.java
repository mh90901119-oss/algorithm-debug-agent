package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSource;

import java.util.List;
import java.util.Map;

/**
 * 目标结果目录在某一时刻的不可变快照。
 *
 * @param source 结果源
 * @param files 按相对路径索引的文件元数据
 */
public record OutputDirectorySnapshot(
        ScheduleResultSource source,
        Map<java.nio.file.Path, OutputFileState> files) {

    /** 冻结快照内容。 */
    public OutputDirectorySnapshot {
        if (source == null || files == null) {
            throw new IllegalArgumentException("source 和 files 不能为空");
        }
        files = Map.copyOf(files);
    }

    /**
     * 返回相对于旧快照新增或发生元数据变化的绝对文件路径。
     *
     * @param before 同一结果源的运行前快照
     * @return 按路径排序的新增或修改文件
     */
    public List<java.nio.file.Path> changedSince(OutputDirectorySnapshot before) {
        if (before == null || !source.equals(before.source())) {
            throw new IllegalArgumentException("只能比较同一 ScheduleResultSource 的快照");
        }
        return files.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(before.files().get(entry.getKey())))
                .map(entry -> source.outputDirectory().resolve(entry.getKey()))
                .sorted()
                .toList();
    }
}
