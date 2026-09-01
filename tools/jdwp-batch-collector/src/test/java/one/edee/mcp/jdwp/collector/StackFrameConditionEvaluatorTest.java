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
        DebugPlan.Condition condition = condition("candidate", List.of("wafer", "id"));

        StackFrameConditionEvaluator.Evaluation result =
                new StackFrameConditionEvaluator().evaluate(thread, condition);

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
                        thread, condition("candidate", List.of()));

        assertEquals(StackFrameConditionEvaluator.Status.UNAVAILABLE, result.status());
        assertEquals("LOCAL_NOT_FOUND", result.reason());
    }

    private static DebugPlan.Condition condition(String localName, List<String> path) {
        DebugPlan.Condition condition = new DebugPlan.Condition();
        condition.localName = localName;
        condition.fieldPath = path;
        condition.expectedType = "STRING";
        condition.expectedValue = "WAFER-1";
        return condition;
    }
}
