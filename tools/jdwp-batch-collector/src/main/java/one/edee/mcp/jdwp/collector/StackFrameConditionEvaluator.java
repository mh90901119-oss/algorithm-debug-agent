package one.edee.mcp.jdwp.collector;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import java.util.List;
import one.edee.mcp.jdwp.core.JdiValuePathReader;

/** 在已挂起线程的顶层栈帧上判断最多四个 AND 值条件。 */
final class StackFrameConditionEvaluator {
    private final JdiValuePathReader reader = new JdiValuePathReader(1_024);

    Evaluation evaluate(ThreadReference thread, List<DebugPlan.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return Evaluation.matched();
        try {
            var frame = thread.frame(0);
            String unavailableReason = null;
            for (DebugPlan.Condition condition : conditions) {
                var resolution = reader.resolve(frame, condition.valuePath);
                if (resolution.status() == JdiValuePathReader.ResolutionStatus.UNAVAILABLE) {
                    if (unavailableReason == null) unavailableReason = resolution.reason();
                    continue;
                }
                if (!matches(resolution.value(), condition)) return Evaluation.notMatched();
            }
            return unavailableReason == null
                    ? Evaluation.matched()
                    : Evaluation.unavailable(unavailableReason);
        } catch (IncompatibleThreadStateException failure) {
            return Evaluation.unavailable("THREAD_STATE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            return Evaluation.unavailable("VALUE_READ_FAILED");
        }
    }

    private static boolean matches(Value value, DebugPlan.Condition condition) {
        if ("NULL".equals(condition.expectedType)) return value == null;
        if (value == null) return false;
        return switch (condition.expectedType) {
            case "STRING" -> value instanceof StringReference text
                    && condition.expectedValue.equals(text.value());
            case "LONG" -> integral(value)
                    && ((PrimitiveValue) value).longValue() == Long.parseLong(condition.expectedValue);
            case "DOUBLE" -> floating(value)
                    && Double.compare(((PrimitiveValue) value).doubleValue(),
                    Double.parseDouble(condition.expectedValue)) == 0;
            case "BOOLEAN" -> value instanceof BooleanValue booleanValue
                    && booleanValue.value() == Boolean.parseBoolean(condition.expectedValue);
            case "CHAR" -> value instanceof CharValue charValue
                    && charValue.value() == condition.expectedValue.charAt(0);
            case "ENUM" -> condition.expectedValue.equals(enumName(value));
            default -> false;
        };
    }

    private static boolean integral(Value value) {
        return value instanceof ByteValue || value instanceof ShortValue
                || value instanceof IntegerValue || value instanceof LongValue;
    }

    private static boolean floating(Value value) {
        return value instanceof FloatValue || value instanceof DoubleValue;
    }

    private static String enumName(Value value) {
        if (!(value instanceof ObjectReference object)) return "";
        Field name = object.referenceType().fieldByName("name");
        Value enumName = name == null ? null : object.getValue(name);
        return enumName instanceof StringReference text ? text.value() : "";
    }

    enum Status { MATCHED, NOT_MATCHED, UNAVAILABLE }

    record Evaluation(Status status, String reason) {
        static Evaluation matched() { return new Evaluation(Status.MATCHED, ""); }
        static Evaluation notMatched() { return new Evaluation(Status.NOT_MATCHED, ""); }
        static Evaluation unavailable(String reason) {
            return new Evaluation(Status.UNAVAILABLE, reason);
        }
    }
}
