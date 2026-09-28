package org.example.algorithmdebug.casecore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;

/**
 * 以 create-once 文档保存幂等操作的启动和唯一终态，不通过覆盖表达状态迁移。
 *
 * <p>跨进程目标互斥由 Task 6 的 OS 锁负责；本组件在持久化边界对已有冲突 fail closed。</p>
 */
public final class OperationJournal {
    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /** 操作终态对应的固定文件类型。 */
    public enum TerminalKind { COMPLETED, FAILED, UNCERTAIN }

    /**
     * @param schemaVersion 归档信封版本
     * @param operationId 操作 ID
     * @param identity 分析身份
     * @param actionType 动作类型
     * @param inputSha256 规范化输入哈希
     * @param startedAt 启动时间
     * @param contentSha256 除本字段外的启动载荷哈希
     */
    public record StartedDocument(
            String schemaVersion,
            OperationId operationId,
            AnalysisIdentity identity,
            AnalysisActionType actionType,
            String inputSha256,
            Instant startedAt,
            String contentSha256) {
        public StartedDocument {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            operationId = ControlArchiveSupport.notNull(operationId, "operationId");
            identity = ControlArchiveSupport.notNull(identity, "identity");
            actionType = ControlArchiveSupport.notNull(actionType, "actionType");
            inputSha256 = ControlArchiveSupport.sha256(inputSha256, "inputSha256");
            startedAt = ControlArchiveSupport.notNull(startedAt, "startedAt");
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
        }
    }

    /**
     * @param schemaVersion 归档信封版本
     * @param kind 终态文件类型
     * @param receipt 业务回执
     * @param recordedAt 终态记录时间
     * @param contentSha256 除本字段外的终态载荷哈希
     */
    public record TerminalDocument(
            String schemaVersion,
            TerminalKind kind,
            OperationReceipt receipt,
            Instant recordedAt,
            String contentSha256) {
        public TerminalDocument {
            schemaVersion = ControlArchiveSupport.version(schemaVersion);
            kind = ControlArchiveSupport.notNull(kind, "kind");
            receipt = ControlArchiveSupport.notNull(receipt, "receipt");
            recordedAt = ControlArchiveSupport.notNull(recordedAt, "recordedAt");
            contentSha256 = ControlArchiveSupport.sha256(contentSha256, "contentSha256");
        }
    }

    /** @param started 启动文档 @param terminal 唯一可选终态文档 */
    public record State(StartedDocument started, Optional<TerminalDocument> terminal) {
        public State {
            started = ControlArchiveSupport.notNull(started, "started");
            terminal = ControlArchiveSupport.notNull(terminal, "terminal");
        }
    }

    private record StartedPayload(
            String schemaVersion,
            OperationId operationId,
            AnalysisIdentity identity,
            AnalysisActionType actionType,
            String inputSha256,
            Instant startedAt) {
    }

    private record TerminalPayload(
            String schemaVersion,
            TerminalKind kind,
            OperationReceipt receipt,
            Instant recordedAt) {
    }

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界 JSON Mapper
     * @param writer 原子 create-new Writer
     */
    public OperationJournal(
            Path casesRoot, BoundedDocumentMapper mapper, AtomicDocumentWriter writer) {
        this.casesRoot = requireCasesRoot(casesRoot);
        this.mapper = ControlArchiveSupport.notNull(mapper, "mapper");
        this.writer = ControlArchiveSupport.notNull(writer, "writer");
    }

