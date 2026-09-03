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

/** 以同 Analysis 普通 Run 的失败指纹比较动态采集目标失败。 */
final class TargetFailureBaselineEvaluator {

    private final Clock clock;

    TargetFailureBaselineEvaluator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.clock = clock;
    }

    CollectionBaselineCheck evaluate(
            CaseArchiveRepository archive,
            Identity identity,
            Path moduleRoot,
            Optional<CapturedScheduleResult<?>> captured)
            throws WorkspaceException, HarnessException, SurefireDiagnosticException {
        if (archive == null || identity == null || moduleRoot == null || captured == null) {
            throw new IllegalArgumentException("failed Baseline parameters must not be null");
        }
        Optional<RunResultFingerprint> reference = archive.findLatestRunResultFingerprint(
                identity.caseId(), identity.analysisId());
        var diagnostic = new SurefireDiagnosticReader().read(
                moduleRoot.resolve("target/surefire-reports"), identity.targetTest().selector());
        if (diagnostic.isEmpty()) {
            return check(identity, ComparisonOutcome.INCOMPARABLE,
                    reference.map(RunResultFingerprint::runId), false,
                    "Target failed; Surefire did not provide a comparable failure fingerprint");
        }
        RunResultFingerprint current = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, identity.caseId(), identity.analysisId(),
                identity.runId(), new TargetFailureFingerprinter().sha256(diagnostic.orElseThrow()));
        if (reference.isEmpty()) {
            return check(identity, ComparisonOutcome.NOT_COMPARED, Optional.empty(), false,
                    "No uninstrumented same-analysis failure fingerprint");
        }
        ReproductionComparator.Result compared = new ReproductionComparator().compare(
                reference.orElseThrow(), current);
        return check(identity, compared.outcome(),
                Optional.of(reference.orElseThrow().runId()),
                compared.outcome() == ComparisonOutcome.MATCHED, compared.summary());
    }

    private CollectionBaselineCheck check(
            Identity identity,
            ComparisonOutcome outcome,
            Optional<RunId> referenceRunId,
            boolean usable,
            String summary) {
        return new CollectionBaselineCheck(
                SchemaVersions.COLLECTION_BASELINE_CHECK,
                identity.caseId(), identity.analysisId(), identity.runId(),
                identity.collectionId(), outcome, referenceRunId,
                usable, summary, clock.instant());
    }

    /** 两类 Collection 共享的最小目标运行身份。 */
    record Identity(
            CaseId caseId,
            AnalysisId analysisId,
            RunId runId,
            CollectionId collectionId,
            TargetTest targetTest) {

        Identity {
            if (caseId == null || analysisId == null || runId == null
                    || collectionId == null || targetTest == null) {
                throw new IllegalArgumentException("The failed Baseline identity must not be null");
            }
        }

        static Identity from(MethodPathCollectionRecord record) {
            return new Identity(
                    record.caseId(), record.analysisId(), record.runId(),
                    record.collectionId(), record.targetTest());
        }

        static Identity from(JdwpCollectionRecord record) {
            return new Identity(
                    record.caseId(), record.analysisId(), record.runId(),
                    record.collectionId(), record.targetTest());
        }
    }
}