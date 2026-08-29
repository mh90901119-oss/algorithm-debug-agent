package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.TestOutcome;

import java.nio.file.Path;
import java.util.Optional;

/** 从本次变化的 Surefire XML 中读取到的精确目标 testcase 事实。 */
public record SurefireTestResult(
        TestOutcome outcome,
        Optional<TargetFailureDiagnostic> targetFailure,
        Path sourceReport) {

    /** 校验测试结果与目标失败分类一致，且保留来源报告供归档。 */
    public SurefireTestResult {
        if (outcome == null || targetFailure == null || sourceReport == null) {
            throw new IllegalArgumentException("SurefireTestResult fields must not be null");
        }
        sourceReport = sourceReport.toAbsolutePath().normalize();
        if (outcome == TestOutcome.PASSED && targetFailure.isPresent()) {
            throw new IllegalArgumentException("PASSED must not contain a target failure");
        }
        if ((outcome == TestOutcome.FAILED || outcome == TestOutcome.ERROR
                || outcome == TestOutcome.NOT_EXECUTED) && targetFailure.isEmpty()) {
            throw new IllegalArgumentException(outcome + " must contain a target diagnostic");
        }
        targetFailure.ifPresent(failure -> validateCategory(outcome, failure.category()));
    }

    private static void validateCategory(TestOutcome outcome, FailureCategory category) {
        if (outcome == TestOutcome.FAILED && category != FailureCategory.TEST_FAILURE
                || outcome == TestOutcome.ERROR && category != FailureCategory.TEST_ERROR
                || outcome == TestOutcome.NOT_EXECUTED
                && category != FailureCategory.TEST_NOT_EXECUTED) {
            throw new IllegalArgumentException("The test result does not match the target failure classification");
        }
    }
}
