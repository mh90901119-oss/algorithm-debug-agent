package org.example.algorithmdebug.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduleResultOriginalNameCaptureTest {

    @TempDir Path temporaryDirectory;

    @Test
    void copiesTheOnlyChangedResultIntoADirectoryUsingTheSourceFileName() throws Exception {
        Path output = Files.createDirectory(temporaryDirectory.resolve("output"));
        Path archive = Files.createDirectory(temporaryDirectory.resolve("archive"));
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        Path gantt = output.resolve("20260901153022.json");
        Files.writeString(gantt, "{\"schemaVersion\":\"1.0\"}");
        OutputDirectorySnapshot after = snapshotter.snapshot(source);
        ScheduleResultCapture<TestSnapshot> capture = new ScheduleResultCapture<>(snapshotter, 1024);

        CapturedScheduleResult<TestSnapshot> result = capture.capture(
                before, after, ignored -> new TestSnapshot("1.0"), archive);

        assertEquals("20260901153022.json", result.capturedPath().getFileName().toString());
        assertEquals(archive.resolve("20260901153022.json"), result.capturedPath());
        assertTrue(Files.isRegularFile(result.capturedPath()));
    }

    private record TestSnapshot(String schemaVersion) implements ScheduleResultSnapshot { }
}
