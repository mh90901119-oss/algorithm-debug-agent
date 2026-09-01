package org.example.algorithmdebug.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 持续排空单个进程输出流，并只归档预算内字节。 */
public final class BoundedOutputCapture {

    private static final int BUFFER_SIZE = 8192;

    /**
     * @param input 待排空的进程流
     * @param destination 不可覆盖日志路径
     * @param maximumBytes 最大归档字节数
     * @return 日志归档统计
     * @throws HarnessException 无法创建或写入日志
     */
    public RunLog capture(InputStream input, Path destination, long maximumBytes)
            throws HarnessException {
        if (input == null || destination == null || maximumBytes <= 0) {
            throw new IllegalArgumentException("input, destination and maximumBytes must be valid");
        }
        Path normalized = prepare(destination);
        return capturePrepared(input, normalized, maximumBytes);
    }

    /** 在目标进程启动前原子创建空日志，确保冲突不会产生外部副作用。 */
    Path prepare(Path destination) throws HarnessException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        Path normalized = destination.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized.getParent());
            Files.newOutputStream(normalized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
            return normalized;
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_LOG_OPEN_FAILED",
                    "Failed to create non-overwriting log: " + normalized,
                    exception);
        }
    }

    /** 排空到已经由 {@link #prepare(Path)} 创建的空日志。 */
    RunLog capturePrepared(InputStream input, Path destination, long maximumBytes)
            throws HarnessException {
        return capturePrepared(input, destination, maximumBytes, (bytes, offset, length) -> { });
    }

    /** 排空日志并把每个原始字节块同步通知给有界观察者。 */
    RunLog capturePrepared(
            InputStream input,
            Path destination,
            long maximumBytes,
            OutputChunkObserver observer) throws HarnessException {
        if (input == null || destination == null || maximumBytes <= 0) {
            throw new IllegalArgumentException("input, destination and maximumBytes must be valid");
        }
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        Path normalized = destination.toAbsolutePath().normalize();
        OutputStream output;
        try {
            output = Files.newOutputStream(normalized, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_LOG_OPEN_FAILED",
                    "Failed to open prepared log: " + normalized,
                    exception);
        }

        long captured = 0;
        long discarded = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (input; output) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                observer.accept(buffer, 0, read);
                int writable = (int) Math.min(read, maximumBytes - captured);
                if (writable > 0) {
                    output.write(buffer, 0, writable);
                    captured += writable;
                }
                discarded += read - writable;
            }
            output.flush();
            return new RunLog(normalized, captured, discarded, discarded > 0);
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_LOG_CAPTURE_FAILED",
                    "Failed to fully drain and archive process log: " + normalized,
                    exception);
        }
    }
}
