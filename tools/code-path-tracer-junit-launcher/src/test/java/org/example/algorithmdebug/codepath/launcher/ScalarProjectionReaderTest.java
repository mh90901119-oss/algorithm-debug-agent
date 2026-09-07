package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScalarProjectionReaderTest {
    private final ScalarProjectionReader reader = new ScalarProjectionReader();

    @Test
    void readsNestedAndInheritedScalarFieldsWithoutExpandingTheObjectGraph() {
        var projection = argument("entityId", 0, List.of("entity", "id"));

        ProjectionValue value = reader.readArguments(
                List.of(projection), new Object[] {new Holder(new ChildEntity("E-1"))}).get(0);

        assertEquals(ProjectionStatus.VALUE, value.status());
        assertEquals("E-1", value.value());
        assertEquals("arg[0].entity.id", value.path());
    }

    @Test
    void reportsUnavailableNullNonScalarAndTruncatedValuesIndependently() {
        var missingArgument = reader.readArguments(
                List.of(argument("missing", 2, List.of())), new Object[] {"value"}).get(0);
        var nullField = reader.readArguments(
                List.of(argument("nullable", 0, List.of("entity"))),
                new Object[] {new Holder(null)}).get(0);
        var nonScalar = reader.readArguments(
                List.of(argument("object", 0, List.of())), new Object[] {new Object()}).get(0);
        var truncated = reader.readArguments(
                List.of(argument("longText", 0, List.of())), new Object[] {"x".repeat(513)}).get(0);

        assertEquals("ARGUMENT_UNAVAILABLE", missingArgument.failureCode());
        assertEquals(ProjectionStatus.NULL, nullField.status());
        assertEquals("NON_SCALAR_VALUE", nonScalar.failureCode());
        assertEquals(ProjectionStatus.TRUNCATED, truncated.status());
        assertEquals(512, ((String) truncated.value()).length());
    }

    @Test
    void marksReturnProjectionUnavailableWhenTheMethodThrows() {
        var projection = new LauncherCodePathPlan.Projection(
                "result", LauncherCodePathPlan.ProjectionSource.RETURN,
                null, List.of("id"), true);

        ProjectionValue value = reader.readReturn(
                List.of(projection), null, new IllegalStateException("failure")).get(0);

        assertEquals(ProjectionStatus.UNAVAILABLE, value.status());
        assertEquals("METHOD_THREW", value.failureCode());
    }

    private static LauncherCodePathPlan.Projection argument(
            String name, int index, List<String> path) {
        return new LauncherCodePathPlan.Projection(
                name, LauncherCodePathPlan.ProjectionSource.ARGUMENT,
                index, path, true);
    }

    private static final class Holder {
        private final BaseEntity entity;

        private Holder(BaseEntity entity) {
            this.entity = entity;
        }
    }

    private static class BaseEntity {
        private final String id;

        private BaseEntity(String id) {
            this.id = id;
        }
    }

    private static final class ChildEntity extends BaseEntity {
        private ChildEntity(String id) {
            super(id);
        }
    }
}
