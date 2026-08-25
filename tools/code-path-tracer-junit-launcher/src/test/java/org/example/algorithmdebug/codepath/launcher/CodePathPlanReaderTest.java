package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathPlanReaderTest {
    @TempDir Path directory;

    @Test
    void readsTheVersionedAgentPlanWithoutAdaContractsOnTheTargetJvm() throws Exception {
        Path planFile = Files.writeString(directory.resolve("plan.json"), validPlan(""));

        LauncherCodePathPlan plan = new CodePathPlanReader().read(planFile);

        assertEquals("fixture.TargetTest#case1", plan.targetTest().selector());
        assertEquals("fixture.Service#solve()V", plan.selectors().get(0).methodKey());
        assertEquals(100, plan.budget().maxEvents());
        assertEquals(4096, plan.budget().maxBytes());
    }

    @Test
    void rejectsUnknownPlanFields() throws Exception {
        Path planFile = Files.writeString(
                directory.resolve("unknown.json"), validPlan(",\"unknown\":true"));

        assertThrows(IOException.class, () -> new CodePathPlanReader().read(planFile));
    }

    private static String validPlan(String suffix) {
        return """
                {
                  "schemaVersion":"2.0",
                  "planId":"plan-1",
                  "caseId":"case-1",
                  "contextId":"context-1",
                  "analysisId":"analysis-1",
                  "targetTest":{"className":"fixture.TargetTest","methodName":"case1"},
                  "selectors":[{
                    "methodKey":"fixture.Service#solve()V",
                    "className":"fixture.Service",
                    "methodName":"solve",
                    "descriptor":"()V"
                  }],
                  "budget":{"maxEvents":100,"maxBytes":4096,"timeoutMillis":30000},
                  "rationale":"fixture",
                  "createdAt":"2026-08-25T00:00:00Z"
                }
                """.trim().replace("\n}", suffix + "\n}");
    }
}
