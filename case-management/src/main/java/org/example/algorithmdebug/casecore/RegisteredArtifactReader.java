package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ArtifactTextExcerpt;
import org.example.algorithmdebug.contracts.CaseId;

/** 按 Case 内注册 ID 校验完整性并读取有界 UTF-8 文本片段。 */
public final class RegisteredArtifactReader {
    /** 单次返回的最大原始字节预算。 */
    public static final int MAX_EXCERPT_BYTES = 65_536;

    private final CaseArchiveRepository repository;
    private final CaseArtifactAccess access;

    /** @param repository Case 归档与 Artifact 注册入口 */
    public RegisteredArtifactReader(CaseArchiveRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        this.repository = repository;
        this.access = new CaseArtifactAccess(repository.casesRoot());
    }

    /** 按字节偏移读取下一个严格 UTF-8 片段，并返回可继续使用的边界偏移。 */
    public ArtifactTextExcerpt read(
            CaseId caseId, String artifactId, long offsetBytes, int maxBytes) {
        if (caseId == null || artifactId == null || artifactId.isBlank()
                || artifactId.contains("/") || artifactId.contains("\\")
                || artifactId.contains(":") || offsetBytes < 0
                || maxBytes < 1 || maxBytes > MAX_EXCERPT_BYTES) {
            throw new IllegalArgumentException("Artifact 读取参数非法");
        }
        ArtifactReference registered = repository.requireArtifactRegistration(
                caseId, artifactId).artifact();
        if (offsetBytes > registered.sizeBytes()) {
            throw new WorkspaceException("CASE_ARTIFACT_OFFSET_INVALID", "Artifact 偏移超过文件大小");
        }
        java.nio.file.Path file = access.requireVerifiedArtifact(caseId, registered);
        int requested = (int) Math.min((long) maxBytes, registered.sizeBytes() - offsetBytes);
        ByteBuffer bytes = ByteBuffer.allocate(requested);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(offsetBytes);
            while (bytes.hasRemaining()) {
                int read = channel.read(bytes);
                if (read <= 0) {
                    break;
                }
            }
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_ARTIFACT_READ_FAILED", "无法读取 Artifact", failure);
        }
        bytes.flip();
        boolean endOfInput = offsetBytes + requested >= registered.sizeBytes();
        CharBuffer chars = CharBuffer.allocate(Math.max(1, requested));
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            var result = decoder.decode(bytes, chars, endOfInput);
            if (result.isError()) result.throwException();
            if (endOfInput) {
                result = decoder.flush(chars);
                if (result.isError()) result.throwException();
            }
        } catch (CharacterCodingException failure) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_NOT_UTF8", "Artifact 不是可读取的 UTF-8 文本", failure);
        }
        int consumed = bytes.position();
        if (requested > 0 && consumed == 0) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_BUDGET_TOO_SMALL", "读取预算不足以容纳一个 UTF-8 字符");
        }
        chars.flip();
        long next = offsetBytes + consumed;
        return new ArtifactTextExcerpt(
                registered, offsetBytes, next, next < registered.sizeBytes(), chars.toString());
    }
}
