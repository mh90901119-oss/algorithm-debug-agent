package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TargetFailureFingerprinterTest {

    private final TargetFailureFingerprinter fingerprinter = new TargetFailureFingerprinter();

    @Test
    void ignoresFormattingWhitespaceAndSourceLineNumber() throws Exception {
        TargetFailureDiagnostic first = diagnostic(
                "java.lang.NullPointerException", "missing   route", "Planner failed",
                "com.acme.Planner.solve(Planner.java:42)");
        TargetFailureDiagnostic same = diagnostic(
                "java.lang.NullPointerException", "missing route", "Planner failed",
                "com.acme.Planner.solve(Planner.java:99)");

        assertEquals(fingerprinter.sha256(first), fingerprinter.sha256(same));
    }

    @Test
    void detectsExceptionCauseAndBusinessMethodChanges() throws Exception {
        TargetFailureDiagnostic reference = diagnostic(
                "java.lang.NullPointerException", "missing route", "Planner failed for JOB-42",
                "com.acme.Planner.solve(Planner.java:42)");

        assertNotEquals(fingerprinter.sha256(reference), fingerprinter.sha256(diagnostic(
                "java.lang.IllegalStateException", "missing route", "Planner failed for JOB-42",
                "com.acme.Planner.solve(Planner.java:42)")));
        assertNotEquals(fingerprinter.sha256(reference), fingerprinter.sha256(diagnostic(
                "java.lang.NullPointerException", "missing route", "Planner failed for JOB-43",
                "com.acme.Planner.solve(Planner.java:42)")));
        assertNotEquals(fingerprinter.sha256(reference), fingerprinter.sha256(diagnostic(
                "java.lang.NullPointerException", "missing route", "Planner failed for JOB-42",
                "com.acme.Planner.dispatch(Planner.java:42)")));
    }

    @Test
    void preservesBusinessNumbersOutsideSourceLineSuffix() throws Exception {
        TargetFailureDiagnostic job42 = diagnostic(
                "java.lang.IllegalStateException", "iteration 10000 for JOB-42", "", "");
        TargetFailureDiagnostic job43 = diagnostic(
                "java.lang.IllegalStateException", "iteration 10000 for JOB-43", "", "");

        assertNotEquals(fingerprinter.sha256(job42), fingerprinter.sha256(job43));
    }

    private static TargetFailureDiagnostic diagnostic(
            String exceptionClass, String message, String cause, String frame) {
        return new TargetFailureDiagnostic(
                FailureCategory.TEST_ERROR, exceptionClass, message, cause, frame);
    }
}
