package org.example.algorithmdebug.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdapterExceptionTest {

    @Test
    void shouldKeepStableCodeAndCause() {
        IllegalStateException cause = new IllegalStateException("broken json");
        AdapterException exception = new AdapterException(
                "ADAPTER_RESULT_PARSE_FAILED", "无法解析结果", cause);

        assertEquals("ADAPTER_RESULT_PARSE_FAILED", exception.code());
        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldRejectBlankErrorCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdapterException(" ", "message"));
    }
}

