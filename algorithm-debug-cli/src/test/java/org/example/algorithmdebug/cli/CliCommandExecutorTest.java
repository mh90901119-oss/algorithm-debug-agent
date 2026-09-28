package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCommandExecutorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void requestFileUsesStrictUtf8AndSixtyFourKibibyteBudget() throws Exception {
        Path valid = Files.writeString(
                temporaryDirectory.resolve("valid.txt"), "为什么失败？");
        Path oversized = Files.write(
                temporaryDirectory.resolve("large.txt"), new byte[65_537]);
        Path malformed = Files.write(
                temporaryDirectory.resolve("malformed.txt"),
                new byte[] {(byte) 0xC3, (byte) 0x28});

        assertEquals("为什么失败？", CliCommandExecutor.decodeUtf8(
                CliCommandExecutor.readBoundedFile(valid, "request-file"), "request-file"));
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readBoundedFile(oversized, "request-file"));
        assertThrows(CliInputException.class, () -> CliCommandExecutor.decodeUtf8(
                CliCommandExecutor.readBoundedFile(malformed, "request-file"), "request-file"));
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readBoundedFile(
                        temporaryDirectory.resolve("missing.txt"), "request-file"));
    }

    @Test
    void utf8BomIsNotArchivedAsQuestionContent() throws Exception {
        byte[] question = "问题".getBytes(StandardCharsets.UTF_8);
        ByteBuffer content = ByteBuffer.allocate(question.length + 3)
                .put(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})
                .put(question);
        Path file = Files.write(temporaryDirectory.resolve("bom.txt"), content.array());

        assertEquals("问题", CliCommandExecutor.decodeUtf8(
                CliCommandExecutor.readBoundedFile(file, "request-file"), "request-file"));
    }

    @Test
    void codePathRequestFileRejectsUtf16EvenWhenJsonParserCouldDetectIt() throws Exception {
        String json = """
                {"planId":"plan-1","methods":[{"methodKey":"fixture.Test#case1()V","projections":[]}],
                 "scopeMethodKey":"fixture.Test#case1()V",
                 "investigation":{"questionToAnswer":"Which path executed?",
                 "gapId":"gap-1","hypothesisIds":["hypothesis-1"],
                 "predicateIds":["predicate-1"],"basedOnEvidenceIds":[]},
                 "rationale":"Locate the path","budget":{"maxEvents":100,"maxBytes":1024,
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
                 "line":12,"maxObservedHits":3,"maxCapturedHits":3,
                 "captureFirstMatchedHits":3,"captureEveryMatchedHits":0,
                 "conditions":[],"capture":{"stack":true,"maxFrames":8,
                 "maxStringLength":256,"valuePaths":[]}}],
                 "budget":{"maxEvents":100,"maxBytes":16777216,
                 "timeoutMillis":300000,"idleTimeoutMillis":120000},
                 "rationale":"Inspect the call stack",
                 "investigation":{"questionToAnswer":"Which state was observed?",
                 "gapId":"gap-1","hypothesisIds":["hypothesis-1"],
                 "predicateIds":["predicate-1"],"basedOnEvidenceIds":[]},
                 "requestedAt":"2026-08-18T00:00:00Z"}
                """);
        Path unsupported = Files.writeString(temporaryDirectory.resolve("unsupported.json"), """
                {"planId":"jdwp-plan-1","tracepoints":[],"projection":["x"],
                 "budget":{"maxEvents":100,"maxBytes":16777216,
                 "timeoutMillis":300000,"idleTimeoutMillis":120000},
                 "rationale":"Inspect the call stack",
                 "investigation":{"questionToAnswer":"Which state was observed?",
                 "gapId":"gap-1","hypothesisIds":["hypothesis-1"],
                 "predicateIds":["predicate-1"],"basedOnEvidenceIds":[]},
                 "requestedAt":"2026-08-18T00:00:00Z"}
                """);

        assertEquals(
                "jdwp-plan-1",
                CliCommandExecutor.readJdwpPlanRequest(valid).planId().value());
        assertThrows(
                CliInputException.class,
                () -> CliCommandExecutor.readJdwpPlanRequest(unsupported));
    }

    @Test
    void evidenceQueryRequestUsesTheVersionedDomainContract() throws Exception {
        var expected = org.example.algorithmdebug.contracts.EvidenceQueryRequest.summary(32_768);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
        Path valid = Files.writeString(
                temporaryDirectory.resolve("evidence-query.json"),
                mapper.writeValueAsString(expected));
        Path unsupported = Files.writeString(
                temporaryDirectory.resolve("unsupported-evidence-query.json"),
                mapper.writeValueAsString(expected).replaceFirst("}$", ",\"unknown\":true}"));

        assertEquals(expected, CliCommandExecutor.readEvidenceQueryRequest(valid));
        assertThrows(CliInputException.class,
                () -> CliCommandExecutor.readEvidenceQueryRequest(unsupported));
    }

    @Test
    void caseOpenBuildsProblemFrameOnlyFromStructuredInputAndServerIds() throws Exception {
        Path input = Files.writeString(temporaryDirectory.resolve("problem-frame.json"), """
                {
                  "symptom": "调度顺序错误",
                  "expectedBehavior": "高优先级任务先执行",
                  "actualBehavior": "低优先级任务先执行",
                  "scopeAnchors": [{
                    "className": "a.b.Scheduler",
                    "methodName": "select",
                    "descriptor": "()V",
                    "sourceRelativePath": "src/main/java/a/b/Scheduler.java",
                    "startLine": 10,
                    "endLine": 30
                  }],
                  "knownFactRefs": [],
                  "initialUnknowns": ["候选比较结果是否错误"]
                }
                """);
        AtomicInteger tokens = new AtomicInteger();
        CliActionRequestMapper mapper = new CliActionRequestMapper(
                "workspace-1",
                temporaryDirectory,
                CliAnalysisContextResolver.unsupported(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "token-" + tokens.incrementAndGet());

        var request = mapper.map(new CliCommand.CaseOpen(
                temporaryDirectory,
                new ProjectId("project-1"),
                new TargetTest("a.b.Test", "case1"),
                input,
                Optional.empty(),
                Optional.empty()));

        assertEquals(AnalysisActionType.ANALYSIS_BEGIN, request.actionType());
        CoreActionInputs.AnalysisBegin payload =
                (CoreActionInputs.AnalysisBegin) request.payload();
        assertEquals(request.identity().caseId(), payload.problemFrame().caseId());
        assertEquals(request.identity().analysisId(), payload.problemFrame().analysisId());
        assertEquals("a.b.Scheduler",
                payload.problemFrame().scopeAnchors().getFirst().className());
        assertEquals(NOW, payload.problemFrame().createdAt());
        assertTrue(request.operationId().isPresent());
    }

    @Test
    void plainTextCaseOpenIsRejectedRatherThanFabricatingSourceAnchors() throws Exception {
        Path input = Files.writeString(
                temporaryDirectory.resolve("question.txt"), "为什么失败？");
        CliActionRequestMapper mapper = new CliActionRequestMapper(
                "workspace-1",
                temporaryDirectory,
                CliAnalysisContextResolver.unsupported(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "token");

        assertThrows(CliInputException.class, () -> mapper.map(new CliCommand.CaseOpen(
                temporaryDirectory,
                new ProjectId("project-1"),
                new TargetTest("a.b.Test", "case1"),
                input,
                Optional.of(new CaseId("case-1")),
                        Optional.empty())));
    }

    @Test
    void incompleteStructuredProblemFrameIsRejectedAsCliInput() throws Exception {
        Path input = Files.writeString(
                temporaryDirectory.resolve("incomplete.json"), """
                        {
                          "symptom": "调度顺序错误",
                          "expectedBehavior": "高优先级任务先执行",
                          "actualBehavior": "低优先级任务先执行"
                        }
                        """);
        CliActionRequestMapper mapper = new CliActionRequestMapper(
                "workspace-1",
                temporaryDirectory,
                CliAnalysisContextResolver.unsupported(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "token");

        assertThrows(CliInputException.class, () -> mapper.map(new CliCommand.CaseOpen(
                temporaryDirectory,
                new ProjectId("project-1"),
                new TargetTest("a.b.Test", "case1"),
                input,
                Optional.empty(),
                Optional.empty())));
    }

    @Test
    void problemFrameRejectsDuplicateFieldsAndTrailingJsonDocuments() throws Exception {
        String valid = """
                {
                  "symptom": "调度顺序错误",
                  "expectedBehavior": "高优先级任务先执行",
                  "actualBehavior": "低优先级任务先执行",
                  "scopeAnchors": [{
                    "className": "a.b.Scheduler",
                    "methodName": "select",
                    "descriptor": "()V",
                    "sourceRelativePath": "src/main/java/a/b/Scheduler.java",
                    "startLine": 10,
                    "endLine": 30
                  }],
                  "knownFactRefs": [],
                  "initialUnknowns": ["候选比较结果是否错误"]
                }
                """;
        Path duplicate = Files.writeString(
                temporaryDirectory.resolve("duplicate.json"),
                valid.replace(
                        "\"symptom\": \"调度顺序错误\"",
                        "\"symptom\": \"第一值\", \"symptom\": \"第二值\""));
        Path trailing = Files.writeString(
                temporaryDirectory.resolve("trailing.json"), valid + "\n{}\n");
        CliActionRequestMapper mapper = new CliActionRequestMapper(
                "workspace-1",
                temporaryDirectory,
                CliAnalysisContextResolver.unsupported(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "token");

        assertThrows(CliInputException.class, () -> mapper.map(new CliCommand.CaseOpen(
                temporaryDirectory,
                new ProjectId("project-1"),
                new TargetTest("a.b.Test", "case1"),
                duplicate,
                Optional.empty(),
                Optional.empty())));
        assertThrows(CliInputException.class, () -> mapper.map(new CliCommand.CaseOpen(
                temporaryDirectory,
                new ProjectId("project-1"),
                new TargetTest("a.b.Test", "case1"),
                trailing,
                Optional.empty(),
                Optional.empty())));
    }
}
