package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunOutcomeSummaryJsonTest {

    @Test
    void shouldRoundTripOptionalDiagnostics() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
        RunOutcomeSummary original = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), true,
                ProcessOutcome.FAILED, TestOutcome.ERROR, GanttOutcome.ABSENT,
                Optional.of(new TargetFailureDiagnostic(
                        FailureCategory.TEST_ERROR, "java.lang.IllegalStateException",
                        "failed", "", "a.b.Algorithm.solve(Algorithm.java:42)")),
                Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "No comparison", List.of());

        String json = mapper.writeValueAsString(original);
        RunOutcomeSummary restored = mapper.readValue(json, RunOutcomeSummary.class);

        assertEquals(original, restored);
    }
}
