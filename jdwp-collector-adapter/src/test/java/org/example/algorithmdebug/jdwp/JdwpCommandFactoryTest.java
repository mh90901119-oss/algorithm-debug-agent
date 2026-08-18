package org.example.algorithmdebug.jdwp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdwpCommandFactoryTest {
    @TempDir
    Path directory;

    @Test
    void injectsLoopbackSuspendedJdwpIntoTargetArgv() throws Exception {
        Path maven = Files.createFile(directory.resolve("mvn.cmd"));
        TestLaunchSpec launch = launch(List.of("-Xmx256m"));
        MavenExecutionOptions options = new MavenExecutionOptions(
                maven, directory.resolve("target-out.log"), directory.resolve("target-err.log"),
                ProcessLimits.defaults());

        List<String> argv = new JdwpTargetCommandFactory().create(launch, options, 51234);

        assertEquals(List.of(
                maven.toString(),
                "-Dtest=org.example.ScheduleTest#case1",
                "-DargLine=-Xmx256m -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:51234",
                "test"), argv);
    }

    @Test
    void rejectsExistingJdwpAgentAndNonJdwpRunMode() throws Exception {
        Path maven = Files.createFile(directory.resolve("mvn.cmd"));
        MavenExecutionOptions options = new MavenExecutionOptions(
                maven, directory.resolve("target-out.log"), directory.resolve("target-err.log"),
                ProcessLimits.defaults());
        assertThrows(IllegalArgumentException.class, () -> new JdwpTargetCommandFactory().create(
                launch(List.of("-agentlib:jdwp=unsafe")), options, 51234));
        TestLaunchSpec baseline = new TestLaunchSpec(
                project(), new TargetTest("org.example.ScheduleTest", "case1"), RunMode.BASELINE,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class,
                () -> new JdwpTargetCommandFactory().create(baseline, options, 51234));
    }

    @Test
    void buildsExactCollectorArgvWithoutShell() throws Exception {
        Path java = Files.createFile(directory.resolve("java.exe"));
        Path jar = Files.createFile(directory.resolve("collector.jar"));
        Path plan = Files.createFile(directory.resolve("collector-plan.json"));
        Path output = directory.resolve("raw");

        assertEquals(List.of(
                java.toString(), "--add-modules", "jdk.jdi", "-jar", jar.toString(), "collect",
                "--plan", plan.toString(), "--host", "127.0.0.1", "--port", "51234",
                "--output", output.toString()),
                new JdwpCollectorCommandFactory().create(java, jar, plan, output, 51234));
    }

    private TestLaunchSpec launch(List<String> jvmArguments) {
        return new TestLaunchSpec(
                project(), new TargetTest("org.example.ScheduleTest", "case1"), RunMode.JDWP,
                List.of("test"), Map.of("test", "org.example.ScheduleTest#case1"),
                jvmArguments, Duration.ofSeconds(30));
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                new ProjectId("demo"), "Demo", directory.toAbsolutePath(), BuildTool.MAVEN,
                Path.of("pom.xml"));
    }
}
