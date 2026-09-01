package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaTestAlgorithmInputLocatorAdditionalSuffixTest {

    @TempDir Path temporaryDirectory;

    @Test
    void locatesAnInputUnderscoreJsonLiteralWithoutChangingItsName() throws Exception {
        Path source = temporaryDirectory.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;
                class TargetTest {
                    void runs() {
                        String inputFile = "data/20260901-input_.json";
                    }
                }
                """);
        Path input = temporaryDirectory.resolve("data/20260901-input_.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}");

        AlgorithmInputLocation location = new JavaTestAlgorithmInputLocator().locate(
                temporaryDirectory, new TargetTest("fixture.TargetTest", "runs"));

        assertEquals("20260901-input_.json", location.resolvedPath().getFileName().toString());
        assertEquals(input.toAbsolutePath().normalize(), location.resolvedPath());
    }
}
