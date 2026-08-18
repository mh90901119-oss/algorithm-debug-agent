package org.example.algorithmdebug.core;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.ReproductionComparator;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.harness.CapturedScheduleResult;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.SurefireDiagnosticException;
import org.example.algorithmdebug.harness.SurefireDiagnosticReader;
import org.example.algorithmdebug.harness.TargetFailureFingerprinter;

/** 以 Surefire 通用失败指纹比较无采集与动态采集的目标失败结果。 */
final class TargetFailureBaselineEvaluator {

    private final Clock clock;

    TargetFailureBaselineEvaluator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock 不能为空");
        this.clock = clock;
    }

    CollectionBaselineCheck evaluate(
            CaseArchiveRepository archive,
            Identity identity,
            Path moduleRoot,
            Optional<CapturedScheduleResult<?>> captured)
            throws WorkspaceException, HarnessException, SurefireDiagnosticException {
        if (archive == null || identity == null || moduleRoot == null || captured == null) {
            throw new IllegalArgumentException("失败 Baseline 参数不能为空");
        }
        Optional<RunResultFingerprint> reference = archive.findReproduction(
                identity.caseId(), identity.contextId());
        var diagnostic = new SurefireDiagnosticReader().read(
                moduleRoot.resolve("target/surefire-reports"), identity.targetTest().selector());
        Optional<String> currentGantt = captured.map(CapturedScheduleResult::normalizedJsonSha256);
        if (diagnostic.isEmpty()) {
            return check(identity, ComparisonOutcome.INCOMPARABLE,
                    reference.map(RunResultFingerprint::runId), currentGantt, false,
                    "Target failed; Surefire did not provide a comparable failure fingerprint");
        }
        RunResultFingerprint current = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, identity.caseId(), identity.contextId(),
                identity.runId(), captured.map(CapturedScheduleResult::rawSha256), currentGantt,
                Optional.of(new TargetFailureFingerprinter().sha256(diagnostic.orElseThrow())));
        if (reference.isEmpty()) {
            return check(identity, ComparisonOutcome.NOT_COMPARED, Optional.empty(),
                    currentGantt, false,
                    "No uninstrumented same-context reproduction reference");
        }
        ReproductionComparator.Result compared = new ReproductionComparator().compare(
                reference.orElseThrow(), current, ReproductionComparator.Scope.SAME_CONTEXT);
        return check(identity, compared.outcome(),
                Optional.of(reference.orElseThrow().runId()), currentGantt,
                compared.outcome() == ComparisonOutcome.MATCHED, compared.summary());
    }

    private CollectionBaselineCheck check(
            Identity identity,
            ComparisonOutcome outcome,
            Optional<RunId> referenceRunId,
            Optional<String> currentGantt,
            boolean usable,
            String summary) {
        return new CollectionBaselineCheck(
                "1.0", identity.caseId(), identity.contextId(), identity.analysisId(),
                identity.runId(), identity.collectionId(), outcome, referenceRunId,
                currentGantt, usable, summary, clock.instant());
    }

    /** 两类 Collection 共享的最小目标运行身份。 */
    record Identity(
            CaseId caseId,
            ContextId contextId,
            AnalysisId analysisId,
            RunId runId,
            CollectionId collectionId,
            TargetTest targetTest) {

        Identity {
            if (caseId == null || contextId == null || analysisId == null || runId == null
                    || collectionId == null || targetTest == null) {
                throw new IllegalArgumentException("失败 Baseline 身份不能为空");
            }
        }

        static Identity from(MethodPathCollectionRecord record) {
            return new Identity(
                    record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                    record.collectionId(), record.targetTest());
        }

        static Identity from(JdwpCollectionRecord record) {
            return new Identity(
                    record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                    record.collectionId(), record.targetTest());
        }
    }
}
