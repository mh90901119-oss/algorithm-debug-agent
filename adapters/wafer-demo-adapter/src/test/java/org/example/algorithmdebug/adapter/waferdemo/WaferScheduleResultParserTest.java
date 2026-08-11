package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaferScheduleResultParserTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldParseFixtureAndIgnoreFutureOptionalField() throws Exception {
        Path fixture = Path.of(getClass().getResource("/wafer-result-fixture.json").toURI());

        WaferScheduleSnapshot snapshot = new WaferScheduleResultParser().parse(fixture);

        assertEquals("1.0", snapshot.schemaVersion());
        assertEquals("FIXTURE-001", snapshot.snapshotId());
        assertEquals(2, snapshot.operations().size());
        assertEquals(6, snapshot.makespan());
        assertEquals("LP1", snapshot.finalWaferLocations().get("W1"));
    }

    @Test
    void shouldRejectOperationWithInconsistentDuration() throws Exception {
        String json = Files.readString(
                Path.of(getClass().getResource("/wafer-result-fixture.json").toURI()),
                StandardCharsets.UTF_8).replace("\"duration\": 5", "\"duration\": 4");
        Path invalid = tempDirectory.resolve("invalid-result.json");
        Files.writeString(invalid, json, StandardCharsets.UTF_8);

        AdapterException exception = assertThrows(AdapterException.class,
                () -> new WaferScheduleResultParser().parse(invalid));

        assertEquals("ADAPTER_RESULT_PARSE_FAILED", exception.code());
    }

    @Test
    void shouldReportMissingResultWithStableCode() {
        AdapterException exception = assertThrows(AdapterException.class,
                () -> new WaferScheduleResultParser().parse(tempDirectory.resolve("missing.json")));

        assertEquals("ADAPTER_RESULT_NOT_FOUND", exception.code());
    }
}

