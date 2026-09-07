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
        Path planFile = Files.writeString(directory.resolve("plan.json"), validPlan("6.0",
                ",\"captureMode\":\"AGGREGATE\",\"scopeStartOrdinal\":100,\"maxMatchedScopes\":20"));

        LauncherCodePathPlan plan = new CodePathPlanReader().read(planFile);

        assertEquals("fixture.TargetTest#case1", plan.targetTest().selector());
        assertEquals("fixture.Service#solve()V", plan.methodSelections().get(0).selector().methodKey());
        assertEquals("waferId", plan.methodSelections().get(0).projections().get(0).name());
        assertEquals("fixture.Service#solve()V", plan.scopeMethodKey());
        assertEquals(100, plan.budget().maxEvents());
        assertEquals(4096, plan.budget().maxBytes());
        assertEquals("Which path ran?", plan.intent().questionToAnswer());
        assertEquals(LauncherCodePathPlan.CaptureMode.AGGREGATE, plan.captureMode());
        assertEquals(100, plan.scopeStartOrdinal());
        assertEquals(20, plan.maxMatchedScopes());
    }

    @Test
    void readsLegacyV5PlanAsTraceWithDefaultScopeWindow() throws Exception {
        Path planFile = Files.writeString(directory.resolve("legacy.json"), validPlan("5.0", ""));

        LauncherCodePathPlan plan = new CodePathPlanReader().read(planFile);

        assertEquals(LauncherCodePathPlan.CaptureMode.TRACE, plan.captureMode());
        assertEquals(1, plan.scopeStartOrdinal());
        assertEquals(10_000, plan.maxMatchedScopes());
    }

    @Test
    void rejectsUnknownPlanFields() throws Exception {
        Path planFile = Files.writeString(
                directory.resolve("unknown.json"), validPlan(",\"unknown\":true"));

        assertThrows(IOException.class, () -> new CodePathPlanReader().read(planFile));
    }

    private static String validPlan(String suffix) {
        return validPlan("6.0", suffix);
    }

    private static String validPlan(String version, String suffix) {
        return """
                {
                  "schemaVersion":"%s",
                  "planId":"plan-1",
                  "caseId":"case-1",
                  "analysisId":"analysis-1",
                  "targetTest":{"className":"fixture.TargetTest","methodName":"case1"},
                  "methodSelections":[{
                    "selector":{
                      "methodKey":"fixture.Service#solve()V",
                      "className":"fixture.Service",
                      "methodName":"solve",
                      "descriptor":"()V"
                    },
                    "projections":[{
                      "name":"waferId",
                      "source":"ARGUMENT",
                      "argumentIndex":0,
                      "fieldPath":["waferId"],
                      "required":true
                    }]
                  }],
                  "scopeMethodKey":"fixture.Service#solve()V",
                  "scopeConditions":[],
                  "budget":{"maxEvents":100,"maxBytes":4096,"timeoutMillis":30000},
                  "rationale":"fixture",
                  "intent":{
                    "questionToAnswer":"Which path ran?",
                    "hypothesis":"The selected method executes",
                    "basedOnEvidenceIds":[],
                    "expectedObservations":["The method is present in the runtime path"]
                  },
                  "createdAt":"2026-08-25T00:00:00Z"
                }
                """.formatted(version).trim().replace("\n}", suffix + "\n}");
    }
}
