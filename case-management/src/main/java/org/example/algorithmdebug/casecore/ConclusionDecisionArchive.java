package org.example.algorithmdebug.casecore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.coordination.CoordinationLimits;

/** 原子追加结论候选和唯一接受/拒绝终态，并校验哈希、身份与单终态约束。 */
public final class ConclusionDecisionArchive {
    private static final String CONCLUSION_ARCHIVE_CONFLICT = "CONCLUSION_ARCHIVE_CONFLICT";
    private static final String CONCLUSION_ARCHIVE_INVALID = "CONCLUSION_ARCHIVE_INVALID";

    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /** 带输入哈希和来源的不可变候选信封。 */
    public record ArchivedCandidate(
            String schemaVersion,
            String policyVersion,
            String inputSha256,
            List<String> provenance,
            Instant archivedAt,
            String contentSha256,
            ConclusionCandidate candidate) {
        /** 校验候选信封字段。 */
        public ArchivedCandidate {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            policyVersion = ControlArchiveSupport.text(
                    policyVersion, "policyVersion",
                    CoordinationLimits.MAX_POLICY_VERSION_LENGTH);
            inputSha256 = ControlArchiveSupport.sha256(inputSha256, "inputSha256");
            provenance = ControlArchiveSupport.provenance(provenance);
            archivedAt = ControlArchiveSupport.notNull(archivedAt, "archivedAt");
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
            candidate = ControlArchiveSupport.notNull(candidate, "candidate");
        }
    }

    /** 带候选哈希和来源的不可变结论终态信封。 */
    public record ArchivedConclusionDecision(
            String schemaVersion,
            String policyVersion,
            String candidateSha256,
            List<String> provenance,
            Instant decidedAt,
            String contentSha256,
            ConclusionDecision decision) {
        /** 校验终态信封字段。 */
        public ArchivedConclusionDecision {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            policyVersion = ControlArchiveSupport.text(
                    policyVersion, "policyVersion",
                    CoordinationLimits.MAX_POLICY_VERSION_LENGTH);
            candidateSha256 = ControlArchiveSupport.sha256(
                    candidateSha256, "candidateSha256");
            provenance = ControlArchiveSupport.provenance(provenance);
            decidedAt = ControlArchiveSupport.notNull(decidedAt, "decidedAt");
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
            decision = ControlArchiveSupport.notNull(decision, "decision");
        }
    }

    private record CandidatePayload(
            String schemaVersion,
            String policyVersion,
            String inputSha256,
            List<String> provenance,
            Instant archivedAt,
            ConclusionCandidate candidate) {
    }

    private record DecisionPayload(
            String schemaVersion,
            String policyVersion,
            String candidateSha256,
            List<String> provenance,
            Instant decidedAt,
            ConclusionDecision decision) {
    }

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界 JSON Mapper
     * @param writer 原子 create-new Writer
     */
    public ConclusionDecisionArchive(
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

    /** 先原子追加完整候选；相同候选精确重放返回既有路径。 */
    public Path appendCandidate(
            ConclusionCandidate candidate,
            String policyVersion,
            List<String> provenance,
            Instant archivedAt) {
        ConclusionCandidate checked = ControlArchiveSupport.notNull(candidate, "candidate");
        String checkedPolicy = ControlArchiveSupport.text(
                policyVersion, "policyVersion",
                CoordinationLimits.MAX_POLICY_VERSION_LENGTH);
        List<String> checkedProvenance = ControlArchiveSupport.provenance(provenance);
        Instant checkedTime = ControlArchiveSupport.notNull(archivedAt, "archivedAt");
        String inputHash = ControlArchiveSupport.digest(mapper.writeJson(checked));
        CandidatePayload payload = new CandidatePayload(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION, checkedPolicy, inputHash,
                checkedProvenance, checkedTime, checked);
        ArchivedCandidate document = new ArchivedCandidate(
                payload.schemaVersion(), payload.policyVersion(), payload.inputSha256(),
                payload.provenance(), payload.archivedAt(), hash(payload), payload.candidate());
        Path path = layout(checked.identity()).conclusionCandidate(
                checked.identity().analysisId(), checked.conclusionId());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            ArchivedCandidate existing = requireCandidate(
                    checked.identity(), checked.conclusionId());
            if (existing.candidate().equals(checked)
                    && existing.policyVersion().equals(checkedPolicy)
                    && existing.provenance().equals(checkedProvenance)) {
                return path;
            }
            throw conflict("Conclusion candidate identity already contains different content");
        }
        writer.writeNewWithParents(path, mapper.writeJson(document));
        return path;
    }

