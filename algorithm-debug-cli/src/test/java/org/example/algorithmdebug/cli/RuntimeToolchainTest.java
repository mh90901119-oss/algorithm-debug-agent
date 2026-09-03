package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeToolchainTest {
    @TempDir Path directory;

    @Test
    void selectsConfiguredTargetJavaAndMavenWithoutChangingAgentJava() throws Exception {
        Path javaHome = Files.createDirectories(directory.resolve("jdk-17"));
        Path java = Files.createDirectories(javaHome.resolve("bin")).resolve("java.exe");
        Files.createFile(java);
        Path maven = Files.createFile(directory.resolve("mvn.cmd"));
        Path agentJava = Files.createFile(directory.resolve("agent-java.exe"));

        RuntimeToolchain toolchain = RuntimeToolchain.resolve(
                Map.of(
                        "ADA_TARGET_JAVA_HOME", javaHome.toString(),
                        "ADA_MAVEN_EXECUTABLE", maven.toString()),
                agentJava,
                true);

        assertEquals(agentJava.toAbsolutePath().normalize(), toolchain.agentJavaExecutable());
        assertEquals(java.toAbsolutePath().normalize(), toolchain.targetJavaExecutable());
        assertEquals(maven.toAbsolutePath().normalize(), toolchain.mavenExecutable().orElseThrow());
    }

    @Test
    void fallsBackToAgentJavaAndRejectsInvalidConfiguredToolchain() throws Exception {
        Path agentJava = Files.createFile(directory.resolve("java.exe"));

        RuntimeToolchain fallback = RuntimeToolchain.resolve(Map.of(), agentJava, true);

        assertEquals(agentJava.toAbsolutePath().normalize(), fallback.targetJavaExecutable());
        assertEquals(java.util.Optional.empty(), fallback.mavenExecutable());
        CliStartupException failure = assertThrows(CliStartupException.class, () -> RuntimeToolchain.resolve(
                Map.of("ADA_TARGET_JAVA_HOME", directory.resolve("missing").toString()),
                agentJava,
                true));
        assertEquals("CLI_TOOLCHAIN_FILE_MISSING", failure.code());
    }
}
