package org.example.algorithmdebug.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongFunction;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.InvestigationEventArchive;
import org.example.algorithmdebug.casecore.InvestigationJournalReader;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEvaluation;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.core.coordination.InvestigationStateProjector;
import org.example.algorithmdebug.evidence.EvidenceView;
import org.example.algorithmdebug.evidence.HypothesisEvidenceReducer;
import org.example.algorithmdebug.evidence.ObservationEvaluator;

/**
 * Investigation Ledger 的唯一应用层写入口。
 *
 * <p>模型只能通过 {@link InvestigationUpdateCommand} 增加假设、Gap 和 Predicate，或显式标记
 * Gap 未解决；Plan 绑定、Observation Evaluation 以及所有状态迁移均由本服务确定性生成。每批事件
 * 在归档前会完整回放，避免把非法的部分状态写入 Ledger。</p>
 */
public final class InvestigationApplicationService {
    private static final String EVENT_ID_PREFIX = "event-";
    private static final int EVENT_ID_HASH_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final BoundedDocumentMapper mapper;
    private final CaseArchiveRepository caseArchive;
    private final InvestigationEventArchive eventArchive;
    private final InvestigationJournalReader journalReader;
    private final InvestigationStateProjector projector;
    private final ObservationEvaluator observationEvaluator;
    private final HypothesisEvidenceReducer hypothesisReducer;
    private final Clock clock;

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界文档映射器
     * @param writer 原子写入器
     * @param clock 事件时钟
     */
    public InvestigationApplicationService(
            Path casesRoot,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            Clock clock) {
        if (casesRoot == null || mapper == null || writer == null || clock == null) {
            throw new IllegalArgumentException(
                    "casesRoot, mapper, writer and clock must not be null");
        }
        this.mapper = mapper;
        this.caseArchive = new CaseArchiveRepository(casesRoot, mapper, writer);
        this.eventArchive = new InvestigationEventArchive(casesRoot, mapper, writer);
        this.journalReader = new InvestigationJournalReader(casesRoot, mapper);
        this.projector = new InvestigationStateProjector();
        this.observationEvaluator = new ObservationEvaluator();
        this.hypothesisReducer = new HypothesisEvidenceReducer();
        this.clock = clock;
    }

    /** 执行模型允许提交的有界调查命令，并返回归档后状态。 */
    public InvestigationState update(InvestigationUpdateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        Journal journal = requireJournal(command.caseId(), command.analysisId());
        Instant occurredAt = clock.instant();
        InvestigationEvent event = switch (command) {
            case InvestigationUpdateCommand.AddHypothesis value -> event(
                    journal, "hypothesis-added", value.hypothesis(), sequence ->
                            new InvestigationEvent.HypothesisAdded(
                                    SchemaVersions.INVESTIGATION_EVENT,
                                    eventId("hypothesis-added", value.hypothesis()),
                                    value.caseId(), value.analysisId(), sequence,
                                    occurredAt, value.hypothesis()));
            case InvestigationUpdateCommand.AddEvidenceGap value -> event(
                    journal, "gap-added", value.gap(), sequence ->
                            new InvestigationEvent.EvidenceGapAdded(
                                    SchemaVersions.INVESTIGATION_EVENT,
                                    eventId("gap-added", value.gap()),
                                    value.caseId(), value.analysisId(), sequence,
                                    occurredAt, value.gap()));
            case InvestigationUpdateCommand.RegisterPredicate value -> event(
                    journal, "predicate-registered", value.predicate(), sequence ->
                            new InvestigationEvent.PredicateRegistered(
                                    SchemaVersions.INVESTIGATION_EVENT,
                                    eventId("predicate-registered", value.predicate()),
                                    value.caseId(), value.analysisId(), sequence,
                                    occurredAt, value.predicate()));
            case InvestigationUpdateCommand.MarkGapUnresolved value -> {
                EvidenceGap gap = requireGap(journal.state(), value.gapId());
                yield event(journal, "gap-unresolved", value, sequence ->
                        new InvestigationEvent.GapStatusChanged(
                                SchemaVersions.INVESTIGATION_EVENT,
                                eventId("gap-unresolved", value), value.caseId(),
                                value.analysisId(), sequence, occurredAt, value.gapId(),
                                gap.status(), EvidenceGapStatus.UNRESOLVED));
            }
        };
        return commit(journal, List.of(event));
    }

