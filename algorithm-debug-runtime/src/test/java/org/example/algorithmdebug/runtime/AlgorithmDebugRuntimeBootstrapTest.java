package org.example.algorithmdebug.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlgorithmDebugRuntimeBootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void bootstrapCreatesOneCoordinatorWithEveryRegisteredAction() throws Exception {
        try (AlgorithmDebugRuntime runtime = bootstrap(Map.of())) {
            assertSame(runtime.coordinator(), runtime.services().coordinator());
            assertEquals(AnalysisActionType.values().length,
                    runtime.coordinator().registeredActionTypes().size());
            assertEquals(java.util.EnumSet.allOf(AnalysisActionType.class),
                    java.util.EnumSet.copyOf(runtime.coordinator().registeredActionTypes()));
        }
    }

    @Test
    void bootstrapDoesNotCreateWorkspaceState() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        assertFalse(Files.exists(workspace));

        try (AlgorithmDebugRuntime ignored = bootstrap(Map.of())) {
            assertFalse(Files.exists(workspace));
        }
    }

    @Test
    void coordinatedAnalysisBeginPreservesAtomicAnalysisPublication() throws Exception {
        try (AlgorithmDebugRuntime runtime = bootstrap(
                Map.of(), List.of(), List.of(new RuntimeTestAdapter()))) {
            Path workspace = temporaryDirectory.resolve("workspace");
            runtime.services().workspace().initialize(workspace);
            Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
            Files.createDirectories(repository.resolve(".git"));
            Path module = Files.createDirectories(repository.resolve("algorithm-module"));
            Files.writeString(module.resolve("pom.xml"), "<project/>");
            ProjectId projectId = runtime.services().project()
                    .register(workspace, module, Optional.empty())
                    .registration().projectId();
            CaseId caseId = new CaseId("case-runtime");
            AnalysisId analysisId = new AnalysisId("analysis-runtime");
            AnalysisIdentity identity = new AnalysisIdentity(projectId, caseId, analysisId);
            TargetTest targetTest = new TargetTest("fixture.TargetTest", "caseUnderTest");
            ProblemFrame frame = new ProblemFrame(
                    SchemaVersions.PROBLEM_FRAME,
                    new ProblemFrameId("problem-runtime"),
                    caseId,
                    analysisId,
                    "调度顺序错误",
                    "返回稳定顺序",
                    "返回了错误顺序",
                    targetTest,
                    List.of(new SourceAnchor(
                            "fixture.Scheduler", "schedule", "()V",
                            "src/main/java/fixture/Scheduler.java", 1, 20)),
                    List.of("target-test:fixture.TargetTest#caseUnderTest"),
                    List.of("候选选择分支是否错误"),
                    Instant.parse("2026-09-28T02:00:00Z"));
            AnalysisActionRequest<CoreActionInputs.AnalysisBegin> request =
                    new AnalysisActionRequest.Command<>(
                            SchemaVersions.ANALYSIS_ACTION_REQUEST,
                            AnalysisActionType.ANALYSIS_BEGIN,
                            identity,
                            new ActionTarget(
                                    "workspace-test", projectId, caseId, analysisId,
                                    Optional.empty(), Optional.empty(), Optional.empty()),
                            Optional.of(new OperationId("operation-runtime-begin")),
                            new CoreActionInputs.AnalysisBegin(
                                    frame, Optional.empty(), Optional.of("runtime-test")));

            var result = runtime.coordinator().execute(request);
            var replay = runtime.coordinator().execute(request);

            assertEquals(ActionOutcome.SUCCEEDED, result.outcome());
            assertEquals(ActionOutcome.SUCCEEDED, replay.outcome());
            assertTrue(((OperationReceipt) replay.data()).replayed());
            WorkspaceLayout layout = WorkspaceLayout.of(workspace);
            Path formalAnalysis = layout.projectCases(projectId)
                    .resolve("case-runtime/analyses/analysis-runtime");
            assertTrue(Files.isRegularFile(formalAnalysis.resolve("analysis-request.json")));
            assertTrue(Files.isRegularFile(formalAnalysis.resolve(
                    "investigation/events/1-problem-frame-problem-runtime.json")));
            assertFalse(Files.exists(formalAnalysis.resolve(
                    "operations/operation-runtime-begin")));
            assertTrue(Files.isRegularFile(layout.projectAnalysisBeginControlCases(projectId)
                    .resolve("case-runtime/analyses/analysis-runtime/operations/"
                            + "operation-runtime-begin/completed.json")));
        }
    }

    @Test
    void missingCollectorsExposeTypedUnavailableCapabilitiesWithoutNullServices() throws Exception {
        try (AlgorithmDebugRuntime runtime = bootstrap(Map.of())) {
            assertFalse(runtime.capability("codepath").available());
            assertEquals("CODEPATH_TOOL_NOT_CONFIGURED",
                    runtime.capability("codepath").code());
            assertFalse(runtime.capability("jdwp").available());
            assertEquals("JDWP_TOOL_NOT_CONFIGURED", runtime.capability("jdwp").code());
            assertTrue(runtime.services().collections() != null);
            assertTrue(runtime.services().jdwpCollections() != null);
        }
    }

    @Test
    void runtimePreservesTheResolvedToolchain() throws Exception {
        Path targetJava = executable("target/bin/java.exe");
        Path maven = executable("maven/bin/mvn.cmd");
        Map<String, String> environment = Map.of(
                "ADA_TARGET_JAVA_HOME", targetJava.getParent().getParent().toString(),
                "ADA_MAVEN_EXECUTABLE", maven.toString());

        try (AlgorithmDebugRuntime runtime = bootstrap(environment)) {
            assertEquals(targetJava.toAbsolutePath().normalize(),
                    runtime.toolchain().targetJavaExecutable());
            assertEquals(maven.toAbsolutePath().normalize(),
                    runtime.toolchain().mavenExecutable().orElseThrow());
        }
    }

    @Test
    void closeReleasesManagedResourcesOnceAndIsIdempotent() {
        AtomicInteger closes = new AtomicInteger();
        AlgorithmDebugRuntime runtime;
        try {
            runtime = bootstrap(
                    Map.of(),
                    List.of(
                            () -> closes.incrementAndGet(),
                            () -> closes.incrementAndGet()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }

        runtime.close();
        runtime.close();

        assertEquals(2, closes.get());
        assertTrue(runtime.closed());
    }

    @Test
    void runtimeModuleDeclaresNoEntryOrHostAdapterDependency() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertFalse(pom.contains("algorithm-debug-cli"));
        assertFalse(pom.contains("algorithm-debug-mcp-server"));
        assertFalse(pom.contains("integrations"));
    }

    private AlgorithmDebugRuntime bootstrap(Map<String, String> environment) throws Exception {
        return bootstrap(environment, List.of());
    }

    private AlgorithmDebugRuntime bootstrap(
            Map<String, String> environment, List<AutoCloseable> resources) throws Exception {
        return bootstrap(environment, resources, List.of());
    }

    private AlgorithmDebugRuntime bootstrap(
            Map<String, String> environment,
            List<AutoCloseable> resources,
            List<TargetProjectAdapter> adapters) throws Exception {
        Path agentJava = executable("agent/bin/java.exe");
        RuntimeBootstrapRequest request = new RuntimeBootstrapRequest(
                "workspace-test", temporaryDirectory.resolve("workspace"), environment,
                Clock.systemUTC(), () -> Runtime.version().feature(), agentJava,
                ";", true, AgentExecutionLog.disabled());
        return new AlgorithmDebugRuntimeBootstrap(() -> adapters, () -> resources)
                .bootstrap(request);
    }

    private Path executable(String relativePath) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.exists(file) ? file : Files.createFile(file);
    }

    private static final class RuntimeTestAdapter implements TargetProjectAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "runtime-test", "1.0", "Runtime test",
                    Set.of(AdapterCapability.BASELINE_EXECUTION));
        }

        @Override
        public ProjectDescriptor inspect(Path projectRoot) {
            return new ProjectDescriptor(
                    new ProjectId("adapter-project"), "Runtime test",
                    projectRoot.toAbsolutePath(), BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            throw new AssertionError("Analysis begin must not create a launch specification");
        }
    }
}
