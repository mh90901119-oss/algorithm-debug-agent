package one.edee.mcp.jdwp.core;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * 从顶层栈帧按精确路径读取一个值。
 *
 * <p>实现不调用目标 JVM 方法、不遍历对象字段和集合，只读取计划明确列出的路径。
 */
public final class JdiValuePathReader {
    private final int maxStringLength;

    public JdiValuePathReader(int maxStringLength) {
        if (maxStringLength < 16 || maxStringLength > 1_024) {
            throw new IllegalArgumentException("maxStringLength must be between 16 and 1024");
        }
        this.maxStringLength = maxStringLength;
    }

    /** 解析路径并保留原始 JDI Value，供条件判断复用。 */
    public Resolution resolve(StackFrame frame, String valuePath) {
        try {
            String[] segments = valuePath.split("\\.", -1);
            Value value;
            if ("this".equals(segments[0])) {
                value = frame.thisObject();
            } else {
                var variable = frame.visibleVariableByName(segments[0]);
                if (variable == null) return Resolution.unavailable("LOCAL_NOT_FOUND");
                value = frame.getValue(variable);
            }
            for (int index = 1; index < segments.length; index++) {
                if (value == null) return Resolution.unavailable("NULL_BEFORE_PATH_END");
                if (!(value instanceof ObjectReference object)) {
                    return Resolution.unavailable("NON_OBJECT_BEFORE_PATH_END");
                }
                Field field = object.referenceType().fieldByName(segments[index]);
                if (field == null) return Resolution.unavailable("FIELD_NOT_FOUND");
                value = field.isStatic()
                        ? field.declaringType().getValue(field)
                        : object.getValue(field);
            }
            return Resolution.available(value);
        } catch (AbsentInformationException failure) {
            return Resolution.unavailable("DEBUG_INFO_UNAVAILABLE");
        } catch (ObjectCollectedException failure) {
            return Resolution.unavailable("OBJECT_COLLECTED");
        } catch (RuntimeException failure) {
            return Resolution.unavailable("VALUE_READ_FAILED");
        }
    }

    /** 将精确路径解析结果转换为稳定、标量优先的 Raw Trace 投影。 */
    public Projection read(StackFrame frame, String valuePath) {
        Resolution resolution = resolve(frame, valuePath);
        if (resolution.status() == ResolutionStatus.UNAVAILABLE) {
            return Projection.unavailable(valuePath, resolution.reason());
        }
        try {
            Value value = resolution.value();
            if (value == null) {
                return new Projection(valuePath, ProjectionStatus.CAPTURED, "NULL", null,
                        null, false, null, null);
            }
            if (value instanceof StringReference text) {
                String full = text.value();
                boolean truncated = full.length() > maxStringLength;
                String captured = truncated ? full.substring(0, maxStringLength) : full;
                return new Projection(valuePath,
                        truncated ? ProjectionStatus.TRUNCATED : ProjectionStatus.CAPTURED,
                        "STRING", "java.lang.String", captured, truncated,
                        text.uniqueID(), truncated ? "MAX_STRING_LENGTH" : null);
            }
            if (value instanceof PrimitiveValue primitive) {
                String type = primitive.type().name();
                String kind = switch (type) {
                    case "boolean" -> "BOOLEAN";
                    case "char" -> "CHAR";
                    default -> "NUMBER";
                };
                return new Projection(valuePath, ProjectionStatus.CAPTURED, kind, type,
                        primitive.toString(), false, null, null);
            }
            if (value instanceof ObjectReference object && isEnum(object)) {
                Field nameField = object.referenceType().fieldByName("name");
                Value name = nameField == null ? null : object.getValue(nameField);
                if (name instanceof StringReference text) {
                    return new Projection(valuePath, ProjectionStatus.CAPTURED, "ENUM",
                            object.referenceType().name(), text.value(), false,
                            object.uniqueID(), null);
                }
            }
            if (value instanceof ObjectReference object) {
                return new Projection(valuePath, ProjectionStatus.REFERENCE_ONLY,
                        value instanceof ArrayReference ? "ARRAY" : "OBJECT",
                        object.referenceType().name(), null, false, object.uniqueID(),
                        "SELECT_A_DEEPER_VALUE_PATH");
            }
            return new Projection(valuePath, ProjectionStatus.REFERENCE_ONLY, "VALUE",
                    value.type().name(), null, false, null, "UNSUPPORTED_VALUE_KIND");
        } catch (ObjectCollectedException failure) {
            return Projection.unavailable(valuePath, "OBJECT_COLLECTED");
        } catch (RuntimeException failure) {
            return Projection.unavailable(valuePath, "VALUE_READ_FAILED");
        }
    }

    private static boolean isEnum(ObjectReference object) {
        if (!(object.referenceType() instanceof ClassType type)) return false;
        for (ClassType current = type; current != null; current = current.superclass()) {
            if ("java.lang.Enum".equals(current.name())) return true;
        }
        return false;
    }

    public enum ResolutionStatus { AVAILABLE, UNAVAILABLE }

    public record Resolution(ResolutionStatus status, Value value, String reason) {
        public static Resolution available(Value value) {
            return new Resolution(ResolutionStatus.AVAILABLE, value, null);
        }

        public static Resolution unavailable(String reason) {
            return new Resolution(ResolutionStatus.UNAVAILABLE, null, reason);
        }
    }

    public enum ProjectionStatus { CAPTURED, TRUNCATED, REFERENCE_ONLY, UNAVAILABLE }

    public record Projection(
            String valuePath,
            ProjectionStatus status,
            String kind,
            String runtimeType,
            String scalarValue,
            boolean valueTruncated,
            Long objectId,
            String reason) {
        public static Projection unavailable(String valuePath, String reason) {
            return new Projection(valuePath, ProjectionStatus.UNAVAILABLE, null, null,
                    null, false, null, reason);
        }
    }
}
