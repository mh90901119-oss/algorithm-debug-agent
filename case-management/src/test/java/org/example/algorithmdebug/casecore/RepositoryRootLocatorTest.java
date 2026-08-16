package org.example.algorithmdebug.casecore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryRootLocatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldLocateLargeRepositoryRootFromNestedAlgorithmModule() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("large-system"));
        Files.createDirectory(repository.resolve(".git"));
        Path module = Files.createDirectories(repository.resolve("modules/algorithm-scheduler"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.createDirectories(repository.resolve("unrelated/sibling/module"));

        Path located = new RepositoryRootLocator().locate(module.resolve("."));

        assertEquals(repository.toRealPath(), located);
    }

    @Test
    void shouldAcceptGitFileUsedByLinkedWorktree() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("large-system"));
        Files.writeString(repository.resolve(".git"), "gitdir: elsewhere", StandardCharsets.UTF_8);
        Path module = Files.createDirectories(repository.resolve("algorithm-scheduler"));

        assertEquals(repository.toRealPath(), new RepositoryRootLocator().locate(module));
    }

    @Test
    void shouldUseModuleAsRepositoryRootWhenNoGitMarkerExists() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("standalone-algorithm"));

        assertEquals(module.toRealPath(), new RepositoryRootLocator().locate(module));
    }

    @Test
    void shouldRejectMissingOrNonDirectoryModuleRoot() throws Exception {
        Path regularFile = Files.writeString(temporaryDirectory.resolve("module.txt"), "not a directory");
        RepositoryRootLocator locator = new RepositoryRootLocator();

        assertThrows(WorkspaceException.class, () -> locator.locate(temporaryDirectory.resolve("missing")));
        assertThrows(WorkspaceException.class, () -> locator.locate(regularFile));
        assertThrows(IllegalArgumentException.class, () -> locator.locate(null));
    }
}
