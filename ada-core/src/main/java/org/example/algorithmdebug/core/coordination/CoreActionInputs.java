package org.example.algorithmdebug.core.coordination;

import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceQueryRequest;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanRequest;

/** 17 个 Core Action 的有界 typed payload；Workspace/Project/Case/Analysis 身份位于 ActionTarget。 */
public final class CoreActionInputs {
    private static final int MAX_ADAPTER_ID_LENGTH = 512;
    private static final int MAX_ARTIFACT_ID_LENGTH = 256;
    private static final int MAX_GANTT_OPERATION_LENGTH = 32;
    private static final int MAX_JSON_POINTER_LENGTH = 4_096;
    private static final int MAX_GANTT_ROWS = 100;
    private static final int MAX_ARTIFACT_READ_BYTES = 1_048_576;

    private CoreActionInputs() {
    }

    /** 无额外载荷的动作使用单例，禁止以 null 表达。 */
    public enum NoInput { INSTANCE }

    /** 原子创建 Analysis 与 Problem Frame。 */
    public record AnalysisBegin(
            ProblemFrame problemFrame,
            Optional<CaseId> existingCaseId,
            Optional<String> adapterId) {
        public AnalysisBegin {
            problemFrame = requireNonNull(problemFrame, "problemFrame");
            existingCaseId = requireOptional(existingCaseId, "existingCaseId");
            adapterId = requireOptional(adapterId, "adapterId")
                    .map(value -> requireText(
                            value, "adapterId", MAX_ADAPTER_ID_LENGTH, false));
            if (existingCaseId.isPresent()
                    && !existingCaseId.orElseThrow().equals(problemFrame.caseId())) {
                throw new IllegalArgumentException(
                        "existingCaseId must match ProblemFrame.caseId");
            }
        }
    }

    /** 通过已注册 Gantt Artifact 执行 summary/slice。 */
    public record GanttInspect(
            String artifactId,
            String operation,
            String jsonPointer,
            int offset,
            int limit) {
        public GanttInspect {
            artifactId = requireText(
                    artifactId, "artifactId", MAX_ARTIFACT_ID_LENGTH, false);
            operation = requireText(
                    operation, "operation", MAX_GANTT_OPERATION_LENGTH, false);
            jsonPointer = requireText(
                    jsonPointer == null ? "" : jsonPointer,
                    "jsonPointer", MAX_JSON_POINTER_LENGTH, true);
            if (offset < 0 || limit < 1 || limit > MAX_GANTT_ROWS) {
                throw new IllegalArgumentException("Gantt slice budget is invalid");
            }
        }
    }

    /** 不包含 queryId 的 Source Query；身份由 Core 生成。 */
    public record SourceQuery(
            SourceQueryMode mode,
            Optional<String> methodKey,
            Optional<String> targetMethodKey,
            Optional<SourceAnchor> sourceAnchor,
            Optional<String> symbol,
            SourceQueryBudget budget) {
        public SourceQuery {
            mode = requireNonNull(mode, "mode");
            methodKey = requireOptional(methodKey, "methodKey");
            targetMethodKey = requireOptional(targetMethodKey, "targetMethodKey");
            sourceAnchor = requireOptional(sourceAnchor, "sourceAnchor");
            symbol = requireOptional(symbol, "symbol");
            budget = requireNonNull(budget, "budget");
        }
    }

    /** 模型允许提交的调查命令。 */
    public record InvestigationUpdate(InvestigationUpdateCommand command) {
        public InvestigationUpdate {
            command = requireNonNull(command, "command");
        }
    }

    /** CodePath 计划编译请求。 */
    public record CodePathPlanCreate(CodePathPlanRequest request) {
        public CodePathPlanCreate {
            request = requireNonNull(request, "request");
        }
    }

    /** 执行已归档 CodePath Plan。 */
    public record CodePathCollect(PlanId planId) {
        public CodePathCollect {
            planId = requireNonNull(planId, "planId");
        }
    }

    /** JDWP 计划编译请求。 */
    public record JdwpPlanCreate(JdwpPlanRequest request) {
        public JdwpPlanCreate {
            request = requireNonNull(request, "request");
        }
    }

    /** 执行已归档 JDWP Plan。 */
    public record JdwpCollect(PlanId planId) {
        public JdwpCollect {
            planId = requireNonNull(planId, "planId");
        }
    }

    /** 有界读取已注册 Artifact。 */
    public record ArtifactRead(String artifactId, long offsetBytes, int maxBytes) {
        public ArtifactRead {
            artifactId = requireText(
                    artifactId, "artifactId", MAX_ARTIFACT_ID_LENGTH, false);
            if (offsetBytes < 0 || maxBytes < 1 || maxBytes > MAX_ARTIFACT_READ_BYTES) {
                throw new IllegalArgumentException("Artifact read budget is invalid");
            }
        }
    }

    /** 查询已注册派生 Evidence。 */
    public record EvidenceQuery(String artifactId, EvidenceQueryRequest request) {
        public EvidenceQuery {
            artifactId = requireText(
                    artifactId, "artifactId", MAX_ARTIFACT_ID_LENGTH, false);
            request = requireNonNull(request, "request");
        }
    }

    /** 提交因果结论候选。 */
    public record AnalysisFinalize(ConclusionCandidate candidate) {
        public AnalysisFinalize {
            candidate = requireNonNull(candidate, "candidate");
        }
    }

    private static String requireText(
            String value, String field, int maximumLength, boolean allowEmpty) {
        if (value == null || !value.equals(value.strip()) || value.length() > maximumLength
                || (!allowEmpty && value.isEmpty())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String field) {
        if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
