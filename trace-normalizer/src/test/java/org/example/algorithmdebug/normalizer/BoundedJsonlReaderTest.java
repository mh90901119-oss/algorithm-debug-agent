package org.example.algorithmdebug.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedJsonlReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsLfCrlfAndFinalLineWithoutNewline() throws Exception {
        Path input = temporaryDirectory.resolve("events.jsonl");
        Files.writeString(input, "{\"id\":1}\r\n{\"id\":2}\n{\"id\":3}");
        List<String> observed = new ArrayList<>();

        new BoundedJsonlReader().read(
                input, 1024, 128, 10,
                (line, json) -> observed.add(line + ":" + json.path("id").asInt()));

        assertEquals(List.of("1:1", "2:2", "3:3"), observed);
    }

    @Test
    void rejectsRecordByUtf8BytesBeforeParsingIt() throws Exception {
        Path input = temporaryDirectory.resolve("oversized.jsonl");
        Files.writeString(input, "{\"value\":\"算法算法\"}\n", StandardCharsets.UTF_8);

        NormalizationException failure = assertThrows(
                NormalizationException.class,
                () -> new BoundedJsonlReader().read(input, 1024, 16, 10, (line, json) -> {}));

        assertEquals("NORMALIZE_RECORD_TOO_LARGE", failure.code());
        assertEquals(1, failure.jsonlLine());
    }

    @Test
    void decodesUtf8CharacterCrossingTheFixedReadBufferBoundary() throws Exception {
        Path input = temporaryDirectory.resolve("buffer-boundary.jsonl");
        String value = "a".repeat(8_181) + "算";
        Files.writeString(input, "{\"value\":\"" + value + "\"}\n", StandardCharsets.UTF_8);
        List<String> observed = new ArrayList<>();

        new BoundedJsonlReader().read(
                input, 16_384, 16_384, 1,
                (line, json) -> observed.add(json.path("value").asText()));

        assertEquals(List.of(value), observed);
    }

    @Test
    void rejectsMalformedUtf8Deterministically() throws Exception {
        Path input = temporaryDirectory.resolve("invalid-utf8.jsonl");
        Files.write(input, new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xC3, '"', '}', '\n'});

        NormalizationException failure = assertThrows(
                NormalizationException.class,
                () -> new BoundedJsonlReader().read(input, 1024, 128, 10, (line, json) -> {}));

        assertEquals("NORMALIZE_UTF8_INVALID", failure.code());
    }

    @Test
    void rejectsInvalidJsonWithExactLine() throws Exception {
        Path input = temporaryDirectory.resolve("invalid.jsonl");
        Files.writeString(input, "{\"id\":1}\nnot-json\n");

        NormalizationException failure = assertThrows(
                NormalizationException.class,
                () -> new BoundedJsonlReader().read(input, 1024, 128, 10, (line, json) -> {}));

        assertEquals("NORMALIZE_JSON_INVALID", failure.code());
        assertEquals(2, failure.jsonlLine());
    }

    @Test
    void rejectsInputAndRecordCountBeyondBudgets() throws Exception {
        Path input = temporaryDirectory.resolve("two.jsonl");
        Files.writeString(input, "{}\n{}\n");

        assertEquals("NORMALIZE_INPUT_TOO_LARGE", assertThrows(
                NormalizationException.class,
                () -> new BoundedJsonlReader().read(input, 3, 128, 10, (line, json) -> {})).code());
        assertEquals("NORMALIZE_RECORD_LIMIT_EXCEEDED", assertThrows(
                NormalizationException.class,
                () -> new BoundedJsonlReader().read(input, 1024, 128, 1, (line, json) -> {})).code());
    }
}
