package org.example.algorithmdebug.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliCommandExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void questionFileUsesStrictUtf8AndSixtyFourKibibyteBudget() throws Exception {
        Path valid = Files.writeString(temporaryDirectory.resolve("valid.txt"), "为什么失败？");
        Path oversized = Files.write(
                temporaryDirectory.resolve("large.txt"), new byte[65_537]);
        Path malformed = Files.write(
                temporaryDirectory.resolve("malformed.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});

        assertEquals("为什么失败？", CliCommandExecutor.readQuestion(valid));
        assertThrows(CliInputException.class, () -> CliCommandExecutor.readQuestion(oversized));
        assertThrows(CliInputException.class, () -> CliCommandExecutor.readQuestion(malformed));
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readQuestion(temporaryDirectory.resolve("missing.txt")));
    }

    @Test
    void utf8BomIsNotArchivedAsQuestionContent() throws Exception {
        byte[] question = "问题".getBytes(StandardCharsets.UTF_8);
        ByteBuffer content = ByteBuffer.allocate(question.length + 3)
                .put(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})
                .put(question);
        Path file = Files.write(temporaryDirectory.resolve("bom.txt"), content.array());

        assertEquals("问题", CliCommandExecutor.readQuestion(file));
    }

    @Test
    void codePathRequestFileRejectsUtf16EvenWhenJsonParserCouldDetectIt() throws Exception {
        String json = """
                {"planId":"plan-1","selectedMethodKeys":["fixture.Test#case1()V"],
                 "rationale":"定位","budget":{"maxEvents":100,"maxBytes":1024,
                 "timeoutMillis":1000,"maxCallDepth":10},"estimatedPackageEvents":100,
                 "requestedAt":"2026-08-18T00:00:00Z"}
                """;
        Path utf16 = Files.write(
                temporaryDirectory.resolve("request-utf16.json"),
                json.getBytes(StandardCharsets.UTF_16LE));

        assertThrows(CliInputException.class, () -> CliCommandExecutor.readPlanRequest(utf16));
    }
}
