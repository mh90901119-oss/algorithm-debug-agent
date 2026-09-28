package org.example.algorithmdebug.casecore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;

/** 以带输入哈希和 provenance 的不可变信封归档 Coordinator policy 决策。 */
public final class CoordinationDecisionArchive {
    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /**
     * @param schemaVersion 归档信封版本
     * @param decisionId 决策 ID
     * @param inputSha256 触发决策的规范化输入哈希
     * @param provenance 决策输入来源引用
     * @param decidedAt 决策时间
     * @param contentSha256 除本字段外完整载荷的哈希
     * @param decision typed policy 决策
     */
    public record ArchivedDecision(
            String schemaVersion,
            String decisionId,
            String inputSha256,
            List<String> provenance,
            Instant decidedAt,
            String contentSha256,
            ActionDecision decision) {
        public ArchivedDecision {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            decisionId = ControlArchiveSupport.id(decisionId, "decisionId");
            inputSha256 = ControlArchiveSupport.sha256(inputSha256, "inputSha256");
            provenance = ControlArchiveSupport.provenance(provenance);
            decidedAt = ControlArchiveSupport.notNull(decidedAt, "decidedAt");
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
            decision = ControlArchiveSupport.notNull(decision, "decision");
        }
    }

    private record DecisionPayload(
            String schemaVersion,
            String decisionId,
            String inputSha256,
            List<String> provenance,
            Instant decidedAt,
            ActionDecision decision) {
    }

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界 JSON Mapper
     * @param writer 原子 create-new Writer
     */
    public CoordinationDecisionArchive(
            Path casesRoot, BoundedDocumentMapper mapper, AtomicDocumentWriter writer) {
        Path root = ControlArchiveSupport.notNull(casesRoot, "casesRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("casesRoot must be an existing regular directory");
        }
        this.casesRoot = root;
        this.mapper = ControlArchiveSupport.notNull(mapper, "mapper");
        this.writer = ControlArchiveSupport.notNull(writer, "writer");
    }

    /** 原子追加一条可独立审计的协调决策。 */
    public Path appendDecision(
            String decisionId,
            String inputSha256,
            List<String> provenance,
            Instant decidedAt,
            ActionDecision decision) {
        ActionDecision checkedDecision = ControlArchiveSupport.notNull(decision, "decision");
        DecisionPayload payload = new DecisionPayload(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION,
                ControlArchiveSupport.id(decisionId, "decisionId"),
                ControlArchiveSupport.sha256(inputSha256, "inputSha256"),
                ControlArchiveSupport.provenance(provenance),
                ControlArchiveSupport.notNull(decidedAt, "decidedAt"),
                checkedDecision);
        ArchivedDecision document = new ArchivedDecision(
                payload.schemaVersion(), payload.decisionId(), payload.inputSha256(),
                payload.provenance(), payload.decidedAt(), hash(payload), payload.decision());
        Path path = layout(checkedDecision.identity()).coordinationDecision(
                checkedDecision.identity().analysisId(), payload.decisionId());
        writer.writeNewWithParents(path, mapper.writeJson(document));
        return path;
    }

    /** 读取并验证决策身份和内容哈希。 */
    public ArchivedDecision requireDecision(AnalysisIdentity identity, String decisionId) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        String checkedDecisionId = ControlArchiveSupport.id(decisionId, "decisionId");
        Path path = layout(checkedIdentity).coordinationDecision(
                checkedIdentity.analysisId(), checkedDecisionId);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new WorkspaceException(
                        ControlArchiveSupport.COORDINATION_DECISION_INVALID,
                        "Decision is missing or not regular");
            }
            ArchivedDecision document = mapper.readJson(path, ArchivedDecision.class);
            DecisionPayload payload = new DecisionPayload(
                    document.schemaVersion(), document.decisionId(), document.inputSha256(),
                    document.provenance(), document.decidedAt(), document.decision());
            if (!checkedDecisionId.equals(document.decisionId())
                    || !checkedIdentity.equals(document.decision().identity())
                    || !document.contentSha256().equals(hash(payload))) {
                throw new WorkspaceException(
                        ControlArchiveSupport.COORDINATION_DECISION_INVALID,
                        "Decision identity or content hash mismatch");
            }
            return document;
        } catch (WorkspaceException failure) {
            if (ControlArchiveSupport.COORDINATION_DECISION_INVALID.equals(failure.code())) {
                throw failure;
            }
            throw new WorkspaceException(
                    ControlArchiveSupport.COORDINATION_DECISION_INVALID,
                    "Decision document is unreadable", failure);
        }
    }

    private String hash(DecisionPayload payload) {
        return ControlArchiveSupport.digest(mapper.writeJson(payload));
    }

    private CaseArchiveLayout layout(AnalysisIdentity identity) {
        return CaseArchiveLayout.of(casesRoot, identity.caseId());
    }
}
