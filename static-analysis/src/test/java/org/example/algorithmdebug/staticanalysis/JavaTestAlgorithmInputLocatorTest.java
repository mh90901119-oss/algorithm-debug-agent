package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.example.algorithmdebug.contracts.AlgorithmInputPathKind;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaTestAlgorithmInputLocatorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void locatesOneDirectRelativeStringLiteralAndIgnoresNestedDeclarations() throws Exception {
        Path module = moduleWithSource("""
                package fixture;
                class TargetTest {
                    void runs() {
                        String algorithmInput = "input/caseinput.json";
                        if (true) { String ignored = "input/nestedinput.json"; }
                    }
                }
                """);
        AlgorithmInputLocation location = new JavaTestAlgorithmInputLocator().locate(
                module, new TargetTest("fixture.TargetTest", "runs"));
        assertEquals("algorithmInput", location.variableName());
        assertEquals(AlgorithmInputPathKind.RELATIVE, location.pathKind());
        assertEquals(module.resolve("input/caseinput.json").normalize(), location.resolvedPath());
        assertEquals(4, location.sourceLine());
    }

    @Test
    void acceptsAnAbsoluteDirectLiteral() throws Exception {
        Path input = Files.writeString(temporaryDirectory.resolve("absoluteinput.json"), "{}");
        String literal = input.toString().replace("\\", "\\\\");
        Path module = moduleWithSource("package fixture; class TargetTest {"
                + " void runs() { java.lang.String input = \"" + literal + "\"; } }");
        AlgorithmInputLocation location = new JavaTestAlgorithmInputLocator().locate(
                module, new TargetTest("fixture.TargetTest", "runs"));
        assertEquals(AlgorithmInputPathKind.ABSOLUTE, location.pathKind());
        assertEquals(input.toAbsolutePath().normalize(), location.resolvedPath());
    }

    @Test
    void rejectsMultipleDistinctInputsAndComputedExpressions() throws Exception {
        Path multiple = moduleWithSource("""
                package fixture; class TargetTest { void runs() {
                    String first = "oneinput.json";
                    String second = "twoinput.json";
                } }
                """);
        AlgorithmInputLocationException multipleFailure = assertThrows(
                AlgorithmInputLocationException.class,
                () -> new JavaTestAlgorithmInputLocator().locate(
                        multiple, new TargetTest("fixture.TargetTest", "runs")));
        assertEquals("MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED", multipleFailure.code());

        Path computed = Files.createDirectory(temporaryDirectory.resolve("computed"));
        Files.writeString(computed.resolve("pom.xml"), "<project/>");
        Path source = computed.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; class TargetTest { void runs() {"
                + " String input = \"dir/\" + \"caseinput.json\"; } }");
        AlgorithmInputLocationException expressionFailure = assertThrows(
                AlgorithmInputLocationException.class,
                () -> new JavaTestAlgorithmInputLocator().locate(
                        computed, new TargetTest("fixture.TargetTest", "runs")));
        assertEquals("ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED", expressionFailure.code());
    }

    private Path moduleWithSource(String content) throws Exception {
        Path module = Files.createDirectory(temporaryDirectory.resolve("module-" + System.nanoTime()));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path source = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return module;
    }
}
