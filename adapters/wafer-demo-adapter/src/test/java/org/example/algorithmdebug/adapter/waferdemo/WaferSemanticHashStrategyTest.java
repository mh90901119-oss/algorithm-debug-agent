package org.example.algorithmdebug.adapter.waferdemo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferSemanticHashStrategyTest {

    @Test
    void shouldIgnoreNonSemanticTextAndCollectionOrderNoise() throws Exception {
        WaferScheduleSnapshot original = fixture();
        List<String> reversedResources = new ArrayList<>(original.resources());
        Collections.reverse(reversedResources);
        List<WaferOperationSnapshot> reversedOperations = new ArrayList<>();
        for (WaferOperationSnapshot operation : original.operations().reversed()) {
            List<String> resourceIds = new ArrayList<>(operation.resourceIds());
            Collections.reverse(resourceIds);
            reversedOperations.add(copy(operation, operation.start(), "changed explanation", resourceIds));
        }
        WaferScheduleSnapshot noisy = new WaferScheduleSnapshot(
                original.schemaVersion(),
                "DIFFERENT-SNAPSHOT",
                "RESCHEDULE",
                original.algorithm(),
                original.equipmentId(),
                original.jobProcessingMode(),
                original.makespan(),
                reversedResources,
                reversedOperations,
                original.finalWaferLocations());

        WaferSemanticHashStrategy strategy = new WaferSemanticHashStrategy();
        String originalHash = strategy.semanticHash(original);
        String noisyHash = strategy.semanticHash(noisy);

        assertEquals(originalHash, noisyHash);
        assertTrue(originalHash.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldChangeWhenScheduleTimingChanges() throws Exception {
        WaferScheduleSnapshot original = fixture();
        List<WaferOperationSnapshot> changedOperations = new ArrayList<>(original.operations());
        WaferOperationSnapshot operation = changedOperations.get(1);
        changedOperations.set(1, copy(operation, operation.start() + 1,
                operation.schedulingReason(), operation.resourceIds()));
        WaferScheduleSnapshot changed = new WaferScheduleSnapshot(
                original.schemaVersion(), original.snapshotId(), original.triggerReason(),
                original.algorithm(), original.equipmentId(), original.jobProcessingMode(),
                original.makespan(), original.resources(), changedOperations,
                original.finalWaferLocations());

        WaferSemanticHashStrategy strategy = new WaferSemanticHashStrategy();

        assertNotEquals(strategy.semanticHash(original), strategy.semanticHash(changed));
    }

    private WaferScheduleSnapshot fixture() throws Exception {
        Path fixture = Path.of(getClass().getResource("/wafer-result-fixture.json").toURI());
        return new WaferScheduleResultParser().parse(fixture);
    }

    private static WaferOperationSnapshot copy(
            WaferOperationSnapshot operation,
            int start,
            String reason,
            List<String> resourceIds) {
        int duration = operation.end() - start;
        return new WaferOperationSnapshot(
                operation.operationId(), operation.waferId(), operation.jobId(), operation.sequenceId(),
                operation.jobStartOrder(), operation.waferOrder(), operation.operationIndex(),
                operation.sequenceStepId(), operation.sequenceStepIndex(), operation.operationType(),
                resourceIds, operation.fromLocation(), operation.toLocation(), start, operation.end(),
                duration, operation.source(), reason);
    }
}

