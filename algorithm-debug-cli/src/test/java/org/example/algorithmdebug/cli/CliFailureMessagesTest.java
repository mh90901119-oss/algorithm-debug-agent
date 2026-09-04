package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CliFailureMessagesTest {
    @Test
    void directsUnsupportedEvidenceQueriesToTheCorrectReaderOrArtifactType() {
        assertEquals(
                "Evidence query supports only CODEPATH_INVOCATIONS and JDWP_SNAPSHOT_SUMMARY Artifacts; "
                        + "use artifact_read for other registered Artifacts or select one of those two Artifact types. "
                        + "This tool result is not target-test evidence.",
                CliFailureMessages.forCode("CASE_EVIDENCE_QUERY_ARTIFACT_UNSUPPORTED"));
    }

    @Test
    void directsAnInsufficientQueryBudgetToAUsefulRecovery() {
        assertEquals(
                "Evidence query output budget cannot return the selected record; increase maxBytes up to 65536 "
                        + "or create a narrower collection projection. This tool result is not target-test evidence.",
                CliFailureMessages.forCode("CASE_EVIDENCE_QUERY_BUDGET_TOO_SMALL"));
    }
}
