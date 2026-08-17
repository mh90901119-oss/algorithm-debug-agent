package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIdGeneratorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateStableLowercaseProjectIdFromCanonicalModulePath() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("Algorithm-Scheduler")).toRealPath();
        ProjectIdGenerator generator = new ProjectIdGenerator();
        String expectedHash = firstTwelveSha256(module.toString());

        ProjectId first = generator.generate(module);
        ProjectId second = generator.generate(module);

        assertEquals(first, second);
        assertEquals("algorithm-scheduler-" + expectedHash, first.value());
        assertTrue(first.value().matches("[a-z0-9-]+"));
    }

    @Test
    void shouldChangeIdWhenCanonicalModulePathChanges() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("one/algorithm-scheduler")).toRealPath();
        Path second = Files.createDirectories(temporaryDirectory.resolve("two/algorithm-scheduler")).toRealPath();
        ProjectIdGenerator generator = new ProjectIdGenerator();

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    private static String firstTwelveSha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }
}
