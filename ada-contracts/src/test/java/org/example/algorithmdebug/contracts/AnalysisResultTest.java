package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisResultTest {

    @Test
    void shouldStoreOnlyUserFacingConclusionAndEvidenceReferences() {
        List<AnalysisConclusion> conclusions = new ArrayList<>();
        conclusions.add(new AnalysisConclusion(
                ClaimClassification.CONFIRMED_FACT,
                "设备空闲区间来自已归档的调度结果。",
                List.of("evidence-1", "artifact-gantt")));
        AnalysisResult result = new AnalysisResult(
                SchemaVersions.ANALYSIS_RESULT,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), "本轮结论是设备在等待前序操作。",
                conclusions, List.of(new RunId("run-1")),
                List.of(new CollectionId("collection-1")),
                List.of(new EvidenceId("evidence-1")),
                List.of("artifact-gantt"),
                List.of("缺少关键方法内部候选集合"),
                Instant.parse("2026-08-19T00:00:00Z"));
        conclusions.clear();

        assertEquals(1, result.conclusions().size());
        assertThrows(UnsupportedOperationException.class, () -> result.conclusions().clear());
        assertFalse(List.of(AnalysisResult.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().toLowerCase().contains("reasoning")
                        || component.getName().toLowerCase().contains("thought")));
    }

    @Test
    void shouldRejectUnreferencedConfirmedConclusion() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisConclusion(
                ClaimClassification.CONFIRMED_FACT, "没有来源的确定事实", List.of()));
    }
}
