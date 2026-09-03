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
                {"planId":"plan-1","methods":[{"methodKey":"fixture.Test#case1()V","projections":[]}],
                 "scopeMethodKey":"fixture.Test#case1()V",
                 "intent":{"questionToAnswer":"Which path executed?",
                 "hypothesis":"The target method executed",
                 "basedOnEvidenceIds":[],"expectedObservations":["An observed method path"]},
                 "rationale":"定位","budget":{"maxEvents":100,"maxBytes":1024,
                 "timeoutMillis":1000},
                 "requestedAt":"2026-08-18T00:00:00Z"}
                """;
        Path utf16 = Files.write(
                temporaryDirectory.resolve("request-utf16.json"),
                json.getBytes(StandardCharsets.UTF_16LE));
        Path utf8 = Files.writeString(temporaryDirectory.resolve("request-utf8.json"), json);

        assertEquals("fixture.Test#case1()V",
                CliCommandExecutor.readPlanRequest(utf8).scopeMethodKey().orElseThrow());
        assertThrows(CliInputException.class, () -> CliCommandExecutor.readPlanRequest(utf16));
    }

    @Test
    void jdwpRequestUsesStrictUtf8AndRejectsUnknownCapabilities() throws Exception {
        Path valid = Files.writeString(temporaryDirectory.resolve("jdwp.json"), """
                {"planId":"jdwp-plan-1","tracepoints":[{
                 "tracepointId":"entry","methodKey":"fixture.Test#case1()V",
                 "line":12,"maxObservedHits":3,"maxCapturedHits":3,"captureFirstMatchedHits":3,"captureEveryMatchedHits":0,"capture":{"locals":false,"stack":true,
                 "maxFrames":8,"maxDepth":1,"maxItems":20,"maxStringLength":256}}],
                 "budget":{"maxEvents":100,"maxBytes":16777216,
                 "timeoutMillis":300000,"idleTimeoutMillis":120000},
                 "rationale":"查看调用栈","intent":{"questionToAnswer":"Which state was observed?","hypothesis":"The target method receives the expected state","basedOnEvidenceIds":[],"expectedObservations":["A matching snapshot"]},"requestedAt":"2026-08-18T00:00:00Z"}
                """);
        Path unsupported = Files.writeString(temporaryDirectory.resolve("unsupported.json"), """
                {"planId":"jdwp-plan-1","tracepoints":[],"projection":["x"],
                 "budget":{"maxEvents":100,"maxBytes":16777216,
                 "timeoutMillis":300000,"idleTimeoutMillis":120000},
                 "rationale":"查看调用栈","requestedAt":"2026-08-18T00:00:00Z"}
                """);

        assertEquals("jdwp-plan-1", CliCommandExecutor.readJdwpPlanRequest(valid).planId().value());
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readJdwpPlanRequest(unsupported));
    }


}
