package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathCommandFactoryTest {
    @TempDir Path directory;

    @Test
    void passesArchivedPlanAndTraceWithoutPackageScope() throws Exception {
        Path jar = Files.write(directory.resolve("launcher.jar"), new byte[]{1, 2, 3});
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                Path.of("java"), jar, "0.1.0", "launcher.Main");
        Path plan = directory.resolve("request/plan.json");

        List<String> argv = new CodePathCommandFactory(";").create(
                configuration, request(configuration.javaExecutable()), plan, directory.resolve("raw.jsonl"));

        assertEquals(List.of(
                "java", "-cp", jar.toAbsolutePath() + ";classes;test-classes", "launcher.Main",
                "--plan", plan.toAbsolutePath().toString(),
                "--trace", directory.resolve("raw.jsonl").toAbsolutePath().toString()), argv);
    }

    @Test
    void acceptsReadableLauncherWithoutPinnedHash() throws Exception {
        Path jar = Files.write(directory.resolve("launcher.jar"), new byte[]{1, 2, 3});
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                Path.of("java"), jar, "0.1.0", "launcher.Main");
        Files.write(jar, new byte[]{4, 5, 6});

        configuration.verifyTool();
    }

    private MethodPathCollectionRequest request(Path java) {
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.Test", "case1"),
                List.of(new MethodSelector("fixture.Service#solve()V", "fixture.Service", "solve", "()V")),
                CollectionBudget.defaults(), "定位目标方法", Instant.EPOCH);
        return new MethodPathCollectionRequest(
                plan.caseId(), plan.contextId(), plan.analysisId(), new RunId("run-1"), plan,
                new CollectionId("collection-1"), directory, directory, java,
                List.of("classes", "test-classes"), "fixture.Test#case1");
    }
}
