package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleResultCaptureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void capturesOnlyResultProducedInsideRunWindow() throws Exception {
        Path output = temporaryDirectory.resolve("algorithm-output");
        Files.createDirectories(output);
        Files.writeString(output.resolve("history.txt"), "invalid");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        Path produced = output.resolve("dynamic-name.any");
        Files.writeString(produced, "{\"schedule\":13}");

        CapturedScheduleResult<TextSnapshot> captured = new ScheduleResultCapture<TextSnapshot>(
                snapshotter,
                1024 * 1024).capture(
                        before,
                        source,
                        parser(),
                        temporaryDirectory.resolve("run/result/gantt.json"));

        assertEquals(produced, captured.sourcePath());
        assertTrue(Files.isRegularFile(captured.capturedPath()));
        assertEquals(new JsonTokenContentHasher().sha256(captured.capturedPath()),
                captured.normalizedJsonSha256());
    }

    @Test
    void rejectsRunWithoutNewOrModifiedResult() throws Exception {
        Path output = temporaryDirectory.resolve("empty-output");
        Files.createDirectories(output);
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);

        HarnessException exception = assertThrows(HarnessException.class,
                () -> new ScheduleResultCapture<TextSnapshot>(snapshotter, 1024).capture(
                        before,
                        source,
                        parser(),
                        temporaryDirectory.resolve("run/result/gantt.json")));

        assertEquals("HARNESS_RESULT_NOT_PRODUCED", exception.code());
    }

    @Test
    void rejectsAmbiguousParseableResults() throws Exception {
        Path output = temporaryDirectory.resolve("ambiguous-output");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        Files.createDirectories(output);
        Files.writeString(output.resolve("one"), "{\"schedule\":1}");
        Files.writeString(output.resolve("two"), "{\"schedule\":2}");

        HarnessException exception = assertThrows(HarnessException.class,
                () -> new ScheduleResultCapture<TextSnapshot>(snapshotter, 1024).capture(
                        before,
                        source,
                        parser(),
                        temporaryDirectory.resolve("run/result/gantt.json")));

        assertEquals("HARNESS_RESULT_AMBIGUOUS", exception.code());
    }

    @Test
    void capturesFromProvidedStableAfterSnapshotWithoutRescanningDirectory() throws Exception {
        Path output = temporaryDirectory.resolve("stable-output");
        Files.createDirectories(output);
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        OutputDirectorySnapshot before = snapshotter.snapshot(source);
        Path produced = output.resolve("stable.json");
        Files.writeString(produced, "{\"schedule\":1}");
        OutputDirectorySnapshot stableAfter = snapshotter.snapshot(source);
        Files.writeString(output.resolve("late.json"), "schedule:2");

        CapturedScheduleResult<TextSnapshot> captured = new ScheduleResultCapture<TextSnapshot>(
                snapshotter, 1024).capture(
                        before,
                        stableAfter,
                        parser(),
                        temporaryDirectory.resolve("run/result/gantt.json"));

        assertEquals(produced, captured.sourcePath());
    }

    private static ScheduleResultParser<TextSnapshot> parser() {
        return path -> {
            try {
                String value = Files.readString(path);
                if (!value.startsWith("{\"schedule\":")) {
                    throw new AdapterException("TEST_INVALID", "不是调度结果");
                }
                return new TextSnapshot("1.0", value);
            } catch (java.io.IOException exception) {
                throw new AdapterException("TEST_IO", "读取失败", exception);
            }
        };
    }

    private record TextSnapshot(String schemaVersion, String value)
            implements ScheduleResultSnapshot {
    }
}
