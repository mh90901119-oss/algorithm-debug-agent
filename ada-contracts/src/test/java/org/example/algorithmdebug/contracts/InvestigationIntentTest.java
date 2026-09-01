package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvestigationIntentTest {

    @Test
    void keepsAValidBoundedEvidenceLineageImmutable() {
        InvestigationIntent intent = new InvestigationIntent(
                "Which runtime state selected this path?",
                "A competing object may hold the required resource.",
                List.of(new EvidenceId("evidence-1")),
                List.of("Actual strategy implementation", "Resource occupant"));

        assertEquals("evidence-1", intent.basedOnEvidenceIds().getFirst().value());
        assertThrows(UnsupportedOperationException.class, () ->
                intent.expectedObservations().add("unexpected"));
    }

    @Test
    void rejectsBlankTextDuplicateEvidenceAndUnboundedObservations() {
        assertThrows(IllegalArgumentException.class, () -> new InvestigationIntent(
                " ", "hypothesis", List.of(), List.of("observation")));
        assertThrows(IllegalArgumentException.class, () -> new InvestigationIntent(
                "question", "hypothesis",
                List.of(new EvidenceId("evidence-1"), new EvidenceId("evidence-1")),
                List.of("observation")));
        assertThrows(IllegalArgumentException.class, () -> new InvestigationIntent(
                "question", "hypothesis", List.of(),
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(index -> "observation-" + index).toList()));
    }
}
