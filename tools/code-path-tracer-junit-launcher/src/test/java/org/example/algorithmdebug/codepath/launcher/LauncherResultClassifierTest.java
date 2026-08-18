package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LauncherResultClassifierTest {
    @Test
    void classifiesTargetFromJUnitFactsAndToolFromInfrastructureFailure() {
        assertEquals(LauncherOutcome.TARGET_SUCCEEDED,
                LauncherResultClassifier.classify(1, 0, 0, Optional.empty()));
        assertEquals(LauncherOutcome.TARGET_FAILED,
                LauncherResultClassifier.classify(1, 1, 0, Optional.empty()));
        assertEquals(LauncherOutcome.TARGET_FAILED,
                LauncherResultClassifier.classify(0, 0, 0, Optional.empty()));
        assertEquals(LauncherOutcome.TOOL_FAILED,
                LauncherResultClassifier.classify(1, 0, 0,
                        Optional.of(new IOException("disk full"))));
    }
}
