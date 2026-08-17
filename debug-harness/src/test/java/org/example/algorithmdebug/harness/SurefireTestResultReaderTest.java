package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireTestResultReaderTest {

    @TempDir
    Path temporaryDirectory;

    private final TargetTest target = new TargetTest("a.b.TargetTest", "runs");
    private final SurefireTestResultReader reader = new SurefireTestResultReader();

    @Test
    void exactCurrentTestcaseWithoutFailureIsPassed() throws Exception {
        Path report = write("""
                <testsuite><testcase classname='a.b.TargetTest' name='runs'/></testsuite>
                """);

        SurefireTestResult result = reader.read(List.of(report), target).orElseThrow();

        assertEquals(TestOutcome.PASSED, result.outcome());
        assertTrue(result.targetFailure().isEmpty());
    }

    @Test
    void assertionAndExceptionRemainDifferentFacts() throws Exception {
        Path failureReport = write("""
                <testsuite><testcase classname='a.b.TargetTest' name='runs'>
                  <failure type='org.opentest4j.AssertionFailedError' message='wrong'>
                    at a.b.TargetTest.runs(TargetTest.java:10)
                  </failure>
                </testcase></testsuite>
                """);
        SurefireTestResult failed = reader.read(List.of(failureReport), target).orElseThrow();
        Files.delete(failureReport);
        Path errorReport = write("""
                <testsuite><testcase classname='a.b.TargetTest' name='runs'>
                  <error type='java.lang.NullPointerException' message='value was null'>
                    at a.b.Algorithm.solve(Algorithm.java:42)
                  </error>
                </testcase></testsuite>
                """);

        SurefireTestResult errored = reader.read(List.of(errorReport), target).orElseThrow();

        assertEquals(TestOutcome.FAILED, failed.outcome());
        assertEquals(FailureCategory.TEST_FAILURE,
                failed.targetFailure().orElseThrow().category());
        assertEquals(TestOutcome.ERROR, errored.outcome());
        assertEquals(FailureCategory.TEST_ERROR,
                errored.targetFailure().orElseThrow().category());
    }

    @Test
    void skippedExactTestIsNotReportedAsPassed() throws Exception {
        Path report = write("""
                <testsuite><testcase classname='a.b.TargetTest' name='runs'>
                  <skipped message='disabled'/>
                </testcase></testsuite>
                """);

        SurefireTestResult result = reader.read(List.of(report), target).orElseThrow();

        assertEquals(TestOutcome.NOT_EXECUTED, result.outcome());
        assertEquals(FailureCategory.TEST_NOT_EXECUTED,
                result.targetFailure().orElseThrow().category());
    }

    @Test
    void reportWithoutExactTargetTestcaseProducesNoFact() throws Exception {
        Path report = write("""
                <testsuite><testcase classname='a.b.TargetTest' name='other'/></testsuite>
                """);

        assertTrue(reader.read(List.of(report), target).isEmpty());
    }

    private Path write(String content) throws Exception {
        return Files.writeString(temporaryDirectory.resolve("TEST-a.b.TargetTest.xml"), content);
    }
}
