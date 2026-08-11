package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** 使用有界文件数扫描目标算法输出目录。 */
public final class OutputDirectorySnapshotter {

    private final int maximumFiles;

    /**
     * @param maximumFiles 单次目录快照允许的最大文件数
     */
    public OutputDirectorySnapshotter(int maximumFiles) {
        if (maximumFiles <= 0) {
            throw new IllegalArgumentException("maximumFiles 必须为正数");
        }
        this.maximumFiles = maximumFiles;
    }

    /** 创建结果目录快照；UT 尚未创建目录时返回空快照。 */
    public OutputDirectorySnapshot snapshot(ScheduleResultSource source) throws HarnessException {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        Path root = source.outputDirectory();
        if (!Files.exists(root)) {
            return new OutputDirectorySnapshot(source, Map.of());
        }
        if (!Files.isDirectory(root)) {
            throw new HarnessException("HARNESS_RESULT_SOURCE_MISSING", "结果源不是目录: " + root);
        }
        int depth = source.recursive() ? Integer.MAX_VALUE : 1;
        Map<Path, OutputFileState> states = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root, depth)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (states.size() >= maximumFiles) {
                    throw new HarnessException(
                            "HARNESS_RESULT_SOURCE_TOO_LARGE",
                            "结果目录文件数超过上限 " + maximumFiles + ": " + root);
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                Path relative = root.relativize(path).normalize();
                states.put(relative, new OutputFileState(
                        relative,
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis()));
            }
            return new OutputDirectorySnapshot(source, states);
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_RESULT_SOURCE_READ_FAILED",
                    "无法扫描结果目录: " + root,
                    exception);
        }
    }
}
