package org.example.algorithmdebug.adapter.maven;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenJUnitAdapterTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldCreateLaunchSpecsForArbitraryValidTestMethods() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        MavenJUnitAdapter adapter = new MavenJUnitAdapter();
        var project = adapter.inspect(projectRoot);
        var first = new TargetTest("org.example.alpha.FirstAlgorithmTest", "handlesEmptyInput");
        var second = new TargetTest("com.acme.beta.OtherTest", "customFailureCase");

        var firstSpec = adapter.createLaunchSpec(project, first, RunMode.BASELINE);
        var secondSpec = adapter.createLaunchSpec(project, second, RunMode.JDWP);

        assertEquals("maven-junit", adapter.descriptor().adapterId());
        assertEquals(first.selector(), firstSpec.mavenProperties().get("test"));
        assertEquals(second.selector(), secondSpec.mavenProperties().get("test"));
        assertEquals("true", firstSpec.mavenProperties().get("failIfNoTests"));
    }

    @Test
    void shouldRejectDirectoryWithoutPomButNotInspectTestSourceWhitelist() throws Exception {
        MavenJUnitAdapter adapter = new MavenJUnitAdapter();
        AdapterException missingPom = assertThrows(
                AdapterException.class, () -> adapter.inspect(projectRoot));
        assertEquals("ADAPTER_BUILD_FILE_MISSING", missingPom.code());

        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        var project = adapter.inspect(projectRoot);
        var unknownButValid = new TargetTest("org.example.DoesNotExistYetTest", "futureCase");

        assertEquals(unknownButValid.selector(), adapter.createLaunchSpec(
                project, unknownButValid, RunMode.BASELINE).mavenProperties().get("test"));
    }
}
