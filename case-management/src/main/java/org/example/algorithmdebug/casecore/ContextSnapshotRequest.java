package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;

/** Context Builder 的显式、可测试输入。 */
public record ContextSnapshotRequest(
        CaseId caseId,
        ContextId contextId,
        ProjectId projectId,
        TargetTest targetTest,
        Path moduleRoot,
        Path repositoryRoot,
        String repositoryRevision,
        String javaVersion,
        String adapterId,
        String adapterVersion,
        ContextInputProbe input,
        Instant createdAt) {

    /** 校验 ID、路径和 Adapter 元数据，且不扫描文件。 */
    public ContextSnapshotRequest {
        if (caseId == null || contextId == null || projectId == null || targetTest == null
                || moduleRoot == null || repositoryRoot == null || repositoryRevision == null
                || javaVersion == null || adapterId == null || adapterVersion == null
                || input == null || createdAt == null) {
            throw new IllegalArgumentException("ContextSnapshotRequest 字段不能为空");
        }
        moduleRoot = moduleRoot.toAbsolutePath().normalize();
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(moduleRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("moduleRoot 和 repositoryRoot 必须是普通目录");
        }
        if (!moduleRoot.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("moduleRoot 必须位于 repositoryRoot 内");
        }
        repositoryRevision = requireText(repositoryRevision, "repositoryRevision", 512);
        javaVersion = requireText(javaVersion, "javaVersion", 256);
        adapterId = requireText(adapterId, "adapterId", 128);
        adapterVersion = requireText(adapterVersion, "adapterVersion", 256);
    }

    /** 返回只替换输入定位事实的新请求。 */
    public ContextSnapshotRequest withInput(ContextInputProbe replacement) {
        return new ContextSnapshotRequest(
                caseId, contextId, projectId, targetTest, moduleRoot, repositoryRoot,
                repositoryRevision, javaVersion, adapterId, adapterVersion,
                replacement, createdAt);
    }

    private static String requireText(String value, String field, int maximum) {
        String checked = value.strip();
        if (checked.isEmpty() || checked.length() > maximum) {
            throw new IllegalArgumentException(field + " 不能为空且不能超过 " + maximum);
        }
        return checked;
    }
}
