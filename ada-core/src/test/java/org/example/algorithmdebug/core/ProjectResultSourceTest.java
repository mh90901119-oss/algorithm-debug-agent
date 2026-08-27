package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void shouldResolveRunDateTokenUsingTheConfiguredClockZone() {
        ProjectRegistration registration = registration("D:/log/scheduler/${runDate}/gant");
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-25T16:30:00Z"),
                ZoneId.of("Asia/Shanghai"));

        var source = ProjectResultSource.from(registration, clock).orElseThrow();

        assertEquals(Path.of("D:/log/scheduler/2026-08-26/gant").toAbsolutePath().normalize(),
                source.outputDirectory());
        assertEquals(false, source.recursive());
    }

    @Test
    void shouldRejectUnsupportedDynamicDirectoryToken() {
        ProjectRegistration registration = registration("D:/log/scheduler/${today}/gant");

        assertThrows(CaseRunException.class,
                () -> ProjectResultSource.from(registration, Clock.systemUTC()));
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
