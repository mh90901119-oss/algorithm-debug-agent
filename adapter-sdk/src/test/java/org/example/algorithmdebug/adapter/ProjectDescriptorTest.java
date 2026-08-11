package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectDescriptorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldResolveRelativeBuildFileInsideAbsoluteProjectRoot() {
        ProjectDescriptor descriptor = new ProjectDescriptor(
                new ProjectId("demo"),
                "Demo Algorithm",
                tempDirectory,
                BuildTool.MAVEN,
                Path.of("pom.xml"));

        assertEquals(tempDirectory.toAbsolutePath().normalize(), descriptor.projectRoot());
        assertEquals(tempDirectory.resolve("pom.xml").toAbsolutePath().normalize(), descriptor.buildFile());
    }

    @Test
    void shouldRejectRelativeRootAndBuildFileEscapingProject() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectDescriptor(
                new ProjectId("demo"), "Demo", Path.of("relative"), BuildTool.MAVEN, Path.of("pom.xml")));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDescriptor(
                new ProjectId("demo"), "Demo", tempDirectory, BuildTool.MAVEN, Path.of("..", "pom.xml")));
    }
}

