package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestLaunchSpecTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldDefensivelyCopyLaunchArgumentsAndPreservePropertyOrder() {
        List<String> goals = new ArrayList<>(List.of("test"));
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("test", "org.example.ScheduleTest#case1");
        properties.put("failIfNoTests", "true");
        List<String> jvmArguments = new ArrayList<>(List.of("-Xmx1g"));

        TestLaunchSpec spec = new TestLaunchSpec(
                project(),
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                goals,
                properties,
                jvmArguments,
                Duration.ofMinutes(2));
        goals.add("package");
        properties.put("skipTests", "true");
        jvmArguments.add("-agentlib:jdwp=unsafe");

        assertEquals(List.of("test"), spec.mavenGoals());
        assertEquals(List.of("test", "failIfNoTests"), spec.mavenProperties().keySet().stream().toList());
        assertEquals(List.of("-Xmx1g"), spec.jvmArguments());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.mavenProperties().put("mutate", "true"));
    }

    @Test
    void shouldRejectUnsafeGoalPropertyAndTimeout() {
        assertThrows(IllegalArgumentException.class, () -> spec(List.of("clean test"), Map.of(), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of("test"), Map.of("bad key", "x"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of("test"), Map.of(), Duration.ZERO));
    }

    @Test
    void shouldRejectJvmArgumentThatCannotBeEncodedAsSingleSurefireToken() {
        assertThrows(IllegalArgumentException.class, () -> new TestLaunchSpec(
                project(),
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                List.of("test"),
                Map.of(),
                List.of("-javaagent=directory with spaces/agent.jar"),
                Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TestLaunchSpec(
                project(),
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                List.of("test"),
                Map.of(),
                List.of("-Dline=first\nsecond"),
                Duration.ofSeconds(1)));
    }

    private TestLaunchSpec spec(List<String> goals, Map<String, String> properties, Duration timeout) {
        return new TestLaunchSpec(
                project(),
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                goals,
                properties,
                List.of(),
                timeout);
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                new ProjectId("demo"), "Demo", tempDirectory, BuildTool.MAVEN, Path.of("pom.xml"));
    }
}
