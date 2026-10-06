package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedSourceWindowReaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void sourceWindowHonorsLineAndUtf8ByteLimitsAndHashesWholeFile() throws Exception {
        Path source = writeJava("Sample.java", "one\n中文\nthree\nfour\n");
        SourceAnchor anchor = anchor("Sample", "run", "src/main/java/fixture/Sample.java", 1, 4);
        SourceQueryBudget budget = new SourceQueryBudget(10, 10, 3, 3, 3, 7);

        BoundedSourceWindowReader.Result result = new BoundedSourceWindowReader().read(
                temporaryDirectory, anchor, budget);

        assertEquals("one\n中", result.window().text());
        assertEquals(1, result.window().fromLine());
        assertEquals(2, result.window().toLine());
        assertEquals(sha256(source), result.window().sourceSha256());
        assertTrue(result.truncated());
        assertEquals(
                SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name(),
                result.limitations().getFirst());
    }

    @Test
    void sensitiveNonJavaFileIsRejected() throws Exception {
        Path secret = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secret, "token=secret", StandardCharsets.UTF_8);
        SourceAnchor anchor = anchor("Secret", "read", "secret.txt", 1, 1);

        SourceQueryException failure = assertThrows(
                SourceQueryException.class,
                () -> new BoundedSourceWindowReader().read(
                        temporaryDirectory, anchor, SourceQueryBudget.defaults()));

        assertEquals(SourceQueryErrorCode.SOURCE_QUERY_PATH_OUTSIDE_WORKSPACE, failure.code());
    }

    @Test
    void traversalAbsolutePathAndMalformedUtf8AreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> anchor("Escape", "run", "../Escape.java", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> anchor("Escape", "run", "C:/outside/Escape.java", 1, 1));
        Path malformed = temporaryDirectory.resolve("src/main/java/fixture/Malformed.java");
        Files.createDirectories(malformed.getParent());
        Files.write(malformed, new byte[] {(byte) 0xC3, (byte) 0x28});

        SourceQueryException failure = assertThrows(
                SourceQueryException.class,
                () -> new BoundedSourceWindowReader().read(
                        temporaryDirectory,
                        anchor("Malformed", "run",
                                "src/main/java/fixture/Malformed.java", 1, 1),
                        SourceQueryBudget.defaults()));

        assertEquals(SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE, failure.code());
    }

    @Test
    void veryLongSingleLineReturnsOnlyTheBoundedPrefix() throws Exception {
        writeJava("Large.java", "x".repeat(100_000));
        SourceQueryBudget budget = new SourceQueryBudget(10, 10, 3, 3, 3, 17);

        BoundedSourceWindowReader.Result result = new BoundedSourceWindowReader().read(
                temporaryDirectory,
                anchor("Large", "run", "src/main/java/fixture/Large.java", 1, 1),
                budget);

        assertEquals("x".repeat(17), result.window().text());
        assertTrue(result.truncated());
        assertEquals(List.of(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name()),
                result.limitations());
    }

    @Test
    void symlinkEscapeIsRejectedWhenPlatformSupportsSymlinks() throws Exception {
        Path outside = temporaryDirectory.resolveSibling("outside-source.java");
        Files.writeString(outside, "class Outside {}", StandardCharsets.UTF_8);
        Path link = temporaryDirectory.resolve("src/main/java/fixture/Escape.java");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException failure) {
            Assumptions.abort("Symbolic links are not available: " + failure.getClass().getSimpleName());
        }

        SourceQueryException failure = assertThrows(
                SourceQueryException.class,
                () -> new BoundedSourceWindowReader().read(
                        temporaryDirectory,
                        anchor("Escape", "run", "src/main/java/fixture/Escape.java", 1, 1),
                        SourceQueryBudget.defaults()));

        assertEquals(SourceQueryErrorCode.SOURCE_QUERY_PATH_OUTSIDE_WORKSPACE, failure.code());
    }

    private Path writeJava(String fileName, String content) throws IOException {
        Path path = temporaryDirectory.resolve("src/main/java/fixture").resolve(fileName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static SourceAnchor anchor(
            String className, String methodName, String path, int from, int to) {
        return new SourceAnchor("fixture." + className, methodName, "()V", path, from, to);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
