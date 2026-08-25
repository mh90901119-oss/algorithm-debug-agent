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
                 "timeoutMillis":1000},
                 "requestedAt":"2026-08-18T00:00:00Z"}
                """;
        Path utf16 = Files.write(
                temporaryDirectory.resolve("request-utf16.json"),
                json.getBytes(StandardCharsets.UTF_16LE));

        assertThrows(CliInputException.class, () -> CliCommandExecutor.readPlanRequest(utf16));
    }

    @Test
    void jdwpRequestUsesStrictUtf8AndRejectsUnknownCapabilities() throws Exception {
        Path valid = Files.writeString(temporaryDirectory.resolve("jdwp.json"), """
                {"planId":"jdwp-plan-1","tracepoints":[{
                 "tracepointId":"entry","methodKey":"fixture.Test#case1()V",
                 "line":12,"maxHits":3,"capture":{"locals":false,"stack":true,
                 "maxFrames":8,"maxDepth":1,"maxItems":20,"maxStringLength":256}}],
                 "budget":{"maxEvents":100,"maxBytes":16777216,
                 "timeoutMillis":300000,"idleTimeoutMillis":120000},
                 "rationale":"查看调用栈","requestedAt":"2026-08-18T00:00:00Z"}
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

    @Test
    void analysisResultFileContainsOnlySupportedFinalResultFields() throws Exception {
        Path valid = Files.writeString(temporaryDirectory.resolve("analysis-result.json"), """
                {"schemaVersion":"1.0","caseId":"case-1","contextId":"context-1",
                 "analysisId":"analysis-1","finalAnswer":"回答","conclusions":[],
                 "referencedRunIds":[],"referencedCollectionIds":[],
                 "referencedEvidenceIds":[],"referencedArtifactIds":[],
                 "missingEvidence":[],"completedAt":"2026-08-19T00:00:00Z"}
                """);
        Path reasoning = Files.writeString(temporaryDirectory.resolve("reasoning.json"), """
                {"schemaVersion":"1.0","caseId":"case-1","contextId":"context-1",
                 "analysisId":"analysis-1","finalAnswer":"回答","conclusions":[],
                 "referencedRunIds":[],"referencedCollectionIds":[],
                 "referencedEvidenceIds":[],"referencedArtifactIds":[],
                 "missingEvidence":[],"reasoning":"hidden",
                 "completedAt":"2026-08-19T00:00:00Z"}
                """);

        assertEquals("回答", CliCommandExecutor.readAnalysisResult(valid).finalAnswer());
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readAnalysisResult(reasoning));
    }
}
