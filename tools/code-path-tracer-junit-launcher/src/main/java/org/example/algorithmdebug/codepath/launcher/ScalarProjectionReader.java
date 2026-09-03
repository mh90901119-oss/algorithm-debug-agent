package org.example.algorithmdebug.codepath.launcher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** 按 Plan 读取普通字段，并把结果立即收敛成有界标量。 */
final class ScalarProjectionReader {
    private static final int MAX_STRING_LENGTH = 512;

    List<ProjectionValue> readArguments(
            List<LauncherCodePathPlan.Projection> projections,
            Object[] arguments) {
        List<ProjectionValue> values = new ArrayList<>();
        for (var projection : projections) {
            if (projection.source() == LauncherCodePathPlan.ProjectionSource.ARGUMENT) {
                int index = projection.argumentIndex() == null ? -1 : projection.argumentIndex();
                if (index < 0 || index >= arguments.length) {
                    values.add(unavailable(projection, "ARGUMENT_UNAVAILABLE"));
                } else {
                    values.add(read(projection, arguments[index]));
                }
            }
        }
        return List.copyOf(values);
    }

    List<ProjectionValue> readReturn(
            List<LauncherCodePathPlan.Projection> projections,
            Object returnValue) {
        return projections.stream()
                .filter(projection -> projection.source() == LauncherCodePathPlan.ProjectionSource.RETURN)
                .map(projection -> read(projection, returnValue))
                .toList();
    }

    private ProjectionValue read(LauncherCodePathPlan.Projection projection, Object root) {
        Object current = root;
        for (String fieldName : projection.fieldPath()) {
            if (current == null) return nullValue(projection);
            Field field = findField(current.getClass(), fieldName);
            if (field == null) return unavailable(projection, "FIELD_NOT_FOUND");
            try {
                if (!field.trySetAccessible()) return unavailable(projection, "FIELD_INACCESSIBLE");
                current = field.get(current);
            } catch (RuntimeException | IllegalAccessException failure) {
                return unavailable(projection, "FIELD_READ_FAILED");
            }
        }
        if (current == null) return nullValue(projection);
        return scalar(projection, current);
    }

    private Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继承字段必须继续向父类查找；失败状态只在完整查找结束后生成。
            }
        }
        return null;
    }

    private ProjectionValue scalar(LauncherCodePathPlan.Projection projection, Object value) {
        Object scalar;
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH) {
                return new ProjectionValue(
                        projection.name(), path(projection), projection.required(),
                        ProjectionStatus.TRUNCATED, text.substring(0, MAX_STRING_LENGTH), "VALUE_LENGTH_EXCEEDED");
            }
            scalar = text;
        } else if (value instanceof Character character) {
            scalar = character.toString();
        } else if (value instanceof Number || value instanceof Boolean) {
            scalar = value;
        } else if (value instanceof Enum<?> enumeration) {
            scalar = enumeration.name();
        } else {
            return unavailable(projection, "NON_SCALAR_VALUE");
        }
        return new ProjectionValue(
                projection.name(), path(projection), projection.required(),
                ProjectionStatus.VALUE, scalar, null);
    }

    private ProjectionValue nullValue(LauncherCodePathPlan.Projection projection) {
        return new ProjectionValue(
                projection.name(), path(projection), projection.required(),
                ProjectionStatus.NULL, null, null);
    }

    private ProjectionValue unavailable(
            LauncherCodePathPlan.Projection projection,
            String failureCode) {
        return new ProjectionValue(
                projection.name(), path(projection), projection.required(),
                ProjectionStatus.UNAVAILABLE, null, failureCode);
    }

    private String path(LauncherCodePathPlan.Projection projection) {
        String root = projection.source() == LauncherCodePathPlan.ProjectionSource.ARGUMENT
                ? "arg[" + projection.argumentIndex() + "]"
                : "return";
        return projection.fieldPath().isEmpty()
                ? root
                : root + "." + String.join(".", projection.fieldPath());
    }
}
