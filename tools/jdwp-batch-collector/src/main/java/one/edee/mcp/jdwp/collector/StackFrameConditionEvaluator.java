package one.edee.mcp.jdwp.collector;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

/** 在已挂起的事件线程顶层栈帧上确定性求值一个有界值路径条件。 */
final class StackFrameConditionEvaluator {

    Evaluation evaluate(ThreadReference thread, DebugPlan.Condition condition) {
        if (condition == null) {
            return Evaluation.matched();
        }
        try {
            StackFrame frame = thread.frame(0);
            Value value = rootValue(frame, condition.localName);
            if (value == MissingValue.INSTANCE) {
                return Evaluation.unavailable("LOCAL_NOT_FOUND");
            }
            for (String segment : condition.fieldPath) {
                if (value == null) {
                    return Evaluation.unavailable("NULL_INTERMEDIATE");
                }
                if (!(value instanceof ObjectReference object)) {
                    return Evaluation.unavailable("NON_OBJECT_INTERMEDIATE");
                }
                Field field = object.referenceType().fieldByName(segment);
                if (field == null) {
                    return Evaluation.unavailable("FIELD_NOT_FOUND");
                }
                value = object.getValue(field);
            }
            return compare(value, condition);
        } catch (AbsentInformationException failure) {
            return Evaluation.unavailable("DEBUG_INFO_UNAVAILABLE");
        } catch (IncompatibleThreadStateException failure) {
            return Evaluation.unavailable("THREAD_STATE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            return Evaluation.unavailable("VALUE_READ_FAILED");
        }
    }

    private static Value rootValue(StackFrame frame, String localName)
            throws AbsentInformationException {
        if ("this".equals(localName)) {
            return frame.thisObject();
        }
        LocalVariable variable = frame.visibleVariableByName(localName);
        return variable == null ? MissingValue.INSTANCE : frame.getValue(variable);
    }

    private static Evaluation compare(Value value, DebugPlan.Condition condition) {
        if ("NULL".equals(condition.expectedType)) {
            return value == null ? Evaluation.matched() : Evaluation.notMatched();
        }
        if (value == null) {
            return Evaluation.notMatched();
        }
        boolean matched = switch (condition.expectedType) {
            case "STRING" -> value instanceof StringReference text
                    && condition.expectedValue.equals(text.value());
            case "LONG" -> integral(value)
                    && ((PrimitiveValue) value).longValue()
                    == Long.parseLong(condition.expectedValue);
            case "DOUBLE" -> floating(value)
                    && Double.compare(((PrimitiveValue) value).doubleValue(),
                    Double.parseDouble(condition.expectedValue)) == 0;
            case "BOOLEAN" -> value instanceof BooleanValue booleanValue
                    && booleanValue.value() == Boolean.parseBoolean(condition.expectedValue);
            case "CHAR" -> value instanceof CharValue charValue
                    && charValue.value() == condition.expectedValue.charAt(0);
            case "ENUM" -> enumName(value).equals(condition.expectedValue);
            default -> false;
        };
        return matched ? Evaluation.matched() : Evaluation.notMatched();
    }

    private static boolean integral(Value value) {
        return value instanceof ByteValue || value instanceof ShortValue
                || value instanceof IntegerValue || value instanceof LongValue;
    }

    private static boolean floating(Value value) {
        return value instanceof FloatValue || value instanceof DoubleValue;
    }

    private static String enumName(Value value) {
        if (!(value instanceof ObjectReference object)) {
            return "";
        }
        Field name = object.referenceType().fieldByName("name");
        Value enumName = name == null ? null : object.getValue(name);
        return enumName instanceof StringReference text ? text.value() : "";
    }

    enum Status { MATCHED, NOT_MATCHED, UNAVAILABLE }

    record Evaluation(Status status, String reason) {
        static Evaluation matched() {
            return new Evaluation(Status.MATCHED, "");
        }

        static Evaluation notMatched() {
            return new Evaluation(Status.NOT_MATCHED, "");
        }

        static Evaluation unavailable(String reason) {
            return new Evaluation(Status.UNAVAILABLE, reason);
        }
    }

    /** 仅用于区分“局部变量不存在”和“局部变量值为 null”。 */
    private static final class MissingValue implements Value {
        private static final MissingValue INSTANCE = new MissingValue();

        @Override
        public com.sun.jdi.Type type() {
            throw new UnsupportedOperationException("missing local");
        }

        @Override
        public com.sun.jdi.VirtualMachine virtualMachine() {
            throw new UnsupportedOperationException("missing local");
        }
    }
}
