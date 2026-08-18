package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LauncherSummaryTest {
    @Test
    void targetFailureIsDistinctFromToolFailureAndTruncation() {
        LauncherSummary target = new LauncherSummary(
                LauncherOutcome.TARGET_FAILED, 2, 1, 0, 1, 9, 900,
                TraceJsonlSink.Limit.NONE, "target assertion failed");
        LauncherSummary tool = new LauncherSummary(
                LauncherOutcome.TOOL_FAILED, 0, 0, 0, 0, 0, 0,
                TraceJsonlSink.Limit.NONE, "cannot start JUnit");
        LauncherSummary truncated = new LauncherSummary(
                LauncherOutcome.TARGET_SUCCEEDED, 1, 1, 0, 0, 10, 1_024,
                TraceJsonlSink.Limit.OUTPUT_BYTES, "");
        LauncherSummary toolAndTarget = new LauncherSummary(
                LauncherOutcome.TOOL_FAILED, 1, 0, 0, 1, 2, 128,
                TraceJsonlSink.Limit.NONE, "CODEPATH_MULTIPLE_THREADS_UNSUPPORTED");

        assertTrue(target.targetFailed());
        assertFalse(tool.targetFailed());
        assertTrue(toolAndTarget.targetFailed());
        assertTrue(truncated.truncated());
        assertEquals(LauncherOutcome.TOOL_FAILED, tool.outcome());
    }

    @Test
    void structuredLineRoundTripsWithoutDependingOnProcessExitCode() {
        LauncherSummary expected = new LauncherSummary(
                LauncherOutcome.TARGET_FAILED, 3, 1, 0, 1, 42, 4096,
                TraceJsonlSink.Limit.EVENTS, "assert \"x\"\nfailed");

        String line = expected.toStructuredLine();
        LauncherSummary actual = LauncherSummary.parseStructuredLine(line);

        assertTrue(line.startsWith(LauncherSummary.LINE_PREFIX));
        assertEquals(expected, actual);
    }
}
