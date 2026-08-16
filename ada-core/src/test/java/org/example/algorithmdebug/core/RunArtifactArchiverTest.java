package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ArtifactReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunArtifactArchiverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void existingArtifactIsNeverOverwritten() throws Exception {
        Path runRoot = Files.createDirectory(temporaryDirectory.resolve("run"));
        Path first = Files.writeString(temporaryDirectory.resolve("first.xml"), "first");
        Path second = Files.writeString(temporaryDirectory.resolve("second.xml"), "other");
        RunArtifactArchiver archiver = new RunArtifactArchiver();
        ArtifactReference original = archiver.copy(
                runRoot, first, Path.of("raw/surefire/report.xml"),
                "artifact-one", "SUREFIRE_XML", "application/xml", 1024);

        assertThrows(CaseRunException.class, () -> archiver.copy(
                runRoot, second, Path.of("raw/surefire/report.xml"),
                "artifact-two", "SUREFIRE_XML", "application/xml", 1024));

        assertEquals("first", Files.readString(runRoot.resolve("raw/surefire/report.xml")));
        assertEquals(original.sha256(), archiver.reference(
                runRoot, runRoot.resolve("raw/surefire/report.xml"),
                "artifact-one", "SUREFIRE_XML", "application/xml", 1024).sha256());
    }

    @Test
    void destinationCannotEscapeRunRoot() throws Exception {
        Path runRoot = Files.createDirectory(temporaryDirectory.resolve("run"));
        Path source = Files.writeString(temporaryDirectory.resolve("source.xml"), "content");

        assertThrows(CaseRunException.class, () -> new RunArtifactArchiver().copy(
                runRoot, source, Path.of("../escape.xml"),
                "artifact-one", "SUREFIRE_XML", "application/xml", 1024));
    }
}
