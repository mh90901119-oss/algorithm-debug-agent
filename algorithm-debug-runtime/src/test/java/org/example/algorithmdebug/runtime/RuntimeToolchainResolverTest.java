package org.example.algorithmdebug.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeToolchainResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void separatesAgentJavaTargetJavaAndExplicitMaven() throws Exception {
        Path agentJava = executable("agent/bin/java.exe");
        Path targetJava = executable("target/bin/java.exe");
        Path maven = executable("maven/bin/mvn.cmd");
        Map<String, String> environment = Map.of(
                "ADA_TARGET_JAVA_HOME", targetJava.getParent().getParent().toString(),
                "ADA_MAVEN_EXECUTABLE", maven.toString());

        RuntimeToolchain toolchain = new RuntimeToolchainResolver(environment, true)
                .resolve(agentJava);

        assertEquals(agentJava.toAbsolutePath().normalize(), toolchain.agentJavaExecutable());
        assertEquals(targetJava.toAbsolutePath().normalize(), toolchain.targetJavaExecutable());
        assertEquals(maven.toAbsolutePath().normalize(), toolchain.mavenExecutable().orElseThrow());
        assertNotEquals(toolchain.agentJavaExecutable(), toolchain.targetJavaExecutable());
    }

    @Test
    void resolvesWindowsEnvironmentNamesCaseInsensitively() throws Exception {
        Path agentJava = executable("agent/bin/java.exe");
        Path targetJava = executable("target/bin/java.exe");
        Map<String, String> environment = new HashMap<>();
        environment.put("ada_target_java_home", targetJava.getParent().getParent().toString());

        RuntimeToolchain toolchain = new RuntimeToolchainResolver(environment, true)
                .resolve(agentJava);

        assertEquals(targetJava.toAbsolutePath().normalize(), toolchain.targetJavaExecutable());
    }

    @Test
    void missingConfiguredExecutableFailsWithStableRuntimeCode() throws Exception {
        Path agentJava = executable("agent/bin/java");
        RuntimeBootstrapException failure = assertThrows(
                RuntimeBootstrapException.class,
                () -> new RuntimeToolchainResolver(
                        Map.of("ADA_MAVEN_EXECUTABLE",
                                temporaryDirectory.resolve("missing-maven").toString()), false)
                        .resolve(agentJava));

        assertEquals("RUNTIME_TOOLCHAIN_FILE_MISSING", failure.code());
    }

    private Path executable(String relativePath) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}
