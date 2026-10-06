package org.example.algorithmdebug.casecore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;

/** 对单个 Analysis 的 Investigation Event 执行带哈希的严格顺序原子追加。 */
public final class InvestigationEventArchive {
    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final InvestigationJournalReader reader;

    /**
     * @param schemaVersion 归档信封版本
     * @param contentSha256 event 规范 JSON 的 SHA-256
     * @param event 不可变调查事件
     */
    public record ArchivedEvent(
            String schemaVersion, String contentSha256, InvestigationEvent event) {
        public ArchivedEvent {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
            event = ControlArchiveSupport.notNull(event, "event");
        }
    }

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界 JSON Mapper
     * @param writer 原子 create-new Writer
     */
    public InvestigationEventArchive(
            Path casesRoot, BoundedDocumentMapper mapper, AtomicDocumentWriter writer) {
        Path root = ControlArchiveSupport.notNull(casesRoot, "casesRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("casesRoot must be an existing regular directory");
        }
        this.casesRoot = root;
        this.mapper = ControlArchiveSupport.notNull(mapper, "mapper");
        this.writer = ControlArchiveSupport.notNull(writer, "writer");
        this.reader = new InvestigationJournalReader(root, mapper);
    }

    /**
     * 原子追加事件；序号必须恰好等于当前最大连续序号加一，历史存在缺号时拒绝继续写入。
     *
     * @return 新事件最终路径
     */
    public Path appendEvent(AnalysisIdentity identity, InvestigationEvent event) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        InvestigationEvent checkedEvent = ControlArchiveSupport.notNull(event, "event");
        if (!checkedIdentity.caseId().equals(checkedEvent.caseId())
                || !checkedIdentity.analysisId().equals(checkedEvent.analysisId())) {
            throw new WorkspaceException(
                    ControlArchiveSupport.INVESTIGATION_IDENTITY_MISMATCH,
                    "Investigation event does not belong to the requested Analysis");
        }

        InvestigationJournalReader.Result current = reader.readValidatedEvents(checkedIdentity);
        if (!current.limitations().isEmpty()) {
            throw ControlArchiveSupport.investigationConflict(
                    "Cannot append to an Investigation journal containing sequence gaps");
        }
        long expectedSequence = current.events().size() + 1L;
        if (checkedEvent.sequence() != expectedSequence) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation sequence must be " + expectedSequence
                            + " but was " + checkedEvent.sequence());
        }
        if (current.events().stream().anyMatch(
                existing -> existing.eventId().equals(checkedEvent.eventId()))) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation eventId already exists: " + checkedEvent.eventId());
        }

        ArchivedEvent document = new ArchivedEvent(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION,
                hash(checkedEvent),
                checkedEvent);
        Path path = layout(checkedIdentity).investigationEvent(
                checkedIdentity.analysisId(), checkedEvent.sequence(), checkedEvent.eventId());
        try {
            writer.writeNewWithParents(path, mapper.writeJson(document));
            return path;
        } catch (WorkspaceException failure) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event could not be appended", failure);
        }
    }

    String hash(InvestigationEvent event) {
        return ControlArchiveSupport.digest(mapper.writeJson(event));
    }

    private CaseArchiveLayout layout(AnalysisIdentity identity) {
        return CaseArchiveLayout.of(casesRoot, identity.caseId());
    }
}
