package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpaqueIdGeneratorTest {
    @Test
    void generatesEvidenceIdentityFromTheSameSafeOpaqueTokenSource() {
        ArrayDeque<String> tokens = new ArrayDeque<>(List.of("first", "second"));
        OpaqueIdGenerator generator = new OpaqueIdGenerator(tokens::removeFirst);

        assertEquals("evidence-first", generator.newEvidenceId().value());
        assertEquals("collection-second", generator.newCollectionId().value());
    }
}
