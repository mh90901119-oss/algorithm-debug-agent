package org.example.algorithmdebug.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdwpToolConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void configuresCollectorFromJarPathWithoutBinaryFingerprint() throws Exception {
        Path collectorJar = Files.writeString(
                temporaryDirectory.resolve("jdwp-batch-collector.jar"), "fixture");

        JdwpToolConfiguration configuration =
                new JdwpToolConfiguration(collectorJar, "1.0.0");

        assertEquals(collectorJar.toAbsolutePath().normalize(), configuration.collectorJar());
        assertEquals("1.0.0", configuration.version());
    }
}
