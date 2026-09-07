package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalJUnitTraceLauncherTest {

    @Test
    void launcherSummaryKeepsCaptureCoverageAndReasons() {
        LauncherSummary summary = new LauncherSummary(
                LauncherOutcome.TARGET_SUCCEEDED, 1, 1, 0, 0, 20, 200,
                TraceJsonlSink.Limit.NONE, "", "AGGREGATE", "SELECTED_METHOD_DEPTH",
                2, 3, true, 100, 10, 5, 5, 1, 10, 20,
                List.of("VALUE_CARDINALITY_LIMIT_REACHED"));

        LauncherSummary decoded = LauncherSummary.parseStructuredLine(summary.toStructuredLine());

        assertEquals(summary, decoded);
        assertEquals(5, decoded.scopeInvocationsCaptured());
    }
}
