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

    @TempDir
    Path directory;

    @Test
    void createsExactArgvWithoutShellComposition() throws Exception {
        Path jar = Files.write(directory.resolve("launcher.jar"), new byte[]{1, 2, 3});
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                Path.of("java"), jar, CodePathToolConfiguration.sha256(jar), "0.1.0", "launcher.Main");
        MethodPathCollectionRequest request = request(configuration.javaExecutable());

        List<String> argv = new CodePathCommandFactory(";").create(
                configuration, request, directory.resolve("raw.jsonl"));

        assertEquals(List.of(
                "java", "-cp", jar.toAbsolutePath() + ";classes;test-classes", "launcher.Main",
                "--test", "fixture.Test#case1", "--include", "fixture",
                "--trace", directory.resolve("raw.jsonl").toAbsolutePath().toString(),
                "--max-output-bytes", Long.toString(CollectionBudget.defaults().maxBytes()),
                "--max-events", Long.toString(CollectionBudget.defaults().maxEvents())), argv);
    }

    @Test
    void rejectsPinnedJarHashMismatch() throws Exception {
        Path jar = Files.write(directory.resolve("launcher.jar"), new byte[]{1, 2, 3});
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                Path.of("java"), jar, "0".repeat(64), "0.1.0", "launcher.Main");
        assertThrows(CodePathAdapterException.class, configuration::verifyTool);
    }

    private MethodPathCollectionRequest request(Path java) {
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.Test", "case1"), "a".repeat(64),
                List.of(new MethodSelector("fixture.Service#solve()V", "fixture.Service", "solve",
                        "()V", "b".repeat(64))), List.of("fixture"), "PACKAGE_SUPERSET",
                CollectionBudget.defaults(), 100, "定位", Instant.EPOCH);
        return new MethodPathCollectionRequest(
                plan.caseId(), plan.contextId(), plan.analysisId(), new RunId("run-1"), plan,
                new CollectionId("collection-1"), directory, directory, java,
                List.of("classes", "test-classes"), "fixture.Test#case1");
    }
}
