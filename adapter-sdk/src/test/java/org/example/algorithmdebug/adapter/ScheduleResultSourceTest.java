package org.example.algorithmdebug.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleResultSourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesAbsoluteOutputDirectory() {
        ScheduleResultSource source = new ScheduleResultSource(
                temporaryDirectory.resolve("a/../results"), false);

        assertEquals(temporaryDirectory.resolve("results"), source.outputDirectory());
    }

    @Test
    void rejectsRelativeOutputDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleResultSource(Path.of("output"), false));
    }
}
