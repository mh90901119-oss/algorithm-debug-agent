package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.SemanticHashStrategy;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseSessionRequest;
import org.example.algorithmdebug.casecore.CaseSessionService;
import org.example.algorithmdebug.casecore.CaseDigestReader;
import org.example.algorithmdebug.casecore.ContextInputProbe;
import org.example.algorithmdebug.casecore.ContextSnapshotBuilder;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.RunLog;
import org.example.algorithmdebug.harness.RunResult;
import org.example.algorithmdebug.harness.TargetTestExecutor;
import org.example.algorithmdebug.harness.TerminationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunApplicationServiceTest {

    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET = new TargetTest("a.b.TargetTest", "runs");

    @TempDir
    Path temporaryDirectory;

    private Path workspace;
    private Path module;
    private Path input;
    private Path output;
    private Path mavenExecutable;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;
    private ProjectRegistrationRepository registrations;
    private CaseOpenResult opened;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path source = module.resolve("src/test/java/a/b/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a.b; class TargetTest {}");
        input = module.resolve("input/case.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}");
        output = Files.createDirectories(module.resolve("output"));
        mavenExecutable = Files.createFile(temporaryDirectory.resolve("mvn.cmd"));

        Files.createDirectories(workspace.resolve("projects/project-1/cases"));
        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        registrations = new ProjectRegistrationRepository(mapper, writer);
        registrations.create(WorkspaceLayout.of(workspace), registration());
        CaseArchiveRepository archive = archive();
        ArrayDeque<String> ids = new ArrayDeque<>(List.of("1", "1", "1"));
        CaseSessionService sessions = new CaseSessionService(
                archive, new CaseDigestReader(archive), new ContextSnapshotBuilder(),
                new OpaqueIdGenerator(ids::removeFirst), Clock.fixed(TIME, ZoneOffset.UTC));
        opened = sessions.open(new CaseSessionRequest(
                Optional.empty(), PROJECT_ID, TARGET, "为什么调度结果不对？",
                module, module, "UNAVAILABLE", "21.0.4", "stub", "1.0",
                ContextInputProbe.present(input, "input/case.json")));
    }

    @Test
    void runRequestExistsBeforeExternalProcessStartsAndOutcomeIsArchived() throws Exception {
        Path requestPath = workspace.resolve(
                "projects/project-1/cases/case-1/runs/run-1/run-request.json");
        AtomicInteger starts = new AtomicInteger();
        TargetTestExecutor executor = (spec, options) -> {
            starts.incrementAndGet();
            assertTrue(Files.isRegularFile(requestPath));
            try {
                Files.writeString(options.stdoutLog(), "[INFO] build ok");
                Files.writeString(options.stderrLog(), "");
                Path reports = module.resolve("target/surefire-reports");
                Files.createDirectories(reports);
                Files.writeString(reports.resolve("TEST-a.b.TargetTest.xml"),
                        "<testsuite><testcase classname='a.b.TargetTest' name='runs'/></testsuite>");
                Files.writeString(output.resolve("gantt.json"), "schedule:ok");
            } catch (java.io.IOException failure) {
                throw new HarnessException("TEST_WRITE_FAILED", "test fixture write failed", failure);
            }
            return new RunResult(
                    RunCompletion.SUCCEEDED, OptionalInt.of(0), TIME, TIME.plusSeconds(1),
                    Duration.ofSeconds(1), 42,
                    new RunLog(options.stdoutLog(), 15, 0, false),
                    new RunLog(options.stderrLog(), 0, 0, false),
                    TerminationReport.notAttempted());
        };
        RunApplicationService service = new RunApplicationService(
                registrations, mapper, writer, new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "1"), Clock.fixed(TIME, ZoneOffset.UTC),
                executor, new RunArtifactArchiver(), mavenExecutable);

        RunOutcomeSummary outcome = service.execute(
                workspace, PROJECT_ID, opened.caseId(), opened.analysisId());

        assertEquals(1, starts.get());
        assertEquals(ProcessOutcome.SUCCEEDED, outcome.processOutcome());
        assertEquals(TestOutcome.PASSED, outcome.testOutcome());
        assertEquals(GanttOutcome.PRESENT, outcome.ganttOutcome());
        assertTrue(Files.isRegularFile(requestPath.getParent().resolve("run-outcome.json")));
        assertTrue(outcome.artifacts().stream().anyMatch(a -> "SUREFIRE_XML".equals(a.artifactType())));
        assertTrue(outcome.artifacts().stream().anyMatch(a -> "GANTT".equals(a.artifactType())));
    }

    @Test
    void processStartFailureStillCompletesStructuredRunOutcome() {
        TargetTestExecutor executor = (spec, options) -> {
            Path requestPath = workspace.resolve(
                    "projects/project-1/cases/case-1/runs/run-1/run-request.json");
            assertTrue(Files.isRegularFile(requestPath));
            throw new HarnessException(
                    "HARNESS_PROCESS_START_FAILED", "cannot start", new java.io.IOException("boom"));
        };
        RunApplicationService service = new RunApplicationService(
                registrations, mapper, writer, new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "1"), Clock.fixed(TIME, ZoneOffset.UTC),
                executor, new RunArtifactArchiver(), mavenExecutable);

        RunOutcomeSummary outcome = service.execute(
                workspace, PROJECT_ID, opened.caseId(), opened.analysisId());

        assertEquals(ProcessOutcome.NOT_STARTED, outcome.processOutcome());
        assertEquals(TestOutcome.NOT_EXECUTED, outcome.testOutcome());
        assertEquals("HARNESS_PROCESS_START_FAILED",
                outcome.agentFailure().orElseThrow().code());
        assertTrue(Files.isRegularFile(workspace.resolve(
                "projects/project-1/cases/case-1/runs/run-1/run-outcome.json")));
    }

    @Test
    void missingMavenStillArchivesANotStartedOutcome() {
        AtomicInteger starts = new AtomicInteger();
        RunApplicationService service = new RunApplicationService(
                registrations, mapper, writer, new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "1"), Clock.fixed(TIME, ZoneOffset.UTC),
                (spec, options) -> {
                    starts.incrementAndGet();
                    throw new AssertionError("Maven 缺失时不得启动外部进程");
                },
                new RunArtifactArchiver(), Optional.empty());

        RunOutcomeSummary outcome = service.execute(
                workspace, PROJECT_ID, opened.caseId(), opened.analysisId());

        assertEquals(0, starts.get());
        assertEquals(ProcessOutcome.NOT_STARTED, outcome.processOutcome());
        assertEquals(TestOutcome.NOT_EXECUTED, outcome.testOutcome());
        assertEquals("MAVEN_NOT_FOUND", outcome.agentFailure().orElseThrow().code());
        assertTrue(Files.isRegularFile(workspace.resolve(
                "projects/project-1/cases/case-1/runs/run-1/run-outcome.json")));
    }

    @Test
    void workspaceFailureIsExposedAsStableCaseRunError() {
        RunApplicationService service = new RunApplicationService(
                registrations, mapper, writer, new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "1"), Clock.fixed(TIME, ZoneOffset.UTC),
                (spec, options) -> {
                    throw new AssertionError("无效 Workspace 不得启动外部进程");
                },
                new RunArtifactArchiver(), mavenExecutable);

        CaseRunException failure = assertThrows(CaseRunException.class, () -> service.execute(
                workspace.getRoot(), PROJECT_ID, opened.caseId(), opened.analysisId()));

        assertEquals("WORKSPACE_PATH_INVALID", failure.code());
    }

    private CaseArchiveRepository archive() {
        return new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID), mapper, writer);
    }

    private ProjectRegistration registration() {
        String path = module.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "test", path, path, path,
                "pom.xml", "MAVEN", "a".repeat(64), TIME);
    }

    private record Snapshot(String schemaVersion, String value) implements ScheduleResultSnapshot {
    }

    private final class StubAdapter implements TargetProjectAdapter<Snapshot> {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "stub", "1.0", "stub", Set.of(
                    AdapterCapability.BASELINE_EXECUTION, AdapterCapability.INPUT_LOCATION,
                    AdapterCapability.SCHEDULE_RESULT, AdapterCapability.SEMANTIC_HASH));
        }

        @Override
        public ProjectDescriptor inspect(Path root) {
            return new ProjectDescriptor(
                    PROJECT_ID, "test", root.toAbsolutePath(), BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            return new TestLaunchSpec(
                    project, targetTest, runMode, List.of("test"),
                    Map.of("test", targetTest.selector()), List.of(), Duration.ofSeconds(10));
        }

        @Override
        public InputLocator inputLocator() {
            return (project, targetTest) -> Optional.of(input);
        }

        @Override
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest targetTest) {
            return new ScheduleResultSource(output, false);
        }

        @Override
        public ScheduleResultParser<Snapshot> scheduleResultParser() {
            return path -> {
                try {
                    return new Snapshot("1.0", Files.readString(path));
                } catch (java.io.IOException failure) {
                    throw new AdapterException("TEST_READ_FAILED", "test fixture read failed", failure);
                }
            };
        }

        @Override
        public SemanticHashStrategy<Snapshot> semanticHashStrategy() {
            return ignored -> "b".repeat(64);
        }
    }
}
