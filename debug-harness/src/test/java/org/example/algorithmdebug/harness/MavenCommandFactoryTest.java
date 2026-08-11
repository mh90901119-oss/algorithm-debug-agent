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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenCommandFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildArgumentVectorWithoutInterpretingShellMetacharacters() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("maven tool.cmd"));
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        properties.put("test", "org.example.ScheduleTest#case1");
        properties.put("payload", "x&y;z|still-one-token");
        TestLaunchSpec spec = spec(properties, List.of("-Xmx256m"));
        MavenExecutionOptions options = new MavenExecutionOptions(
                executable,
                temporaryDirectory.resolve("stdout.log"),
                temporaryDirectory.resolve("stderr.log"),
                ProcessLimits.defaults());

        List<String> command = new MavenCommandFactory().create(spec, options);

        assertEquals(List.of(
                executable.toAbsolutePath().normalize().toString(),
                "-Dtest=org.example.ScheduleTest#case1",
                "-Dpayload=x&y;z|still-one-token",
                "-DargLine=-Xmx256m",
                "test"), command);
    }

    @Test
    void shouldRejectConflictingArgLineSources() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("mvn.cmd"));
        MavenExecutionOptions options = new MavenExecutionOptions(
                executable,
                temporaryDirectory.resolve("stdout.log"),
                temporaryDirectory.resolve("stderr.log"),
                ProcessLimits.defaults());

        HarnessException exception = assertThrows(HarnessException.class,
                () -> new MavenCommandFactory().create(
                        spec(Map.of("argLine", "-Xms128m"), List.of("-Xmx256m")),
                        options));

        assertEquals("HARNESS_LAUNCH_SPEC_CONFLICT", exception.code());
    }

    @Test
    void shouldRejectRelativeOrMissingMavenExecutable() {
        assertThrows(IllegalArgumentException.class, () -> new MavenExecutionOptions(
                Path.of("mvn"),
                temporaryDirectory.resolve("stdout.log"),
                temporaryDirectory.resolve("stderr.log"),
                ProcessLimits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new MavenExecutionOptions(
                temporaryDirectory.resolve("missing-mvn.cmd"),
                temporaryDirectory.resolve("stdout.log"),
                temporaryDirectory.resolve("stderr.log"),
                ProcessLimits.defaults()));
    }

    private TestLaunchSpec spec(Map<String, String> properties, List<String> jvmArguments) {
        ProjectDescriptor project = new ProjectDescriptor(
                new ProjectId("demo"),
                "Demo",
                temporaryDirectory.toAbsolutePath(),
                BuildTool.MAVEN,
                Path.of("pom.xml"));
        return new TestLaunchSpec(
                project,
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                List.of("test"),
                properties,
                jvmArguments,
                Duration.ofSeconds(30));
    }
}
