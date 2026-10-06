package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;

/** 可归档、可确定性编译并绑定调查 Predicate 的 JDWP Agent 采集计划。 */
public record JdwpCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<JdwpTracepointSpec> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        String questionToAnswer,
        InvestigationBindingStatus investigationStatus,
        Optional<InvestigationBinding> investigationBinding,
        Instant createdAt) {

    private static final int MAX_TRACEPOINTS = 20;
    private static final int MAX_SNAPSHOTS = 500;

    /** 校验采集预算以及当前/旧版本调查绑定语义。 */
    public JdwpCollectionPlan {
        boolean current = SchemaVersions.JDWP_COLLECTION_PLAN.equals(schemaVersion);
        boolean legacy = SchemaVersions.JDWP_COLLECTION_PLAN_LEGACY.equals(schemaVersion);
        if (!current && !legacy) {
            throw new IllegalArgumentException("Unsupported JdwpCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        tracepoints = ContractChecks.immutableList(tracepoints, "tracepoints");
        if (tracepoints.isEmpty() || tracepoints.size() > MAX_TRACEPOINTS) {
            throw new IllegalArgumentException("tracepoints count must be between 1 and 20");
        }
        HashSet<String> ids = new HashSet<>();
        if (tracepoints.stream().anyMatch(point -> !ids.add(point.tracepointId()))) {
            throw new IllegalArgumentException("tracepointId must not be duplicated");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        long maximumSnapshots = tracepoints.stream()
                .mapToLong(JdwpTracepointSpec::maxCapturedHits).sum();
        if (maximumSnapshots > budget.maxEvents() || maximumSnapshots > MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("Tracepoint snapshots exceed the collection event budget");
        }
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        questionToAnswer = ContractChecks.requireBoundedText(
                questionToAnswer, "questionToAnswer", 2_048, false);
        investigationStatus = ContractChecks.requireNonNull(
                investigationStatus, "investigationStatus");
        investigationBinding = investigationBinding == null
                ? Optional.empty() : investigationBinding;
        validateInvestigation(current, caseId, analysisId,
                investigationStatus, investigationBinding);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 同时读取当前 v6 与旧 v5 JSON；旧 intent 不具有 Predicate 门禁效力。 */
    @JsonCreator
    public static JdwpCollectionPlan fromJson(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("planId") PlanId planId,
            @JsonProperty("caseId") CaseId caseId,
            @JsonProperty("analysisId") AnalysisId analysisId,
            @JsonProperty("targetTest") TargetTest targetTest,
            @JsonProperty("tracepoints") List<JdwpTracepointSpec> tracepoints,
            @JsonProperty("budget") JdwpCollectionBudget budget,
            @JsonProperty("rationale") String rationale,
            @JsonProperty("questionToAnswer") String questionToAnswer,
            @JsonProperty("investigationStatus") InvestigationBindingStatus investigationStatus,
            @JsonProperty("investigationBinding") Optional<InvestigationBinding> investigationBinding,
            @JsonProperty("intent") InvestigationIntent legacyIntent,
            @JsonProperty("createdAt") Instant createdAt) {
        if (SchemaVersions.JDWP_COLLECTION_PLAN_LEGACY.equals(schemaVersion)) {
            if (legacyIntent == null || questionToAnswer != null || investigationStatus != null
                    || (investigationBinding != null && investigationBinding.isPresent())) {
                throw new IllegalArgumentException("Legacy JDWP plan must contain only legacy intent");
            }
            return new JdwpCollectionPlan(
                    schemaVersion, planId, caseId, analysisId, targetTest, tracepoints,
                    budget, rationale, legacyIntent.questionToAnswer(),
                    InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
        }
        if (legacyIntent != null) {
            throw new IllegalArgumentException("Current JDWP plan must not contain legacy intent");
        }
        return new JdwpCollectionPlan(
                schemaVersion, planId, caseId, analysisId, targetTest, tracepoints,
                budget, rationale, questionToAnswer, investigationStatus,
                investigationBinding, createdAt);
    }

    /** 旧 v5 Java 调用方只读构造器；不能用于创建当前计划。 */
    public JdwpCollectionPlan(
            String schemaVersion,
            PlanId planId,
            CaseId caseId,
            AnalysisId analysisId,
            TargetTest targetTest,
            List<JdwpTracepointSpec> tracepoints,
            JdwpCollectionBudget budget,
            String rationale,
            InvestigationIntent intent,
            Instant createdAt) {
        this(schemaVersion, planId, caseId, analysisId, targetTest, tracepoints,
                budget, rationale, legacyQuestion(intent),
                InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
    }

    private static String legacyQuestion(InvestigationIntent intent) {
        return ContractChecks.requireNonNull(intent, "intent").questionToAnswer();
    }

    private static void validateInvestigation(
            boolean current,
            CaseId caseId,
            AnalysisId analysisId,
            InvestigationBindingStatus status,
            Optional<InvestigationBinding> binding) {
        if (current) {
            if (status != InvestigationBindingStatus.STRUCTURED || binding.isEmpty()) {
                throw new IllegalArgumentException(
                        "Current JDWP plan requires a STRUCTURED InvestigationBinding");
            }
            InvestigationBinding value = binding.orElseThrow();
            if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
                throw new IllegalArgumentException("JDWP plan binding identity mismatch");
            }
        } else if (status != InvestigationBindingStatus.LEGACY_UNSTRUCTURED
                || binding.isPresent()) {
            throw new IllegalArgumentException(
                    "Legacy JDWP plan must be LEGACY_UNSTRUCTURED without a binding");
        }
    }
}
