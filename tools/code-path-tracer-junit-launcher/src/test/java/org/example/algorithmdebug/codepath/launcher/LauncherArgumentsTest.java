package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherArgumentsTest {
    @TempDir Path directory;

    @Test
    void acceptsOnlyArchivedPlanAndRawTraceInSameCollection() throws Exception {
        Path plan = directory.resolve("request/plan.json");
        Files.createDirectories(plan.getParent());
        Files.writeString(plan, "{}");
        Path trace = directory.resolve("raw/codepath.jsonl");

        LauncherArguments arguments = LauncherArguments.parse(new String[] {
                "--plan", plan.toString(), "--trace", trace.toString()
        });

        assertEquals(plan.toAbsolutePath(), arguments.planFile());
        assertEquals(trace.toAbsolutePath(), arguments.traceFile());
    }

    @Test
    void rejectsLegacyPackageArgumentsAndPathsFromDifferentCollections() throws Exception {
        Path plan = directory.resolve("request/plan.json");
        Files.createDirectories(plan.getParent());
        Files.writeString(plan, "{}");

        assertThrows(IllegalArgumentException.class, () -> LauncherArguments.parse(new String[] {
                "--plan", plan.toString(), "--include", "example"
        }));
        assertThrows(IllegalArgumentException.class, () -> LauncherArguments.parse(new String[] {
                "--plan", plan.toString(), "--trace", directory.resolve("other/raw/codepath.jsonl").toString()
        }));
    }
}
