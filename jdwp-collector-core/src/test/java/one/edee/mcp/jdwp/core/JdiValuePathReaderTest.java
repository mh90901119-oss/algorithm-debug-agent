package one.edee.mcp.jdwp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import org.junit.jupiter.api.Test;

class JdiValuePathReaderTest {

    @Test
    void readsOnlyTheRequestedNestedField() throws Exception {
        StackFrame frame = mock(StackFrame.class);
        LocalVariable contextVariable = mock(LocalVariable.class);
        ObjectReference context = mock(ObjectReference.class);
        ObjectReference wafer = mock(ObjectReference.class);
        ReferenceType contextType = mock(ReferenceType.class);
        ReferenceType waferType = mock(ReferenceType.class);
        Field waferField = mock(Field.class);
        Field idField = mock(Field.class);
        StringReference id = mock(StringReference.class);

        when(frame.visibleVariableByName("context")).thenReturn(contextVariable);
        when(frame.getValue(contextVariable)).thenReturn(context);
        when(context.referenceType()).thenReturn(contextType);
        when(contextType.fieldByName("wafer")).thenReturn(waferField);
        when(context.getValue(waferField)).thenReturn(wafer);
        when(wafer.referenceType()).thenReturn(waferType);
        when(waferType.fieldByName("id")).thenReturn(idField);
        when(wafer.getValue(idField)).thenReturn(id);
        when(id.value()).thenReturn("WAFER-1");

        JdiValuePathReader.Projection result =
                new JdiValuePathReader(256).read(frame, "context.wafer.id");

        assertEquals(JdiValuePathReader.ProjectionStatus.CAPTURED, result.status());
        assertEquals("STRING", result.kind());
        assertEquals("WAFER-1", result.scalarValue());
        verify(contextType, never()).allFields();
        verify(waferType, never()).allFields();
    }

    @Test
    void returnsReferenceOnlyWithoutExpandingAComplexRoot() throws Exception {
        StackFrame frame = mock(StackFrame.class);
        LocalVariable contextVariable = mock(LocalVariable.class);
        ObjectReference context = mock(ObjectReference.class);
        ReferenceType contextType = mock(ReferenceType.class);

        when(frame.visibleVariableByName("context")).thenReturn(contextVariable);
        when(frame.getValue(contextVariable)).thenReturn(context);
        when(context.referenceType()).thenReturn(contextType);
        when(contextType.name()).thenReturn("fixture.Context");
        when(context.uniqueID()).thenReturn(41L);

        JdiValuePathReader.Projection result =
                new JdiValuePathReader(256).read(frame, "context");

        assertEquals(JdiValuePathReader.ProjectionStatus.REFERENCE_ONLY, result.status());
        assertEquals("OBJECT", result.kind());
        assertEquals("fixture.Context", result.runtimeType());
        verify(contextType, never()).allFields();
    }

    @Test
    void reportsMissingLocalWithoutDiscardingTheProjection() throws Exception {
        StackFrame frame = mock(StackFrame.class);
        when(frame.visibleVariableByName("missing")).thenReturn(null);

        JdiValuePathReader.Projection result =
                new JdiValuePathReader(256).read(frame, "missing.value");

        assertEquals(JdiValuePathReader.ProjectionStatus.UNAVAILABLE, result.status());
        assertEquals("LOCAL_NOT_FOUND", result.reason());
        assertEquals("missing.value", result.valuePath());
    }

    @Test
    void reportsProjectionFailureWithoutDiscardingTheHit() throws Exception {
        StackFrame frame = mock(StackFrame.class);
        LocalVariable variable = mock(LocalVariable.class);
        PrimitiveValue value = mock(PrimitiveValue.class);
        when(frame.visibleVariableByName("count")).thenReturn(variable);
        when(frame.getValue(variable)).thenReturn(value);
        when(value.type()).thenThrow(new IllegalStateException("detached VM"));

        JdiValuePathReader.Projection result =
                new JdiValuePathReader(256).read(frame, "count");

        assertEquals(JdiValuePathReader.ProjectionStatus.UNAVAILABLE, result.status());
        assertEquals("VALUE_READ_FAILED", result.reason());
        assertEquals("count", result.valuePath());
    }
}
