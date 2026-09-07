package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanAwareCodePathAgentTest {
    @TempDir Path temporaryDirectory;

    @AfterEach
    void deactivate() {
        CodePathCaptureRuntime.deactivate();
    }

    @Test
    void weavesOnlyTheExactSelectedMethod() throws Exception {
        String className = InstrumentedFixture.class.getName();
        var selector = new LauncherCodePathPlan.MethodSelector(
                className + "#selected()V", className, "selected", "()V");
        LauncherCodePathPlan plan = new LauncherCodePathPlan(
                "6.0", "plan-1", "case-1", "analysis-1",
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                List.of(new LauncherCodePathPlan.MethodSelection(selector, List.of())),
                null, List.of(), LauncherCodePathPlan.CaptureMode.TRACE, 1, 10_000,
                new LauncherCodePathPlan.Budget(100, 65_536, 30_000),
                "fixture", new LauncherCodePathPlan.Intent(
                        "Which path ran?", "selected executes", List.of(), List.of("selected")),
                Instant.EPOCH);
        Path raw = temporaryDirectory.resolve("trace.jsonl");
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(plan, raw);
        PlanAwareCodePathAgent.install(plan);

        InstrumentedFixture fixture = new InstrumentedFixture();
        fixture.unselected();
        fixture.selected();
        runtime.close();

        List<String> lines = Files.readAllLines(raw);
        assertEquals(2, lines.size());
        assertFalse(String.join("\n", lines).contains("unselected"));
    }

    static final class InstrumentedFixture {
        void selected() {}
        void unselected() {}
    }
}
