package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CoreDependencyBoundaryTest {
    @Test
    void coreDependsOnMethodPathSpiButNotCodePathImplementation() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>method-path-spi</artifactId>"));
        assertFalse(pom.contains("<artifactId>method-path-codepathtracer</artifactId>"));
    }
}
