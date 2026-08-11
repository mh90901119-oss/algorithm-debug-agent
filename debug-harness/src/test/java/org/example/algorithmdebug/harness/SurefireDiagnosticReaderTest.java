package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireDiagnosticReaderTest {

    @TempDir
    Path temporaryDirectory;

    private final SurefireDiagnosticReader reader = new SurefireDiagnosticReader();

    @Test
    void shouldExtractErrorWithoutInferringBusinessCause() throws Exception {
        copyFixture("TEST-error.xml");

        var diagnostic = reader.read(temporaryDirectory, "a.b.TargetTest#runs").orElseThrow();

        assertEquals(FailureCategory.TEST_ERROR, diagnostic.category());
        assertEquals("java.lang.IllegalStateException", diagnostic.exceptionClass());
        assertEquals("java.lang.NullPointerException: resource was null", diagnostic.cause());
        assertEquals("a.b.Algorithm.solve(Algorithm.java:42)", diagnostic.stableBusinessFrame());
        assertTrue(diagnostic.normalizedMessage().contains("schedule failed at <TIMESTAMP>"));
    }

    @Test
    void shouldClassifyAssertionAsTestFailure() throws Exception {
        copyFixture("TEST-assertion.xml");

        var diagnostic = reader.read(temporaryDirectory, "a.b.TargetTest#runs").orElseThrow();

        assertEquals(FailureCategory.TEST_FAILURE, diagnostic.category());
        assertEquals("org.opentest4j.AssertionFailedError", diagnostic.exceptionClass());
    }

    @Test
    void shouldReturnEmptyWhenReportOrTargetIsMissing() throws Exception {
        assertTrue(reader.read(temporaryDirectory, "a.b.TargetTest#runs").isEmpty());
        copyFixture("TEST-error.xml");
        assertTrue(reader.read(temporaryDirectory, "a.b.OtherTest#runs").isEmpty());
    }

    @Test
    void shouldRejectDoctype() throws Exception {
        Files.writeString(temporaryDirectory.resolve("TEST-malicious.xml"),
                "<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]>"
                        + "<testsuite><testcase classname='a.b.TargetTest' name='runs'>"
                        + "<error type='x' message='&ext;'>&ext;</error></testcase></testsuite>");

        assertThrows(SurefireDiagnosticException.class,
                () -> reader.read(temporaryDirectory, "a.b.TargetTest#runs"));
    }

    @Test
    void shouldRejectDoctypeWithoutWritingParserDiagnosticsToStderr() throws Exception {
        Files.writeString(temporaryDirectory.resolve("TEST-malicious.xml"),
                "<!DOCTYPE x><testsuite><testcase classname='a.b.TargetTest' name='runs'>"
                        + "<error type='x' message='bad'>bad</error></testcase></testsuite>");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured));
            assertThrows(SurefireDiagnosticException.class,
                    () -> reader.read(temporaryDirectory, "a.b.TargetTest#runs"));
        } finally {
            System.setErr(original);
        }

        assertEquals("", captured.toString());
    }

    private void copyFixture(String name) throws Exception {
        try (var source = getClass().getResourceAsStream("/surefire/" + name)) {
            Files.copy(source, temporaryDirectory.resolve(name));
        }
    }
}
