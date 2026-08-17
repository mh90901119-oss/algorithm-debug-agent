package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** 新建或显式续接一次 Case Analysis 所需的确定性输入。 */
public record CaseSessionRequest(
        Optional<CaseId> caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String question,
        Path moduleRoot,
        Path repositoryRoot,
        String repositoryRevision,
        String javaVersion,
        String adapterId,
        String adapterVersion,
        ContextInputProbe input) {

    /** 校验请求字段，不执行文件扫描或 Maven。 */
    public CaseSessionRequest {
        if (caseId == null || projectId == null || targetTest == null || question == null
                || moduleRoot == null || repositoryRoot == null || repositoryRevision == null
                || javaVersion == null || adapterId == null || adapterVersion == null || input == null) {
            throw new IllegalArgumentException("CaseSessionRequest 字段不能为空");
        }
        question = question.strip();
        if (question.isEmpty() || question.length() > 65_536) {
            throw new IllegalArgumentException("question 不能为空且不能超过 65536");
        }
        moduleRoot = moduleRoot.toAbsolutePath().normalize();
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(moduleRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("moduleRoot 和 repositoryRoot 必须是普通目录");
        }
    }
}
