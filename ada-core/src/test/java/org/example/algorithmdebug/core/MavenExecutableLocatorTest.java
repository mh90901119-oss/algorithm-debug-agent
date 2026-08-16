package org.example.algorithmdebug.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExecutableLocatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldApplyExplicitMavenHomeM2HomeAndPathPriority() throws Exception {
        Path explicit = createExecutable(temporaryDirectory.resolve("explicit/mvn.cmd"));
        Path mavenHome = createExecutable(temporaryDirectory.resolve("maven-home/bin/mvn.cmd"));
        Path m2Home = createExecutable(temporaryDirectory.resolve("m2-home/bin/mvn.cmd"));
        Path pathEntry = createExecutable(temporaryDirectory.resolve("path-entry/mvn.cmd"));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("MAVEN_HOME", mavenHome.getParent().getParent().toString());
        environment.put("M2_HOME", m2Home.getParent().getParent().toString());
        environment.put("PATH", pathEntry.getParent().toString());
        MavenExecutableLocator locator = new MavenExecutableLocator(environment, ";", true);

        assertEquals(explicit.toAbsolutePath().normalize(), locator.locate(Optional.of(explicit)).orElseThrow());

        Files.delete(explicit);
        assertEquals(mavenHome.toAbsolutePath().normalize(), locator.locate(Optional.empty()).orElseThrow());

        Files.delete(mavenHome);
        assertEquals(m2Home.toAbsolutePath().normalize(), locator.locate(Optional.empty()).orElseThrow());

        Files.delete(m2Home);
        assertEquals(pathEntry.toAbsolutePath().normalize(), locator.locate(Optional.empty()).orElseThrow());
    }

    @Test
    void shouldProbeWindowsNamesInStableOrder() throws Exception {
        Path bin = Files.createDirectories(temporaryDirectory.resolve("maven/bin"));
        Path bat = createExecutable(bin.resolve("mvn.bat"));
        createExecutable(bin.resolve("mvn.exe"));
        Map<String, String> environment = Map.of("MAVEN_HOME", bin.getParent().toString());

        Optional<Path> located = new MavenExecutableLocator(environment, ";", true).locate(Optional.empty());

        assertEquals(bat.toAbsolutePath().normalize(), located.orElseThrow());
    }

    @Test
    void shouldReturnEmptyWhenEverySupportedSourceIsAbsent() {
        MavenExecutableLocator locator = new MavenExecutableLocator(Map.of(), ";", true);

        assertTrue(locator.locate(Optional.empty()).isEmpty());
        assertTrue(locator.locate(Optional.of(temporaryDirectory.resolve("missing-mvn.cmd"))).isEmpty());
    }

    @Test
    void shouldReadSupportedWindowsEnvironmentNamesCaseInsensitively() throws Exception {
        Path pathEntry = createExecutable(temporaryDirectory.resolve("path-entry/mvn.cmd"));
        MavenExecutableLocator locator = new MavenExecutableLocator(
                Map.of("Path", pathEntry.getParent().toString()), ";", true);

        assertEquals(pathEntry.toAbsolutePath().normalize(), locator.locate(Optional.empty()).orElseThrow());
    }

    private static Path createExecutable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "maven");
    }
}
