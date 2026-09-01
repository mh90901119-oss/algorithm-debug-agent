package org.example.algorithmdebug.validator;

import org.example.algorithmdebug.casecore.ArtifactIntegrityChecker;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.ValidationFinding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 对本地只读产物执行普通文件、字节数和 SHA-256 校验。 */
public final class ArtifactIntegrityVerifier {

    private final ArtifactIntegrityChecker checker = new ArtifactIntegrityChecker();

    /**
     * @param reference 归档时生成的不可变引用
     * @param path 当前 Case 内的实际路径
     * @return 空列表表示完整匹配，否则返回一个稳定的 INVALID Finding
     */
    public List<ValidationFinding> verify(ArtifactReference reference, Path path) {
        ArtifactIntegrityChecker.Status status = checker.verify(reference, path).status();
        return switch (status) {
            case VALID -> List.of();
            case MISSING -> finding("ARTIFACT_MISSING", "The archived artifact does not exist", reference);
            case NOT_REGULAR -> finding("ARTIFACT_NOT_REGULAR", "The archived artifact is not a regular file", reference);
            case SIZE_MISMATCH -> finding(
                    "ARTIFACT_SIZE_MISMATCH", "The archived artifact byte count does not match its reference", reference);
            case HASH_MISMATCH -> finding(
                    "ARTIFACT_HASH_MISMATCH", "The archived artifact SHA-256 does not match its reference", reference);
            case READ_FAILED -> finding("ARTIFACT_READ_FAILED", "Failed to read the archived artifact", reference);
        };
    }

    /** 流式计算普通文件 SHA-256，不把整个产物加载到内存。 */
    public String sha256(Path path) throws IOException {
        return checker.sha256(path);
    }

    private static List<ValidationFinding> finding(
            String code,
            String detail,
            ArtifactReference reference) {
        return List.of(new ValidationFinding(
                code, EvidenceValidationStatus.INVALID, detail,
                List.of(reference), Optional.empty()));
    }
}
