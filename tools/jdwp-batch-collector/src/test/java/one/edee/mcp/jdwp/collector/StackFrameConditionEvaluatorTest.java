package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import java.util.List;
import org.junit.jupiter.api.Test;

class StackFrameConditionEvaluatorTest {

    @Test
    void traversesInstanceFieldsWithoutInvokingTargetMethods() throws Exception {
        ThreadReference thread = mock(ThreadReference.class);
        StackFrame frame = mock(StackFrame.class);
        LocalVariable local = mock(LocalVariable.class);
        ObjectReference candidate = mock(ObjectReference.class);
        ObjectReference wafer = mock(ObjectReference.class);
        ReferenceType candidateType = mock(ReferenceType.class);
        ReferenceType waferType = mock(ReferenceType.class);
        Field waferField = mock(Field.class);
        Field idField = mock(Field.class);
        StringReference id = mock(StringReference.class);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.visibleVariableByName("candidate")).thenReturn(local);
        when(frame.getValue(local)).thenReturn(candidate);
        when(candidate.referenceType()).thenReturn(candidateType);
        when(candidateType.fieldByName("wafer")).thenReturn(waferField);
        when(candidate.getValue(waferField)).thenReturn(wafer);
        when(wafer.referenceType()).thenReturn(waferType);
        when(waferType.fieldByName("id")).thenReturn(idField);
        when(wafer.getValue(idField)).thenReturn(id);
        when(id.value()).thenReturn("WAFER-1");
        DebugPlan.Condition condition = condition("candidate.wafer.id", "WAFER-1");

        StackFrameConditionEvaluator.Evaluation result =
                new StackFrameConditionEvaluator().evaluate(thread, List.of(condition));

        assertEquals(StackFrameConditionEvaluator.Status.MATCHED, result.status());
    }

    @Test
    void reportsUnavailableWhenDebugLocalIsMissing() throws Exception {
        ThreadReference thread = mock(ThreadReference.class);
        StackFrame frame = mock(StackFrame.class);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.visibleVariableByName("candidate")).thenReturn(null);

        StackFrameConditionEvaluator.Evaluation result =
                new StackFrameConditionEvaluator().evaluate(
                        thread, List.of(condition("candidate", "WAFER-1")));

        assertEquals(StackFrameConditionEvaluator.Status.UNAVAILABLE, result.status());
        assertEquals("LOCAL_NOT_FOUND", result.reason());
    }

    @Test
    void requiresEveryConditionToMatch() throws Exception {
        ThreadReference thread = mock(ThreadReference.class);
        StackFrame frame = mock(StackFrame.class);
        LocalVariable waferNumber = mock(LocalVariable.class);
        LocalVariable chamber = mock(LocalVariable.class);
        StringReference waferValue = mock(StringReference.class);
        StringReference chamberValue = mock(StringReference.class);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.visibleVariableByName("waferNumber")).thenReturn(waferNumber);
        when(frame.visibleVariableByName("chamber")).thenReturn(chamber);
        when(frame.getValue(waferNumber)).thenReturn(waferValue);
        when(frame.getValue(chamber)).thenReturn(chamberValue);
        when(waferValue.value()).thenReturn("WAFER-1");
        when(chamberValue.value()).thenReturn("PM2");

        StackFrameConditionEvaluator.Evaluation result = new StackFrameConditionEvaluator().evaluate(
                thread,
                List.of(condition("waferNumber", "WAFER-1"), condition("chamber", "PM1")));

        assertEquals(StackFrameConditionEvaluator.Status.NOT_MATCHED, result.status());
    }

    private static DebugPlan.Condition condition(String valuePath, String expectedValue) {
        DebugPlan.Condition condition = new DebugPlan.Condition();
        condition.valuePath = valuePath;
        condition.expectedType = "STRING";
        condition.expectedValue = expectedValue;
        return condition;
    }
}
