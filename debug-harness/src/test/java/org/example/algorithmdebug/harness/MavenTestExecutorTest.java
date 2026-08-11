package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenTestExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRejectExistingLogBeforeStartingTargetProcess() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("mvn.cmd"));
        Path stdout = temporaryDirectory.resolve("stdout.log");
        Files.writeString(stdout, "existing");
        AtomicBoolean started = new AtomicBoolean();
        MavenTestExecutor executor = new MavenTestExecutor(
                new MavenCommandFactory(),
                new ProcessSupervisor(),
                new BoundedOutputCapture(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
                () -> 0L,
                builder -> {
                    started.set(true);
                    return builder.start();
                });

        HarnessException exception = assertThrows(HarnessException.class, () -> executor.execute(
                spec(),
                new MavenExecutionOptions(
                        executable,
                        stdout,
                        temporaryDirectory.resolve("stderr.log"),
                        ProcessLimits.defaults())));

        assertEquals("HARNESS_LOG_OPEN_FAILED", exception.code());
        assertFalse(started.get());
        assertEquals("existing", Files.readString(stdout));
    }

    private TestLaunchSpec spec() {
        return new TestLaunchSpec(
                new ProjectDescriptor(
                        new ProjectId("demo"), "Demo", temporaryDirectory.toAbsolutePath(),
                        BuildTool.MAVEN, Path.of("pom.xml")),
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(1));
    }
}