    /** 在候选存在后追加唯一 accepted 或 rejected 终态。 */
    public Path appendDecision(
            ConclusionDecision decision,
            List<String> provenance,
            Instant decidedAt) {
        ConclusionDecision checked = ControlArchiveSupport.notNull(decision, "decision");
        ArchivedCandidate candidate = requireCandidate(
                checked.identity(), checked.conclusionId());
        if (!candidate.policyVersion().equals(checked.policyVersion())) {
            throw conflict("Conclusion candidate and decision policy versions differ");
        }
        List<String> checkedProvenance = ControlArchiveSupport.provenance(provenance);
        Instant checkedTime = ControlArchiveSupport.notNull(decidedAt, "decidedAt");
        DecisionPayload payload = new DecisionPayload(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION, checked.policyVersion(),
                candidate.inputSha256(), checkedProvenance, checkedTime, checked);
        ArchivedConclusionDecision document = new ArchivedConclusionDecision(
                payload.schemaVersion(), payload.policyVersion(), payload.candidateSha256(),
                payload.provenance(), payload.decidedAt(), hash(payload), payload.decision());
        CaseArchiveLayout layout = layout(checked.identity());
        Path selected = terminalPath(layout, checked);
        Path opposite = oppositeTerminalPath(layout, checked);
        if (Files.exists(opposite, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("Conclusion already has the opposite terminal decision");
        }
        if (Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) {
            ArchivedConclusionDecision existing = requireDecision(
                    checked.identity(), checked.conclusionId());
            if (existing.decision().equals(checked)
                    && existing.provenance().equals(checkedProvenance)) {
                return selected;
            }
            throw conflict("Conclusion terminal decision already contains different content");
        }
        writer.writeNewWithParents(selected, mapper.writeJson(document));
        return selected;
    }

    /** 读取并校验候选身份、输入哈希和信封内容哈希。 */
    public ArchivedCandidate requireCandidate(
            AnalysisIdentity identity, String conclusionId) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        String checkedId = ControlArchiveSupport.id(conclusionId, "conclusionId");
        Path path = layout(checkedIdentity).conclusionCandidate(
                checkedIdentity.analysisId(), checkedId);
        try {
            requireRegular(path, "Conclusion candidate is missing or not regular");
            ArchivedCandidate document = mapper.readJson(path, ArchivedCandidate.class);
            CandidatePayload payload = new CandidatePayload(
                    document.schemaVersion(), document.policyVersion(), document.inputSha256(),
                    document.provenance(), document.archivedAt(), document.candidate());
            String inputHash = ControlArchiveSupport.digest(
                    mapper.writeJson(document.candidate()));
            if (!checkedId.equals(document.candidate().conclusionId())
                    || !checkedIdentity.equals(document.candidate().identity())
                    || !inputHash.equals(document.inputSha256())
                    || !hash(payload).equals(document.contentSha256())) {
                throw invalid("Conclusion candidate identity or hash mismatch");
            }
            return document;
        } catch (WorkspaceException failure) {
            if (CONCLUSION_ARCHIVE_INVALID.equals(failure.code())) {
                throw failure;
            }
            throw invalid("Conclusion candidate is unreadable", failure);
        }
    }

    /** 读取唯一终态并校验其候选关联、身份和内容哈希。 */
    public ArchivedConclusionDecision requireDecision(
            AnalysisIdentity identity, String conclusionId) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        String checkedId = ControlArchiveSupport.id(conclusionId, "conclusionId");
        CaseArchiveLayout layout = layout(checkedIdentity);
        Path accepted = layout.conclusionAccepted(checkedIdentity.analysisId(), checkedId);
        Path rejected = layout.conclusionRejected(checkedIdentity.analysisId(), checkedId);
        boolean hasAccepted = Files.exists(accepted, LinkOption.NOFOLLOW_LINKS);
        boolean hasRejected = Files.exists(rejected, LinkOption.NOFOLLOW_LINKS);
        if (hasAccepted == hasRejected) {
            throw invalid("Conclusion must have exactly one terminal decision");
        }
        Path path = hasAccepted ? accepted : rejected;
        try {
            requireRegular(path, "Conclusion decision is not regular");
            ArchivedConclusionDecision document = mapper.readJson(
                    path, ArchivedConclusionDecision.class);
            DecisionPayload payload = new DecisionPayload(
                    document.schemaVersion(), document.policyVersion(),
                    document.candidateSha256(), document.provenance(), document.decidedAt(),
                    document.decision());
            ArchivedCandidate candidate = requireCandidate(checkedIdentity, checkedId);
            if (!checkedId.equals(document.decision().conclusionId())
                    || !checkedIdentity.equals(document.decision().identity())
                    || !candidate.inputSha256().equals(document.candidateSha256())
                    || !hash(payload).equals(document.contentSha256())
                    || (hasAccepted
                    != (document.decision().decision() == ActionDecisionCode.ALLOWED))) {
                throw invalid("Conclusion decision identity or hash mismatch");
            }
            return document;
        } catch (WorkspaceException failure) {
            if (CONCLUSION_ARCHIVE_INVALID.equals(failure.code())) {
                throw failure;
            }
            throw invalid("Conclusion decision is unreadable", failure);
        }
    }

    private static void requireRegular(Path path, String message) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw invalid(message);
        }
    }

    private static Path terminalPath(
            CaseArchiveLayout layout, ConclusionDecision decision) {
        return decision.decision() == ActionDecisionCode.ALLOWED
                ? layout.conclusionAccepted(
                decision.identity().analysisId(), decision.conclusionId())
                : layout.conclusionRejected(
                decision.identity().analysisId(), decision.conclusionId());
    }

    private static Path oppositeTerminalPath(
            CaseArchiveLayout layout, ConclusionDecision decision) {
        return decision.decision() == ActionDecisionCode.ALLOWED
                ? layout.conclusionRejected(
                decision.identity().analysisId(), decision.conclusionId())
                : layout.conclusionAccepted(
                decision.identity().analysisId(), decision.conclusionId());
    }

    private String hash(Object payload) {
        return ControlArchiveSupport.digest(mapper.writeJson(payload));
    }

    private CaseArchiveLayout layout(AnalysisIdentity identity) {
        return CaseArchiveLayout.of(casesRoot, identity.caseId());
    }

    private static WorkspaceException conflict(String message) {
        return new WorkspaceException(CONCLUSION_ARCHIVE_CONFLICT, message);
    }

    private static WorkspaceException invalid(String message) {
        return new WorkspaceException(CONCLUSION_ARCHIVE_INVALID, message);
    }

    private static WorkspaceException invalid(String message, Throwable cause) {
        return new WorkspaceException(CONCLUSION_ARCHIVE_INVALID, message, cause);
    }
}
