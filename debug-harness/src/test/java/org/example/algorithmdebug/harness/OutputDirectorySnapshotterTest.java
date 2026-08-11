package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputDirectorySnapshotterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsFilesAddedAndModifiedInsideRunWindow() throws Exception {
        Path output = temporaryDirectory.resolve("output");
        Files.createDirectories(output);
        Path existing = output.resolve("existing.json");
        Files.writeString(existing, "old");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);

        Files.writeString(existing, "new-content");
        Files.writeString(output.resolve("created.any"), "new");
        OutputDirectorySnapshot after = snapshotter.snapshot(source);

        assertEquals(2, after.changedSince(before).size());
        assertTrue(after.changedSince(before).stream()
                .anyMatch(path -> path.getFileName().toString().equals("existing.json")));
    }
}
