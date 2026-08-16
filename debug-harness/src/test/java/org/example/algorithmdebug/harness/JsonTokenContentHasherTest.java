package org.example.algorithmdebug.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTokenContentHasherTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger fileSequence = new AtomicInteger();

    @Test
    void ignoresFormattingWhitespaceBetweenJsonTokens() throws Exception {
        assertEquals(
                hash("{\"name\":\"A B\",\"values\":[1,2]}"),
                hash("{\n  \"name\" : \"A B\",\n  \"values\" : [ 1, 2 ]\n}"));
    }

    @Test
    void preservesStringWhitespaceMemberOrderArrayOrderAndNumberText() throws Exception {
        assertNotEquals(hash("{\"v\":\"A B\"}"), hash("{\"v\":\"AB\"}"));
        assertNotEquals(hash("{\"a\":1,\"b\":2}"), hash("{\"b\":2,\"a\":1}"));
        assertNotEquals(hash("[1,2]"), hash("[2,1]"));
        assertNotEquals(hash("1"), hash("1.0"));
    }

    @Test
    void rejectsMalformedJsonAndMultipleRootValues() {
        HarnessException malformed = assertThrows(
                HarnessException.class, () -> hash("{\"a\":"));
        HarnessException multiple = assertThrows(
                HarnessException.class, () -> hash("{} {}"));

        assertEquals("GANTT_JSON_TOKEN_HASH_FAILED", malformed.code());
        assertEquals("GANTT_JSON_TOKEN_HASH_FAILED", multiple.code());
    }

    private String hash(String json) throws Exception {
        Path path = Files.writeString(
                temporaryDirectory.resolve("case-" + fileSequence.incrementAndGet() + ".json"), json);
        return new JsonTokenContentHasher().sha256(path);
    }
}
