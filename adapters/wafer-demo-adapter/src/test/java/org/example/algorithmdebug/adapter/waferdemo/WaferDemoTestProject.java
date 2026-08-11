package org.example.algorithmdebug.adapter.waferdemo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class WaferDemoTestProject {

    private WaferDemoTestProject() {
    }

    static void create(Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path testSource = root.resolve(
                "src/test/java/org/example/scheduler/wafer/WaferSchedulingReproductionTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, "class WaferSchedulingReproductionTest {}", StandardCharsets.UTF_8);

        createInput(root, "20260810101501.json");
        Files.createDirectories(root.resolve("output/algorithm-results"));
    }

    private static void createInput(Path root, String name) throws IOException {
        Path input = root.resolve("input/cases").resolve(name);
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}", StandardCharsets.UTF_8);
    }

}