    /** 读取并严格回放指定 Analysis 的当前状态。 */
    public InvestigationState currentState(CaseId caseId, AnalysisId analysisId) {
        return requireJournal(caseId, analysisId).state();
    }

    /**
     * 在 Plan 已成功归档后记录冻结绑定，并立即把目标 Gap 从 OPEN 推进到 PLANNED。
     * 相同 Plan 的精确重放是幂等的，第二个 Plan 不得改写已计划 Gap。
     */
    public InvestigationState recordPlanBound(PlanId planId, InvestigationBinding binding) {
        if (planId == null || binding == null) {
            throw new IllegalArgumentException("planId and binding must not be null");
        }
        Journal journal = requireJournal(binding.caseId(), binding.analysisId());
        String boundId = eventId("plan-bound", new PlanBindingAnchor(planId, binding));
        var existing = journal.events().stream()
                .filter(value -> value.eventId().equals(boundId))
                .findFirst();
        if (existing.isPresent()) {
            if (existing.get() instanceof InvestigationEvent.PlanBound value
                    && value.planId().equals(planId) && value.binding().equals(binding)) {
                EvidenceGap currentGap = requireGap(journal.state(), binding.gapId());
                if (currentGap.status() != EvidenceGapStatus.OPEN) {
                    return journal.state();
                }
                InvestigationEvent repair = new InvestigationEvent.GapStatusChanged(
                        SchemaVersions.INVESTIGATION_EVENT,
                        eventId("gap-planned", new GapTransitionAnchor(
                                binding.gapId().value(), currentGap.status(),
                                EvidenceGapStatus.PLANNED)),
                        binding.caseId(), binding.analysisId(),
                        journal.state().lastSequence() + 1, clock.instant(),
                        binding.gapId(), currentGap.status(), EvidenceGapStatus.PLANNED);
                return commit(journal, List.of(repair));
            }
            throw new IllegalArgumentException("Investigation event identity conflict");
        }
        EvidenceGap gap = requireGap(journal.state(), binding.gapId());
        if (gap.status() != EvidenceGapStatus.OPEN) {
            throw new IllegalArgumentException(
                    "A new Plan can only bind an OPEN EvidenceGap");
        }
        Instant occurredAt = clock.instant();
        List<InvestigationEvent> events = new ArrayList<>();
        long sequence = journal.state().lastSequence() + 1;
        events.add(new InvestigationEvent.PlanBound(
                SchemaVersions.INVESTIGATION_EVENT, boundId,
                binding.caseId(), binding.analysisId(), sequence++, occurredAt,
                planId, binding));
        events.add(new InvestigationEvent.GapStatusChanged(
                SchemaVersions.INVESTIGATION_EVENT,
                eventId("gap-planned", new GapTransitionAnchor(
                        binding.gapId().value(), gap.status(), EvidenceGapStatus.PLANNED)),
                binding.caseId(), binding.analysisId(), sequence, occurredAt,
                binding.gapId(), gap.status(), EvidenceGapStatus.PLANNED));
        return commit(journal, events);
    }

