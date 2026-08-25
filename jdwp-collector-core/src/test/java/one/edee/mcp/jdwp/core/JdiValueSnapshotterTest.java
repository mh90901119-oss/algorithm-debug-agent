package one.edee.mcp.jdwp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.jdi.PrimitiveType;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdiValueSnapshotterTest {

    @Test
    void preservesPrimitiveJvmTypeAndJsonScalarType() {
        PrimitiveValue value = mock(PrimitiveValue.class);
        PrimitiveType type = mock(PrimitiveType.class);
        when(value.type()).thenReturn(type);
        when(type.name()).thenReturn("int");
        when(value.toString()).thenReturn("42");

        Object snapshot = new JdiValueSnapshotter(SnapshotLimits.DEFAULT).snapshot(value);

        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) snapshot;
        assertEquals("primitive", typed.get("$kind"));
        assertEquals("int", typed.get("$type"));
        assertEquals(42, typed.get("$value"));
    }

    @Test
    void marksStringTruncationWithoutAppendingAmbiguousText() {
        StringReference value = mock(StringReference.class);
        when(value.value()).thenReturn("abcdefghijklmnopqrst");

        Object snapshot = new JdiValueSnapshotter(new SnapshotLimits(1, 20, 16)).snapshot(value);

        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) snapshot;
        assertEquals("abcdefghijklmnop", typed.get("$value"));
        assertEquals(20, typed.get("$originalLength"));
        assertTrue((Boolean) typed.get("$truncated"));
        assertFalse(typed.get("$value").toString().contains("..."));
    }
}
