package org.example.algorithmdebug.integration;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.core.CaseRunException;
import org.example.algorithmdebug.core.MavenExecutableLocator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseRunArchiveIntegrationTest {

    private static final String TEST_CLASS = "fixture.TargetTest";

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Scenario.class, names = "TEST_NOT_FOUND", mode = EnumSource.Mode.EXCLUDE)
    void archivesDeterministicFactsForIsolatedMavenScenario(Scenario scenario) throws Exception {
        Path scenarioRoot = Files.createDirectories(
                temporaryDirectory.resolve(scenario.name().toLowerCase(Locale.ROOT)));
        Path module = Files.createDirectories(scenarioRoot.resolve("module"));
        Path workspace = scenarioRoot.resolve("workspace");
        MavenFixture.create(module, scenario);
        Path maven = locateMaven();
        TargetTest target = new TargetTest(TEST_CLASS, scenario.targetMethod());
        FixtureAdapter adapter = new FixtureAdapter(scenario);
        ControlPlaneServices services = ControlPlaneServices.create(
                Clock.systemUTC(), () -> Runtime.version().feature(),
                System.getenv(), File.pathSeparator, isWindows(),
                List.of(adapter), Optional.of(maven));

        services.workspace().initialize(workspace);
        ProjectId projectId = new ProjectId(
                "fixture-" + scenario.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        ProjectRegistrationResult registration = services.project().register(
                workspace, module, Optional.of(projectId),
                Optional.of(module.resolve("output").toAbsolutePath().normalize().toString()));
        CaseOpenResult opened = services.cases().open(
                workspace, registration.registration().projectId(), target,
                "验证隔离 Maven 场景 " + scenario.name(), Optional.empty(),
                Optional.of("fixture-adapter"));
        services.algorithmInputs().capture(
                workspace, projectId, opened.caseId(), opened.analysisId());

        RunOutcomeSummary outcome = services.runs().execute(
                workspace, projectId, opened.caseId(), opened.analysisId());

        assertScenario(outcome, scenario);
        assertEquals(scenario.producesGantt() ? GanttOutcome.PRESENT : GanttOutcome.ABSENT,
                outcome.ganttOutcome());
        assertTrue(outcome.agentFailure().isEmpty());
        assertArchivedOutcomeAndArtifacts(
                workspace, projectId, outcome,
                scenario.hasSurefireReport(), scenario.producesGantt());
    }

    @org.junit.jupiter.api.Test
    void archivesEachSuccessfulGanttWithoutUsingItAsBaselineGate() throws Exception {
        Path scenarioRoot = Files.createDirectories(temporaryDirectory.resolve("gantt-comparison"));
        Path module = Files.createDirectories(scenarioRoot.resolve("module"));
        Path workspace = scenarioRoot.resolve("workspace");
        MavenFixture.create(module, Scenario.PASS);
        Path maven = locateMaven();
        TargetTest target = new TargetTest(TEST_CLASS, Scenario.PASS.targetMethod());
        FixtureAdapter stableAdapter = new FixtureAdapter(Scenario.PASS, "ok");
        ControlPlaneServices services = services(stableAdapter, Optional.of(maven));
        services.workspace().initialize(workspace);
        ProjectId projectId = new ProjectId("fixture-gantt-comparison");
        services.project().register(
                workspace, module, Optional.of(projectId),
                Optional.of(module.resolve("output").toAbsolutePath().normalize().toString()));
        CaseOpenResult opened = services.cases().open(
                workspace, projectId, target, "检查调度是否稳定", Optional.empty(),
                Optional.of("fixture-adapter"));
        services.algorithmInputs().capture(
                workspace, projectId, opened.caseId(), opened.analysisId());

        RunOutcomeSummary first = services.runs().execute(
                workspace, projectId, opened.caseId(), opened.analysisId());
        Files.writeString(module.resolve("src/test/java/fixture/TargetTest.java"),
                System.lineSeparator() + "// create a new source context",
                java.nio.file.StandardOpenOption.APPEND);
        CaseOpenResult reopened = services.cases().open(
                workspace, projectId, target, "代码变化后继续检查", Optional.of(opened.caseId()),
                Optional.of("fixture-adapter"));
        services.algorithmInputs().capture(
                workspace, projectId, opened.caseId(), reopened.analysisId());
        RunOutcomeSummary crossAnalysis = services.runs().execute(
                workspace, projectId, opened.caseId(), reopened.analysisId());
        ControlPlaneServices changedServices = services(
                new FixtureAdapter(Scenario.PASS, "changed"), Optional.of(maven));
        RunOutcomeSummary changed = changedServices.runs().execute(
                workspace, projectId, opened.caseId(), reopened.analysisId());

        assertEquals(ComparisonOutcome.NOT_COMPARED, first.comparisonOutcome());
        assertEquals(ComparisonOutcome.NOT_COMPARED, crossAnalysis.comparisonOutcome());
        assertEquals(ComparisonOutcome.NOT_COMPARED, changed.comparisonOutcome());
        ArtifactReference firstGantt = first.artifacts().stream()
                .filter(artifact -> "GANTT".equals(artifact.artifactType()))
                .findFirst().orElseThrow();
        ArtifactReference secondGantt = crossAnalysis.artifacts().stream()
                .filter(artifact -> "GANTT".equals(artifact.artifactType()))
                .findFirst().orElseThrow();
        ArtifactReference changedGantt = changed.artifacts().stream()
                .filter(artifact -> "GANTT".equals(artifact.artifactType()))
                .findFirst().orElseThrow();
        assertEquals(firstGantt.sha256(), secondGantt.sha256());
        assertNotEquals(secondGantt.sha256(), changedGantt.sha256());
        Path caseRoot = workspace.resolve("projects").resolve(projectId.value())
                .resolve("cases").resolve(opened.caseId().value());
        Path firstRunRoot = caseRoot.resolve("runs").resolve(first.runId().value());
        Path secondRunRoot = caseRoot.resolve("runs").resolve(crossAnalysis.runId().value());
        assertTrue(Files.notExists(firstRunRoot.resolve("run-result-fingerprint.json")));
        assertTrue(Files.notExists(secondRunRoot.resolve("run-result-fingerprint.json")));
        assertNotEquals(opened.analysisId(), reopened.analysisId());
        assertTrue(Files.isRegularFile(caseRoot.resolve("analyses")
                .resolve(opened.analysisId().value()).resolve("analysis-request.json")));
        assertTrue(Files.isRegularFile(caseRoot.resolve("analyses")
                .resolve(reopened.analysisId().value()).resolve("analysis-request.json")));
        assertTrue(Files.notExists(caseRoot.resolve("contexts")));
    }

    @org.junit.jupiter.api.Test
    void archivesRepeatedBusinessExceptionFingerprintsWithoutComparingOrdinaryRuns() throws Exception {
        Path scenarioRoot = Files.createDirectories(temporaryDirectory.resolve("failure-comparison"));
        Path module = Files.createDirectories(scenarioRoot.resolve("module"));
        Path workspace = scenarioRoot.resolve("workspace");
        MavenFixture.create(module, Scenario.BUSINESS_EXCEPTION);
        TargetTest target = new TargetTest(
                TEST_CLASS, Scenario.BUSINESS_EXCEPTION.targetMethod());
        ControlPlaneServices services = services(
                new FixtureAdapter(Scenario.BUSINESS_EXCEPTION), Optional.of(locateMaven()));
        services.workspace().initialize(workspace);
        ProjectId projectId = new ProjectId("fixture-failure-comparison");
        services.project().register(
                workspace, module, Optional.of(projectId),
                Optional.of(module.resolve("output").toAbsolutePath().normalize().toString()));
        CaseOpenResult opened = services.cases().open(
                workspace, projectId, target, "检查异常是否稳定", Optional.empty(),
                Optional.of("fixture-adapter"));
        services.algorithmInputs().capture(
                workspace, projectId, opened.caseId(), opened.analysisId());

        RunOutcomeSummary first = services.runs().execute(
                workspace, projectId, opened.caseId(), opened.analysisId());
        RunOutcomeSummary second = services.runs().execute(
                workspace, projectId, opened.caseId(), opened.analysisId());

        assertEquals(ComparisonOutcome.NOT_COMPARED, first.comparisonOutcome());
        assertEquals(ComparisonOutcome.NOT_COMPARED, second.comparisonOutcome());
        assertEquals(GanttOutcome.ABSENT, second.ganttOutcome());
        assertEquals(FailureCategory.TEST_ERROR,
                second.targetFailure().orElseThrow().category());
        assertTrue(second.artifacts().stream().anyMatch(
                artifact -> "RUN_RESULT_FINGERPRINT".equals(artifact.artifactType())));
        assertTrue(first.artifacts().stream().anyMatch(
                artifact -> "RUN_RESULT_FINGERPRINT".equals(artifact.artifactType())));
    }

    @org.junit.jupiter.api.Test
    void missingMavenDoesNotCreateFingerprintOrReference() throws Exception {
        Path scenarioRoot = Files.createDirectories(temporaryDirectory.resolve("missing-maven"));
        Path module = Files.createDirectories(scenarioRoot.resolve("module"));
        Path workspace = scenarioRoot.resolve("workspace");
        MavenFixture.create(module, Scenario.PASS);
        TargetTest target = new TargetTest(TEST_CLASS, Scenario.PASS.targetMethod());
        ControlPlaneServices services = services(
                new FixtureAdapter(Scenario.PASS), Optional.empty());
        services.workspace().initialize(workspace);
        ProjectId projectId = new ProjectId("fixture-missing-maven");
        services.project().register(
                workspace, module, Optional.of(projectId),
                Optional.of(module.resolve("output").toAbsolutePath().normalize().toString()));
        CaseOpenResult opened = services.cases().open(
                workspace, projectId, target, "Maven 不可用", Optional.empty(),
                Optional.of("fixture-adapter"));
        services.algorithmInputs().capture(
                workspace, projectId, opened.caseId(), opened.analysisId());

        RunOutcomeSummary outcome = services.runs().execute(
                workspace, projectId, opened.caseId(), opened.analysisId());

        assertEquals(ComparisonOutcome.NOT_COMPARED, outcome.comparisonOutcome());
        Path caseRoot = workspace.resolve("projects").resolve(projectId.value())
                .resolve("cases").resolve(opened.caseId().value());
        assertTrue(Files.notExists(caseRoot.resolve("runs").resolve(outcome.runId().value())
                .resolve("run-result-fingerprint.json")));
        assertTrue(Files.notExists(caseRoot.resolve("contexts")));
    }

    @org.junit.jupiter.api.Test
    void missingTargetStopsBeforeRunCreation() throws Exception {
        Path scenarioRoot = Files.createDirectories(temporaryDirectory.resolve("missing-target"));
        Path module = Files.createDirectories(scenarioRoot.resolve("module"));
        Path workspace = scenarioRoot.resolve("workspace");
        MavenFixture.create(module, Scenario.TEST_NOT_FOUND);
        TargetTest target = new TargetTest(TEST_CLASS, Scenario.TEST_NOT_FOUND.targetMethod());
        ControlPlaneServices services = services(
                new FixtureAdapter(Scenario.TEST_NOT_FOUND), Optional.of(locateMaven()));
        services.workspace().initialize(workspace);
        ProjectId projectId = new ProjectId("fixture-missing-target");
        services.project().register(
                workspace, module, Optional.of(projectId),
                Optional.of(module.resolve("output").toAbsolutePath().normalize().toString()));
        CaseOpenResult opened = services.cases().open(
                workspace, projectId, target, "check missing target", Optional.empty(),
                Optional.of("fixture-adapter"));

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                services.algorithmInputs().capture(
                        workspace, projectId, opened.caseId(), opened.analysisId()));

        assertEquals("TARGET_TEST_NOT_FOUND", failure.code());
        Path caseRoot = workspace.resolve("projects").resolve(projectId.value())
                .resolve("cases").resolve(opened.caseId().value());
        assertTrue(Files.notExists(caseRoot.resolve("runs")));
        assertTrue(Files.notExists(caseRoot.resolve("analyses")
                .resolve(opened.analysisId().value()).resolve("input")));
    }

    private static void assertScenario(RunOutcomeSummary outcome, Scenario scenario) {
        assertEquals(scenario.processOutcome, outcome.processOutcome());
        assertEquals(scenario.testOutcome, outcome.testOutcome());
        if (scenario.failureCategory == null) {
            assertTrue(outcome.targetFailure().isEmpty());
        } else {
            assertEquals(
                    scenario.failureCategory,
                    outcome.targetFailure().orElseThrow().category());
        }
    }

    private static void assertArchivedOutcomeAndArtifacts(
            Path workspace,
            ProjectId projectId,
            RunOutcomeSummary outcome,
            boolean expectSurefire,
            boolean expectGantt) throws Exception {
        Path caseRoot = workspace.resolve("projects").resolve(projectId.value())
                .resolve("cases").resolve(outcome.caseId().value());
        Path runRoot = caseRoot.resolve("runs").resolve(outcome.runId().value());
        assertTrue(Files.isRegularFile(runRoot.resolve("run-request.json")));
        Path outcomePath = runRoot.resolve("run-outcome.json");
        assertTrue(Files.isRegularFile(outcomePath));
        assertEquals(outcome, new BoundedDocumentMapper().readJson(
                outcomePath, RunOutcomeSummary.class));

        Set<String> types = outcome.artifacts().stream()
                .map(ArtifactReference::artifactType)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains("STDOUT"));
        assertTrue(types.contains("STDERR"));
        assertEquals(expectSurefire, types.contains("SUREFIRE_XML"));
        assertEquals(expectGantt, types.contains("GANTT"));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(projectId),
                new BoundedDocumentMapper(), new AtomicDocumentWriter());
        for (ArtifactReference artifact : outcome.artifacts()) {
            Path archived = caseRoot.resolve(artifact.relativePath()).normalize();
            assertTrue(archived.startsWith(runRoot));
            assertTrue(Files.isRegularFile(archived));
            assertEquals(artifact.sizeBytes(), Files.size(archived));
            assertEquals(artifact.sha256(), sha256(archived));
            assertEquals(artifact, archive.requireArtifactRegistration(
                    outcome.caseId(), artifact.artifactId()).artifact());
        }
    }

    private static Path locateMaven() {
        return new MavenExecutableLocator(
                System.getenv(), File.pathSeparator, isWindows())
                .locate(Optional.empty())
                .orElseThrow(() -> new AssertionError("集成测试要求本机 PATH 或 MAVEN_HOME 提供 Maven"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static ControlPlaneServices services(
            FixtureAdapter adapter, Optional<Path> maven) {
        return ControlPlaneServices.create(
                Clock.systemUTC(), () -> Runtime.version().feature(),
                System.getenv(), File.pathSeparator, isWindows(), List.of(adapter), maven);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private enum Scenario {
        PASS("caseUnderTest", ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, null, true, true),
        ASSERTION_FAILURE(
                "caseUnderTest", ProcessOutcome.FAILED, TestOutcome.FAILED,
                FailureCategory.TEST_FAILURE, true, true),
        BUSINESS_EXCEPTION(
                "caseUnderTest", ProcessOutcome.FAILED, TestOutcome.ERROR,
                FailureCategory.TEST_ERROR, true, false),
        COMPILE_FAILURE(
                "caseUnderTest", ProcessOutcome.FAILED, TestOutcome.NOT_EXECUTED,
                FailureCategory.BUILD_FAILURE, false, false),
        TEST_NOT_FOUND(
                "missingCase", ProcessOutcome.FAILED, TestOutcome.NOT_EXECUTED,
                FailureCategory.TEST_NOT_EXECUTED, false, false),
        TIMEOUT("caseUnderTest", ProcessOutcome.TIMED_OUT, TestOutcome.UNKNOWN, null, false, false);

        private final String targetMethod;
        private final ProcessOutcome processOutcome;
        private final TestOutcome testOutcome;
        private final FailureCategory failureCategory;
        private final boolean hasSurefireReport;
        private final boolean producesGantt;

        Scenario(
                String targetMethod,
                ProcessOutcome processOutcome,
                TestOutcome testOutcome,
                FailureCategory failureCategory,
                boolean hasSurefireReport,
                boolean producesGantt) {
            this.targetMethod = targetMethod;
            this.processOutcome = processOutcome;
            this.testOutcome = testOutcome;
            this.failureCategory = failureCategory;
            this.hasSurefireReport = hasSurefireReport;
            this.producesGantt = producesGantt;
        }

        String targetMethod() {
            return targetMethod;
        }

        boolean hasSurefireReport() {
            return hasSurefireReport;
        }

        boolean producesGantt() {
            return producesGantt;
        }
    }

    /** 只在临时目录生成最小 Maven/JUnit 工程，不引用开发机绝对路径。 */
    private static final class MavenFixture {

        private static final String POM = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>fixture</groupId>
                    <artifactId>case-run-fixture</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.3</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                                <configuration>
                                    <useModulePath>false</useModulePath>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        private MavenFixture() {
        }

        static void create(Path module, Scenario scenario) throws Exception {
            Files.createDirectory(module.resolve(".git"));
            Files.writeString(module.resolve("pom.xml"), POM);
            Path testSource = module.resolve("src/test/java/fixture/TargetTest.java");
            Files.createDirectories(testSource.getParent());
            Files.writeString(testSource, testSource(scenario));
            Path input = module.resolve("input/case-input.json");
            Files.createDirectories(input.getParent());
            Files.writeString(input, "{}");
            if (scenario == Scenario.COMPILE_FAILURE) {
                Path broken = module.resolve("src/main/java/fixture/Broken.java");
                Files.createDirectories(broken.getParent());
                Files.writeString(broken, "package fixture; public class Broken { syntax error }");
            }
        }

        private static String testSource(Scenario scenario) {
            String body = switch (scenario) {
                case PASS -> "org.junit.jupiter.api.Assertions.assertEquals(4, 2 + 2);";
                case ASSERTION_FAILURE ->
                        "org.junit.jupiter.api.Assertions.assertEquals(5, 2 + 2, \"schedule count\");";
                case BUSINESS_EXCEPTION ->
                        "throw new NullPointerException(\"missing route\");";
                case COMPILE_FAILURE -> "org.junit.jupiter.api.Assertions.assertTrue(true);";
                case TEST_NOT_FOUND -> "org.junit.jupiter.api.Assertions.assertTrue(true);";
                case TIMEOUT -> "Thread.sleep(60_000L);";
            };
            String method = scenario == Scenario.TEST_NOT_FOUND ? "differentCase" : "caseUnderTest";
            String ganttWrite = scenario.producesGantt ? """
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("output"));
                    String scheduleValue = System.getProperty("fixture.scheduleValue", "ok");
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of("output", "gantt-" + System.nanoTime() + ".json"),
                            "{\\\"schedule\\\":\\\"" + scheduleValue + "\\\"}");
                    """ : "";
            return """
                    package fixture;

                    class TargetTest {
                        @org.junit.jupiter.api.Test
                        void %s() throws Exception {
                            String algorithmInputFilePath = "input/case-input.json";
                            %s
                            %s
                        }
                    }
                    """.formatted(method, ganttWrite, body);
        }
    }

    private record FixtureSnapshot(String schemaVersion) implements ScheduleResultSnapshot {
    }

    private static final class FixtureAdapter implements TargetProjectAdapter {

        private final Scenario scenario;
        private final String scheduleValue;

        private FixtureAdapter(Scenario scenario) {
            this(scenario, "ok");
        }

        private FixtureAdapter(Scenario scenario, String scheduleValue) {
            this.scenario = scenario;
            this.scheduleValue = scheduleValue;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "fixture-adapter", "1.0", "Integration Maven Fixture",
                    Set.of(
                            AdapterCapability.BASELINE_EXECUTION,
                            AdapterCapability.CODE_PATH_COLLECTION));
        }

        @Override
        public ProjectDescriptor inspect(Path projectRoot) {
            return new ProjectDescriptor(
                    new ProjectId("fixture-project"), "Integration Maven Fixture",
                    projectRoot.toAbsolutePath().normalize(), BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            Duration timeout = scenario == Scenario.TIMEOUT
                    ? Duration.ofSeconds(2) : Duration.ofMinutes(2);
            return new TestLaunchSpec(
                    project, targetTest, runMode, List.of("-o", "test"),
                    Map.of(
                            "test", targetTest.selector(),
                            "failIfNoTests", "true",
                            "surefire.failIfNoSpecifiedTests", "true",
                            "fixture.scheduleValue", scheduleValue),
                    List.of(), timeout);
        }
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest targetTest) {
            return new ScheduleResultSource(project.projectRoot().resolve("output"), false);
        }
        public ScheduleResultParser<FixtureSnapshot> scheduleResultParser() {
            return path -> new FixtureSnapshot("1.0");
        }

    }
}