    /**
     * 对 Plan 绑定的每个 Predicate 计算一次 Observation，并按证据等级推进 Gap 与 Hypothesis。
     * CLUE_ONLY/INVALID 只归档 Evaluation；只有确认级证据可以触发状态迁移。
     */
    public InvestigationState recordCollectedEvidence(
            InvestigationBinding binding, EvidenceView evidence) {
        if (binding == null || evidence == null) {
            throw new IllegalArgumentException("binding and evidence must not be null");
        }
        requireIdentity(binding, evidence);
        Journal journal = requireJournal(binding.caseId(), binding.analysisId());
        List<ObservationPredicate> predicates = binding.predicateIds().stream()
                .map(id -> journal.state().predicates().stream()
                        .filter(value -> value.predicateId().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Binding Predicate does not exist: " + id.value())))
                .toList();
        List<ObservationEvaluation> batchEvaluations = predicates.stream()
                .map(predicate -> observationEvaluator.evaluate(predicate, evidence))
                .toList();

        List<InvestigationEvent> candidates = new ArrayList<>();
        long sequence = journal.state().lastSequence() + 1;
        for (ObservationEvaluation evaluation : batchEvaluations) {
            ObservationEvaluation existing = journal.state().evaluations().stream()
                    .filter(value -> value.evaluationId().equals(evaluation.evaluationId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (!existing.equals(evaluation)) {
                    throw new IllegalArgumentException(
                            "Observation Evaluation identity conflict");
                }
                continue;
            }
            candidates.add(new InvestigationEvent.ObservationEvaluated(
                    SchemaVersions.INVESTIGATION_EVENT,
                    eventId("observation-evaluated", evaluation),
                    binding.caseId(), binding.analysisId(), sequence++,
                    clock.instant(), evaluation));
        }
        boolean confirmationEligible = batchEvaluations.stream().allMatch(value ->
                value.evidenceDisposition() == EvidenceDisposition.CONFIRMATION_ELIGIBLE);
        if (!confirmationEligible) {
            return candidates.isEmpty() ? journal.state() : commit(journal, candidates);
        }

        InvestigationState evaluated = project(journal.events(), candidates);
        EvidenceGap gap = requireGap(evaluated, binding.gapId());
        boolean gapAlreadyTerminal = gap.status() == EvidenceGapStatus.CLOSED
                || gap.status() == EvidenceGapStatus.UNRESOLVED;
        if (!gapAlreadyTerminal) {
            if (gap.status() != EvidenceGapStatus.PLANNED
                    && gap.status() != EvidenceGapStatus.OBSERVED) {
                throw new IllegalArgumentException(
                        "Confirmation evidence requires a PLANNED or OBSERVED EvidenceGap");
            }
            boolean hasUnknown = batchEvaluations.stream().anyMatch(value ->
                    value.truth() == org.example.algorithmdebug.contracts.investigation.ObservationTruth.UNKNOWN);
            if (gap.status() == EvidenceGapStatus.PLANNED) {
                candidates.add(gapTransition(
                        binding, sequence++, gap.status(), EvidenceGapStatus.OBSERVED,
                        "gap-observed"));
            }
            EvidenceGapStatus finalGapStatus = hasUnknown
                    ? EvidenceGapStatus.UNRESOLVED : EvidenceGapStatus.CLOSED;
            candidates.add(gapTransition(
                    binding, sequence++, EvidenceGapStatus.OBSERVED, finalGapStatus,
                    hasUnknown ? "gap-unresolved" : "gap-closed"));
        }

        InvestigationState transitioned = project(journal.events(), candidates);
        for (var hypothesisId : binding.hypothesisIds()) {
            HypothesisRecord hypothesis = transitioned.hypotheses().stream()
                    .filter(value -> value.hypothesisId().equals(hypothesisId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Binding Hypothesis does not exist: " + hypothesisId.value()));
            HypothesisEvaluation reduction = hypothesisReducer.reduce(
                    hypothesis, transitioned.predicates(), transitioned.gaps(),
                    transitioned.evaluations());
            if (reduction.newStatus() != reduction.previousStatus()) {
                candidates.add(new InvestigationEvent.HypothesisStatusChanged(
                        SchemaVersions.INVESTIGATION_EVENT,
                        eventId("hypothesis-status", reduction),
                        binding.caseId(), binding.analysisId(), sequence++, clock.instant(),
                        hypothesisId, reduction.previousStatus(), reduction.newStatus(),
                        reduction));
                transitioned = project(journal.events(), candidates);
            }
        }
        return commit(journal, candidates);
    }

    private InvestigationEvent event(
            Journal journal,
            String eventKind,
            Object payload,
            LongFunction<InvestigationEvent> factory) {
        String id = eventId(eventKind, payload);
        InvestigationEvent existing = journal.events().stream()
                .filter(value -> value.eventId().equals(id)).findFirst().orElse(null);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Investigation command was already applied: " + id);
        }
        return factory.apply(journal.state().lastSequence() + 1);
    }

    private InvestigationEvent gapTransition(
            InvestigationBinding binding,
            long sequence,
            EvidenceGapStatus previous,
            EvidenceGapStatus next,
            String kind) {
        GapTransitionAnchor anchor = new GapTransitionAnchor(
                binding.gapId().value(), previous, next);
        return new InvestigationEvent.GapStatusChanged(
                SchemaVersions.INVESTIGATION_EVENT, eventId(kind, anchor),
                binding.caseId(), binding.analysisId(), sequence, clock.instant(),
                binding.gapId(), previous, next);
    }

    private InvestigationState commit(Journal journal, List<InvestigationEvent> candidates) {
        if (candidates.isEmpty()) {
            return journal.state();
        }
        InvestigationState projected = project(journal.events(), candidates);
        for (InvestigationEvent candidate : candidates) {
            eventArchive.appendEvent(journal.identity(), candidate);
        }
        return projected;
    }

    private InvestigationState project(
            List<InvestigationEvent> existing, List<InvestigationEvent> candidates) {
        ArrayList<InvestigationEvent> all = new ArrayList<>(existing.size() + candidates.size());
        all.addAll(existing);
        all.addAll(candidates);
        return projector.project(all);
    }

    private Journal requireJournal(CaseId caseId, AnalysisId analysisId) {
        if (caseId == null || analysisId == null) {
            throw new IllegalArgumentException("caseId and analysisId must not be null");
        }
        ProjectId projectId = caseArchive.requireCase(caseId).projectId();
        AnalysisIdentity identity = new AnalysisIdentity(projectId, caseId, analysisId);
        InvestigationJournalReader.Result result = journalReader.readValidatedEvents(identity);
        if (!result.limitations().isEmpty()) {
            throw new IllegalArgumentException(
                    "Investigation journal contains sequence gaps: " + result.limitations());
        }
        if (result.events().isEmpty()) {
            throw new IllegalArgumentException("Investigation journal is empty");
        }
        return new Journal(identity, result.events(), projector.project(result.events()));
    }

    private String eventId(String kind, Object payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            digest.update(kind.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(mapper.writeJson(payload));
            return EVENT_ID_PREFIX + HexFormat.of().formatHex(digest.digest())
                    .substring(0, EVENT_ID_HASH_LENGTH);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM does not provide SHA-256", failure);
        }
    }

    private static EvidenceGap requireGap(
            InvestigationState state,
            org.example.algorithmdebug.contracts.investigation.EvidenceGapId gapId) {
        return state.gaps().stream().filter(value -> value.gapId().equals(gapId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "EvidenceGap does not exist: " + gapId.value()));
    }

    private static void requireIdentity(
            InvestigationBinding binding, EvidenceView evidence) {
        if (!binding.caseId().equals(evidence.caseId())
                || !binding.analysisId().equals(evidence.analysisId())) {
            throw new IllegalArgumentException("Evidence and binding identity mismatch");
        }
    }

    private record Journal(
            AnalysisIdentity identity,
            List<InvestigationEvent> events,
            InvestigationState state) {
        private Journal {
            events = List.copyOf(events);
        }
    }

    private record PlanBindingAnchor(PlanId planId, InvestigationBinding binding) {
    }

    private record GapTransitionAnchor(
            String gapId, EvidenceGapStatus previous, EvidenceGapStatus next) {
    }
}