    /** 原子追加操作启动文档。 */
    public Path start(
            AnalysisIdentity identity,
            OperationId operationId,
            AnalysisActionType actionType,
            String inputSha256,
            Instant startedAt) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        OperationId checkedOperationId = ControlArchiveSupport.notNull(operationId, "operationId");
        StartedPayload payload = new StartedPayload(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION,
                checkedOperationId,
                checkedIdentity,
                ControlArchiveSupport.notNull(actionType, "actionType"),
                ControlArchiveSupport.sha256(inputSha256, "inputSha256"),
                ControlArchiveSupport.notNull(startedAt, "startedAt"));
        StartedDocument document = new StartedDocument(
                payload.schemaVersion(), payload.operationId(), payload.identity(), payload.actionType(),
                payload.inputSha256(), payload.startedAt(), hash(payload));
        Path path = layout(checkedIdentity).operationStarted(
                checkedIdentity.analysisId(), checkedOperationId);
        try {
            writer.writeNewWithParents(path, mapper.writeJson(document));
            return path;
        } catch (WorkspaceException failure) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation start already exists or could not be appended: "
                            + checkedOperationId.value(), failure);
        }
    }

    /** 追加成功终态。 */
    public Path complete(OperationReceipt receipt, Instant recordedAt) {
        return appendTerminal(TerminalKind.COMPLETED, receipt, recordedAt);
    }

    /** 追加失败或拒绝终态。 */
    public Path fail(OperationReceipt receipt, Instant recordedAt) {
        return appendTerminal(TerminalKind.FAILED, receipt, recordedAt);
    }

    /** 追加结果不确定终态，禁止调用方据此自动重跑目标 UT。 */
    public Path markUncertain(OperationReceipt receipt, Instant recordedAt) {
        return appendTerminal(TerminalKind.UNCERTAIN, receipt, recordedAt);
    }

    /** 读取并完整校验一个操作生命周期。 */
    public State requireState(AnalysisIdentity identity, OperationId operationId) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        OperationId checkedOperationId = ControlArchiveSupport.notNull(operationId, "operationId");
        CaseArchiveLayout layout = layout(checkedIdentity);
        Path startedPath = layout.operationStarted(checkedIdentity.analysisId(), checkedOperationId);
        if (!Files.isRegularFile(startedPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(startedPath)) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation start is missing: " + checkedOperationId.value());
        }
        StartedDocument started = readStarted(startedPath);
        requireIdentity(checkedIdentity, checkedOperationId, started);

        List<TerminalDocument> terminals = new ArrayList<>();
        readTerminalIfPresent(
                layout.operationCompleted(checkedIdentity.analysisId(), checkedOperationId),
                TerminalKind.COMPLETED, terminals);
        readTerminalIfPresent(
                layout.operationFailed(checkedIdentity.analysisId(), checkedOperationId),
                TerminalKind.FAILED, terminals);
        readTerminalIfPresent(
                layout.operationUncertain(checkedIdentity.analysisId(), checkedOperationId),
                TerminalKind.UNCERTAIN, terminals);
        if (terminals.size() > 1) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation contains more than one terminal document: " + checkedOperationId.value());
        }
        Optional<TerminalDocument> terminal = terminals.stream().findFirst();
        terminal.ifPresent(value -> requireTerminalMatches(started, value));
        return new State(started, terminal);
    }

    private Path appendTerminal(
            TerminalKind kind, OperationReceipt receipt, Instant recordedAt) {
        OperationReceipt checkedReceipt = ControlArchiveSupport.notNull(receipt, "receipt");
        State current = requireState(checkedReceipt.identity(), checkedReceipt.operationId());
        if (current.terminal().isPresent()) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation already has a terminal document: " + checkedReceipt.operationId().value());
        }
        requireReceiptKind(kind, checkedReceipt);
        TerminalPayload payload = new TerminalPayload(
                ControlArchiveSupport.ARCHIVE_SCHEMA_VERSION,
                kind,
                checkedReceipt,
                ControlArchiveSupport.notNull(recordedAt, "recordedAt"));
        TerminalDocument document = new TerminalDocument(
                payload.schemaVersion(), payload.kind(), payload.receipt(),
                payload.recordedAt(), hash(payload));
        requireTerminalMatches(current.started(), document);
        CaseArchiveLayout layout = layout(checkedReceipt.identity());
        Path target = switch (kind) {
            case COMPLETED -> layout.operationCompleted(
                    checkedReceipt.identity().analysisId(), checkedReceipt.operationId());
            case FAILED -> layout.operationFailed(
                    checkedReceipt.identity().analysisId(), checkedReceipt.operationId());
            case UNCERTAIN -> layout.operationUncertain(
                    checkedReceipt.identity().analysisId(), checkedReceipt.operationId());
        };
        try {
            writer.writeNew(target, mapper.writeJson(document));
            return target;
        } catch (WorkspaceException failure) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation terminal could not be appended: "
                            + checkedReceipt.operationId().value(), failure);
        }
    }

    private void requireReceiptKind(TerminalKind kind, OperationReceipt receipt) {
        boolean valid = switch (kind) {
            case COMPLETED -> receipt.outcome() == ActionOutcome.SUCCEEDED
                    && receipt.errorCode().isEmpty();
            case FAILED -> receipt.outcome() != ActionOutcome.SUCCEEDED
                    && receipt.errorCode().filter(code -> code != CoordinationErrorCode.OPERATION_UNCERTAIN)
                    .isPresent();
            case UNCERTAIN -> receipt.outcome() == ActionOutcome.FAILED
                    && receipt.errorCode().filter(
                            code -> code == CoordinationErrorCode.OPERATION_UNCERTAIN).isPresent();
        };
        if (!valid || receipt.replayed()) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation receipt does not match terminal kind " + kind);
        }
    }

    private StartedDocument readStarted(Path path) {
        try {
            StartedDocument document = mapper.readJson(path, StartedDocument.class);
            StartedPayload payload = new StartedPayload(
                    document.schemaVersion(), document.operationId(), document.identity(),
                    document.actionType(), document.inputSha256(), document.startedAt());
            if (!document.contentSha256().equals(hash(payload))) {
                throw ControlArchiveSupport.operationConflict("Operation start hash mismatch");
            }
            return document;
        } catch (WorkspaceException failure) {
            if (ControlArchiveSupport.OPERATION_CONFLICT.equals(failure.code())) {
                throw failure;
            }
            throw ControlArchiveSupport.operationConflict("Operation start is unreadable", failure);
        }
    }

    private void readTerminalIfPresent(
            Path path, TerminalKind expectedKind, List<TerminalDocument> terminals) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw ControlArchiveSupport.operationConflict("Operation terminal is not a regular file");
        }
        try {
            TerminalDocument document = mapper.readJson(path, TerminalDocument.class);
            TerminalPayload payload = new TerminalPayload(
                    document.schemaVersion(), document.kind(), document.receipt(), document.recordedAt());
            if (document.kind() != expectedKind || !document.contentSha256().equals(hash(payload))) {
                throw ControlArchiveSupport.operationConflict("Operation terminal content conflict");
            }
            requireReceiptKind(expectedKind, document.receipt());
            terminals.add(document);
        } catch (WorkspaceException failure) {
            if (ControlArchiveSupport.OPERATION_CONFLICT.equals(failure.code())) {
                throw failure;
            }
            throw ControlArchiveSupport.operationConflict("Operation terminal is unreadable", failure);
        }
    }

    private void requireIdentity(
            AnalysisIdentity identity, OperationId operationId, StartedDocument started) {
        if (!identity.equals(started.identity()) || !operationId.equals(started.operationId())) {
            throw ControlArchiveSupport.operationConflict("Operation start identity conflict");
        }
    }

    private void requireTerminalMatches(StartedDocument started, TerminalDocument terminal) {
        OperationReceipt receipt = terminal.receipt();
        if (!started.operationId().equals(receipt.operationId())
                || !started.identity().equals(receipt.identity())
                || started.actionType() != receipt.actionType()
                || !started.inputSha256().equals(receipt.inputSha256())) {
            throw ControlArchiveSupport.operationConflict(
                    "Operation terminal does not match its start document");
        }
    }

    private String hash(Object payload) {
        return ControlArchiveSupport.digest(mapper.writeJson(payload));
    }

    private CaseArchiveLayout layout(AnalysisIdentity identity) {
        return CaseArchiveLayout.of(casesRoot, identity.caseId());
    }

    private static Path requireCasesRoot(Path value) {
        Path root = ControlArchiveSupport.notNull(value, "casesRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("casesRoot must be an existing regular directory");
        }
        return root;
    }
}
