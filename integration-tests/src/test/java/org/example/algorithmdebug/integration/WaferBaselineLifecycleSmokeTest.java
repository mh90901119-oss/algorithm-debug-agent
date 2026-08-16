package org.example.algorithmdebug.integration;

import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.adapter.waferdemo.WaferDemoAdapter;
import org.example.algorithmdebug.adapter.waferdemo.WaferScheduleSnapshot;
import org.example.algorithmdebug.casecore.BaselineStabilityService;
import org.example.algorithmdebug.casecore.CaseWorkspace;
import org.example.algorithmdebug.contracts.BaselineVerification;
import org.example.algorithmdebug.contracts.CaseFingerprint;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.BaselineStabilityState;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.harness.CapturedScheduleResult;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.MavenTestExecutor;
import org.example.algorithmdebug.harness.OutputDirectorySnapshotter;
import org.example.algorithmdebug.harness.OutputStabilityPolicy;
import org.example.algorithmdebug.harness.OutputStabilityWaiter;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.ScheduleProducingTestRunner;
import org.example.algorithmdebug.harness.ScheduleResultCapture;
import org.example.algorithmdebug.harness.ScheduleRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferBaselineLifecycleSmokeTest {

    private static final TargetTest TARGET_TEST = new TargetTest(
            "org.example.scheduler.wafer.WaferSchedulingReproductionTest",
            "reproduceComplexSchedulingFromTimestampedInput");

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "wafer.demo.projectRoot", matches = ".+")
    void capturesTwoRealRunsAndMarksBaselineStable() throws Exception {
        Path projectRoot = Path.of(System.getProperty("wafer.demo.projectRoot"));
        WaferDemoAdapter adapter = new WaferDemoAdapter();
        ProjectDescriptor project = adapter.inspect(projectRoot);
        TestLaunchSpec launchSpec = adapter.createLaunchSpec(project, TARGET_TEST, RunMode.BASELINE);
        ScheduleResultSource source = adapter.scheduleResultSource(project, TARGET_TEST);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(10_000);
        ScheduleResultCapture<WaferScheduleSnapshot> capture =
                new ScheduleResultCapture<>(snapshotter, 100L * 1024 * 1024);
        ScheduleProducingTestRunner<WaferScheduleSnapshot> runner =
                new ScheduleProducingTestRunner<>(
                        new MavenTestExecutor(),
                        snapshotter,
                        new OutputStabilityWaiter(snapshotter, OutputStabilityPolicy.defaults()),
                        capture);
        CaseWorkspace workspace = CaseWorkspace.create(
                temporaryDirectory.resolve("cases"), new CaseId("CASE-WAFER-SMOKE"));

        CapturedScheduleResult<WaferScheduleSnapshot> first = runAndCapture(
                launchSpec, source, runner,
                workspace.createRun(new RunId("RUN-001")));
        CapturedScheduleResult<WaferScheduleSnapshot> second = runAndCapture(
                launchSpec, source, runner,
                workspace.createRun(new RunId("RUN-002")));

        CaseFingerprint fingerprint = new CaseFingerprint(
                TARGET_TEST.selector(),
                "working-tree-smoke",
                sha256(projectRoot.resolve(
                        "src/test/java/org/example/scheduler/wafer/WaferSchedulingReproductionTest.java")),
                sha256(projectRoot.resolve("input/cases/20260810101501.json")),
                sha256(projectRoot.resolve("pom.xml")),
                System.getProperty("java.version"),
                adapter.descriptor().adapterId(),
                adapter.descriptor().adapterVersion());
        BaselineStabilityService stability = new BaselineStabilityService(2);
        BaselineVerification verification = stability.start(
                fingerprint, new RunId("RUN-001"), first.semanticHash());
        verification = stability.record(
                verification, new RunId("RUN-002"), second.semanticHash());

        assertEquals(first.semanticHash(), second.semanticHash());
        assertEquals(BaselineStabilityState.BASELINE_STABLE, verification.state());
        assertEquals(165, second.snapshot().operations().size());
        assertTrue(Files.isRegularFile(
                workspace.caseRoot().resolve("runs/RUN-001/result/gantt.json")));
        assertTrue(Files.isRegularFile(
                workspace.caseRoot().resolve("runs/RUN-002/result/gantt.json")));
    }

    private CapturedScheduleResult<WaferScheduleSnapshot> runAndCapture(
            TestLaunchSpec spec,
            ScheduleResultSource source,
            ScheduleProducingTestRunner<WaferScheduleSnapshot> runner,
            Path runDirectory) throws Exception {
        ScheduleRunResult<WaferScheduleSnapshot> result = runner.run(
                spec,
                new MavenExecutionOptions(
                        mavenExecutable(),
                        runDirectory.resolve("logs/stdout.log"),
                        runDirectory.resolve("logs/stderr.log"),
                        ProcessLimits.defaults()),
                source,
                adapterParser(),
                adapterHashStrategy(),
                runDirectory.resolve("result/gantt.json"));
        assertEquals(RunCompletion.SUCCEEDED, result.run().completion(),
                () -> "目标 UT 失败，stdout=" + result.run().stdout().path()
                        + ", stderr=" + result.run().stderr().path());
        assertEquals(0, result.run().exitCode().orElseThrow());
        assertTrue(Files.isRegularFile(result.run().stdout().path()));
        assertTrue(Files.isRegularFile(result.run().stderr().path()));
        assertTrue(result.run().termination().survivingProcessIds().isEmpty());
        return result.scheduleResult().orElseThrow();
    }

    private static Path mavenExecutable() {
        String configured = System.getProperty("ada.maven.executable");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "mvn.cmd"
                : "mvn";
        return Path.of(System.getProperty("maven.home"), "bin", executable)
                .toAbsolutePath()
                .normalize();
    }

    private static org.example.algorithmdebug.adapter.ScheduleResultParser<WaferScheduleSnapshot>
            adapterParser() {
        return new WaferDemoAdapter().scheduleResultParser();
    }

    private static org.example.algorithmdebug.adapter.SemanticHashStrategy<WaferScheduleSnapshot>
            adapterHashStrategy() {
        return new WaferDemoAdapter().semanticHashStrategy();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
