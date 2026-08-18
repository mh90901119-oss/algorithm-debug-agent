package org.example.algorithmdebug.methodpath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetClasspathResolverTest {
    @Test
    void isAReplaceableSpiRatherThanCollectorImplementationType() throws Exception {
        TargetClasspathResolver resolver = (maven, module, output) -> List.of("target/classes");
        assertEquals(List.of("target/classes"), resolver.resolve(
                Path.of("mvn"), Path.of("module"), Path.of("output")));
    }
}
