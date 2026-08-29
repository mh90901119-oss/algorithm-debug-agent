package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 从目标 UT 运行窗口中确定性发现、验证并不可变复制唯一调度结果。 */
public final class ScheduleResultCapture<T extends ScheduleResultSnapshot> {

    private final OutputDirectorySnapshotter snapshotter;
    private final long maximumResultBytes;

    /** 创建有单文件大小预算的捕获器。 */
    public ScheduleResultCapture(
            OutputDirectorySnapshotter snapshotter,
            long maximumResultBytes) {
        if (snapshotter == null) {
            throw new IllegalArgumentException("snapshotter must not be null");
        }
        if (maximumResultBytes <= 0) {
            throw new IllegalArgumentException("maximumResultBytes must be positive");
        }
        this.snapshotter = snapshotter;
        this.maximumResultBytes = maximumResultBytes;
    }

    /**
     * 对运行前快照和当前目录做差分，只接受唯一能被 Parser 解析的候选，并复制到不可变目标。
     */
    public CapturedScheduleResult<T> capture(
            OutputDirectorySnapshot before,
            ScheduleResultSource source,
            ScheduleResultParser<T> parser,
            Path destination) throws HarnessException {
        if (before == null || source == null || parser == null || destination == null) {
            throw new IllegalArgumentException("Capture arguments must not be null");
        }
        OutputDirectorySnapshot after = snapshotter.snapshot(source);
        return capture(before, after, parser, destination);
    }

    /**
     * 使用已经完成稳定性确认的 after 快照捕获结果，避免再次扫描引入运行窗口竞态。
     */
    public CapturedScheduleResult<T> capture(
            OutputDirectorySnapshot before,
            OutputDirectorySnapshot after,
            ScheduleResultParser<T> parser,
            Path destination) throws HarnessException {
        if (before == null || after == null || parser == null || destination == null) {
            throw new IllegalArgumentException("Capture arguments must not be null");
        }
        List<Path> changed = after.changedSince(before);
        List<ParsedCandidate<T>> valid = new ArrayList<>();
        for (Path candidate : changed) {
            try {
                long size = Files.size(candidate);
                if (size > maximumResultBytes) {
                    continue;
                }
                T snapshot = parser.parse(candidate);
                valid.add(new ParsedCandidate<>(candidate, size, snapshot));
            } catch (IOException | AdapterException ignored) {
                // 候选合法性由业务 Parser 决定；无效旁路文件不是 Harness 失败。
            }
        }
        if (valid.isEmpty()) {
            throw new HarnessException(
                    "HARNESS_RESULT_NOT_PRODUCED",
                    "The current UT run window produced no parseable schedule result");
        }
        if (valid.size() > 1) {
            throw new HarnessException(
                    "HARNESS_RESULT_AMBIGUOUS",
                    "The current UT produced multiple parseable schedule results; refusing to guess: "
                            + valid.stream().map(item -> item.path().toString()).toList());
        }
        ParsedCandidate<T> selected = valid.getFirst();
        Path captured = copyAtomically(selected.path(), destination);
        return new CapturedScheduleResult<>(
                selected.path(), captured, selected.sizeBytes(), selected.snapshot());
    }

    private static Path copyAtomically(Path source, Path destination) throws HarnessException {
        Path normalized = destination.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            throw new HarnessException(
                    "HARNESS_RESULT_COPY_FAILED",
                    "The capture target already exists and cannot be overwritten: " + normalized);
        }
        Path parent = normalized.getParent();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".gantt-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, normalized);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return normalized;
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_RESULT_COPY_FAILED",
                    "Failed to copy the schedule result into the immutable Run directory: " + normalized,
                    exception);
        }
    }

    private record ParsedCandidate<T>(Path path, long sizeBytes, T snapshot) {
    }
}
