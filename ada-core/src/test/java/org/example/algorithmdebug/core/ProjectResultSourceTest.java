package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectResultSourceTest {

    @Test
    void shouldResolveConfiguredDirectoryUnderModuleRoot() {
        ProjectRegistration registration = registration("output/algorithm-results");

        var source = ProjectResultSource.from(registration).orElseThrow();

        assertEquals(Path.of("D:/repo/module/output/algorithm-results").toAbsolutePath().normalize(),
                source.outputDirectory());
        assertEquals(false, source.recursive());
    }

    @Test
    void shouldUseConfiguredAbsoluteDirectoryWithoutRebasingItToTheModule() {
        ProjectRegistration registration = registration("D:/shared/algorithm-results");

        var source = ProjectResultSource.from(registration).orElseThrow();

        assertEquals(Path.of("D:/shared/algorithm-results").toAbsolutePath().normalize(),
                source.outputDirectory());
        assertEquals(false, source.recursive());
    }

    @Test
    void shouldReturnEmptyWhenProjectHasNoResultConfiguration() {
        assertTrue(ProjectResultSource.from(registration(null)).isEmpty());
    }

    private static ProjectRegistration registration(String resultDirectory) {
        return new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, new ProjectId("demo-project"), "demo",
                "D:/repo", "D:/repo/module", "D:/repo/module", "pom.xml", "MAVEN",
                resultDirectory, Instant.parse("2026-08-20T00:00:00Z"));
    }
}
