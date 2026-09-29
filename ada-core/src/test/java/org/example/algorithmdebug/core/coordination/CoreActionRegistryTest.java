package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.junit.jupiter.api.Test;

class CoreActionRegistryTest {

    @Test
    void everyActionTypeHasExactlyOneTypedBinding() {
        AnalysisActionRegistry registry = CoreActionHandlers.createRegistry(
                new PolicyTestFixtures.Prerequisites(), ports());

        assertEquals(EnumSet.allOf(AnalysisActionType.class),
                registry.bindings().stream()
                        .map(AnalysisActionBinding::actionType)
                        .collect(java.util.stream.Collectors.toCollection(
                                () -> EnumSet.noneOf(AnalysisActionType.class))));
        for (AnalysisActionType actionType : AnalysisActionType.values()) {
            assertSame(actionType, registry.require(actionType).actionType());
        }
    }

    @Test
    void sideEffectClassificationMatchesTheFixedPolicyMatrix() {
        AnalysisActionRegistry registry = CoreActionHandlers.createRegistry(
                new PolicyTestFixtures.Prerequisites(), ports());
        Map<AnalysisActionType, ActionSideEffect> expected = new EnumMap<>(AnalysisActionType.class);
        put(expected, ActionSideEffect.READ_ONLY,
                AnalysisActionType.CASE_INSPECT,
                AnalysisActionType.CASE_AUDIT,
                AnalysisActionType.GANTT_INSPECT,
                AnalysisActionType.ARTIFACT_READ,
                AnalysisActionType.EVIDENCE_QUERY,
                AnalysisActionType.ANALYSIS_STATUS);
        put(expected, ActionSideEffect.CASE_WRITE,
                AnalysisActionType.ANALYSIS_BEGIN,
                AnalysisActionType.ALGORITHM_INPUT_CAPTURE,
                AnalysisActionType.STATIC_ANALYZE,
                AnalysisActionType.SOURCE_QUERY,
                AnalysisActionType.INVESTIGATION_UPDATE,
                AnalysisActionType.CODEPATH_PLAN_CREATE,
                AnalysisActionType.JDWP_PLAN_CREATE,
                AnalysisActionType.ANALYSIS_FINALIZE);
        put(expected, ActionSideEffect.TARGET_EXECUTION,
                AnalysisActionType.RUN_TEST,
                AnalysisActionType.CODEPATH_COLLECT,
                AnalysisActionType.JDWP_COLLECT);

        registry.bindings().forEach(binding -> assertEquals(
                expected.get(binding.actionType()), binding.sideEffect(),
                binding.actionType().name()));
    }

    @Test
    void analysisFinalizePortReturnsTheCanonicalFinalizationContract() {
        var component = java.util.Arrays.stream(
                        CoreActionHandlers.ActionPorts.class.getRecordComponents())
                .filter(value -> value.getName().equals("analysisFinalize"))
                .findFirst()
                .orElseThrow();

        assertTrue(component.getGenericType().getTypeName().contains(
                "org.example.algorithmdebug.contracts.coordination.ConclusionFinalization"));
    }

    private static void put(
            Map<AnalysisActionType, ActionSideEffect> target,
            ActionSideEffect sideEffect,
            AnalysisActionType... actions) {
        for (AnalysisActionType action : actions) {
            target.put(action, sideEffect);
        }
    }

    private static CoreActionHandlers.ActionPorts ports() {
        return new CoreActionHandlers.ActionPorts(
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null,
                (target, input, cancellation) -> null);
    }
}
