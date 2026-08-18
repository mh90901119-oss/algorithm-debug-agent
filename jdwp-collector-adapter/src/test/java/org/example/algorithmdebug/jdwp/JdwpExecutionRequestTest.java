package org.example.algorithmdebug.jdwp;

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

class JdwpExecutionRequestTest {
    @TempDir
    Path directory;

    @Test
    void rejectsMissingConfiguredCollectorJarWithoutRequiringAFingerprint() throws Exception {
        Path java = Files.createFile(directory.resolve("java.exe"));
        Path maven = Files.createFile(directory.resolve("mvn.cmd"));
        Path plan = Files.writeString(directory.resolve("plan.json"), "{}");
        ProjectDescriptor project = new ProjectDescriptor(
                new ProjectId("demo"), "Demo", directory.toAbsolutePath(),
                BuildTool.MAVEN, Path.of("pom.xml"));
        TestLaunchSpec launch = new TestLaunchSpec(
                project, new TargetTest("example.AlgorithmTest", "runs"), RunMode.JDWP,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(10));
        MavenExecutionOptions target = new MavenExecutionOptions(
                maven, directory.resolve("target-out.log"), directory.resolve("target-err.log"),
                ProcessLimits.defaults());

        assertThrows(IllegalArgumentException.class, () -> new JdwpExecutionRequest(
                launch, target, 50_001, java, directory.resolve("missing-collector.jar"), plan,
                directory.resolve("raw"), directory.resolve("collector-out.log"),
                directory.resolve("collector-err.log"), ProcessLimits.defaults(), 1_024,
                Duration.ofSeconds(1), Duration.ofSeconds(10)));
    }
}
