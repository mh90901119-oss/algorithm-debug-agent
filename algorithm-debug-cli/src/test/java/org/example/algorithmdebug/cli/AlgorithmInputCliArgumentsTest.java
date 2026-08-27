package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class AlgorithmInputCliArgumentsTest {
    @Test
    void parsesTheExplicitAlgorithmInputCaptureCommand() {
        CliCommand.AlgorithmInputCapture command = assertInstanceOf(
                CliCommand.AlgorithmInputCapture.class,
                CliArguments.parse(new String[]{
                        "input", "capture", "--workspace", "workspace", "--project-id", "project-1",
                        "--case-id", "case-1", "--analysis-id", "analysis-1"}));
        assertEquals("case-1", command.caseId().value());
        assertEquals("analysis-1", command.analysisId().value());
    }
}
