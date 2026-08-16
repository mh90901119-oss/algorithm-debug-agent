package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

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
            throw new IllegalArgumentException("moveOperation 不能为空");
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
            throw new IllegalArgumentException("target 和 content 不能为空");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("文档父目录不存在或不是普通目录: " + parent);
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            FileAlreadyExistsException cause = new FileAlreadyExistsException(normalizedTarget.toString());
            throw new WorkspaceException("拒绝覆盖已有 Workspace 文档: " + normalizedTarget, cause);
        }

        Path temporary = null;
        Throwable primaryFailure = null;
        try {
            temporary = Files.createTempFile(parent, "." + normalizedTarget.getFileName() + "-", ".tmp");
            writeAndFlush(temporary, content);
            moveOperation.move(temporary, normalizedTarget);
            temporary = null;
        } catch (IOException failure) {
            primaryFailure = failure;
            throw new WorkspaceException("原子创建 Workspace 文档失败: " + normalizedTarget, failure);
        } finally {
            if (temporary != null) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    private static void writeAndFlush(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void cleanupTemporary(Path temporary, Throwable primaryFailure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw new WorkspaceException("清理 Workspace 临时文档失败: " + temporary, cleanupFailure);
        }
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }
}
