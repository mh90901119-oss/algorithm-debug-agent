package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * 通过同目录临时文件原子创建终态控制文档，并禁止覆盖已有文档。
 *
 * <p>不支持原子移动的文件系统会明确失败；调用方不得将此组件用于覆盖或更新操作。</p>
 */
public final class AtomicDocumentWriter {

    private final MoveOperation moveOperation;

    /** 使用底层文件系统的原子移动操作。 */
    public AtomicDocumentWriter() {
        this((source, target) -> Files.move(source, target, ATOMIC_MOVE));
    }

    AtomicDocumentWriter(MoveOperation moveOperation) {
        if (moveOperation == null) {
            throw new IllegalArgumentException("moveOperation must not be null");
        }
        this.moveOperation = moveOperation;
    }

    /**
     * 原子创建一个新文档。
     *
     * @param target 终态文档路径，其父目录必须已存在
     * @param content 完整文档字节
     * @throws WorkspaceException 目标已存在、写入失败、原子移动失败或临时文件清理失败
     */
    public void writeNew(Path target, byte[] content) {
        if (target == null || content == null) {
            throw new IllegalArgumentException("target and content must not be null");
        }
        writeNew(target, Math.max(1L, content.length), output -> output.write(content));
    }

    /**
     * 以流式、硬字节上限方式原子创建大型 Artifact，不在内存中组装完整文档。
     *
     * @param target 终态 Artifact 路径
     * @param maximumBytes 流式写入硬上限
     * @param contentWriter 将内容写入给定流的回调
     */
    void writeNew(Path target, long maximumBytes, StreamContentWriter contentWriter) {
        if (target == null || contentWriter == null || maximumBytes < 1) {
            throw new IllegalArgumentException("target, maximumBytes and contentWriter is invalid");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "The document parent path does not exist or is not a directory: " + parent);
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            FileAlreadyExistsException cause = new FileAlreadyExistsException(normalizedTarget.toString());
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Refusing to overwrite an existing Workspace document: " + normalizedTarget, cause);
        }

        Path temporary = null;
        Throwable primaryFailure = null;
        try {
            temporary = Files.createTempFile(parent, "." + normalizedTarget.getFileName() + "-", ".tmp");
            writeAndFlush(temporary, maximumBytes, contentWriter);
            moveOperation.move(temporary, normalizedTarget);
            temporary = null;
        } catch (IOException | SecurityException failure) {
            primaryFailure = failure;
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Failed to atomically create Workspace document: " + normalizedTarget, failure);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (temporary != null) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    /** 创建缺失的父目录并原子写入；写入失败时只清理本次创建且仍为空的目录。 */
    void writeNewWithParents(Path target, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        writeNewWithParents(target, Math.max(1L, content.length), output -> output.write(content));
    }

    /** 流式版本的父目录托管写入。 */
    void writeNewWithParents(
            Path target, long maximumBytes, StreamContentWriter contentWriter) {
        if (target == null || contentWriter == null || maximumBytes < 1) {
            throw new IllegalArgumentException("target, maximumBytes and contentWriter is invalid");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new WorkspaceException("WORKSPACE_PATH_INVALID", "Document parent path is missing");
        }
        List<Path> created = missingDirectories(parent);
        Throwable primaryFailure = null;
        try {
            Files.createDirectories(parent);
            writeNew(target, maximumBytes, contentWriter);
        } catch (IOException | SecurityException failure) {
            primaryFailure = failure;
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Failed to create Workspace document parent", failure);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (primaryFailure != null) {
                cleanupCreatedDirectories(created, primaryFailure);
            }
        }
    }

    /** 通过同目录临时文件原子替换一个已存在的控制文档。 */
    public void replace(Path target, byte[] content) {
        if (target == null || content == null) {
            throw new IllegalArgumentException("target and content must not be null");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "Document to replace does not exist or is not a regular file: " + normalizedTarget);
        }
        Path temporary = null;
        Throwable primaryFailure = null;
        try {
            temporary = Files.createTempFile(parent, "." + normalizedTarget.getFileName() + "-", ".tmp");
            writeAndFlush(temporary, Math.max(1L, content.length), output -> output.write(content));
            Files.move(temporary, normalizedTarget, ATOMIC_MOVE, REPLACE_EXISTING);
            temporary = null;
        } catch (IOException | SecurityException failure) {
            primaryFailure = failure;
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Failed to atomically replace Workspace document: " + normalizedTarget, failure);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (temporary != null) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    private static void writeAndFlush(
            Path temporary, long maximumBytes, StreamContentWriter contentWriter) throws IOException {
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream output = new BoundedChannelOutputStream(channel, maximumBytes);
            contentWriter.write(output);
            output.flush();
            channel.force(true);
        }
    }

    private static void cleanupTemporary(Path temporary, Throwable primaryFailure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | SecurityException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Failed to clean up temporary Workspace document: " + temporary, cleanupFailure);
        }
    }

    private static List<Path> missingDirectories(Path parent) {
        ArrayList<Path> result = new ArrayList<>();
        for (Path candidate = parent;
                candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
                candidate = candidate.getParent()) {
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static void cleanupCreatedDirectories(List<Path> directories, Throwable primaryFailure) {
        for (Path directory : directories) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException ignored) {
                return;
            } catch (IOException | SecurityException cleanupFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
        }
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface StreamContentWriter {
        void write(OutputStream output) throws IOException;
    }

    private static final class BoundedChannelOutputStream extends OutputStream {
        private final FileChannel channel;
        private final long maximumBytes;
        private long written;

        private BoundedChannelOutputStream(FileChannel channel, long maximumBytes) {
            this.channel = channel;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            if (written + length > maximumBytes) {
                throw new IOException("Streaming Artifact exceeds maximum byte count " + maximumBytes);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            written += length;
        }
    }
}
