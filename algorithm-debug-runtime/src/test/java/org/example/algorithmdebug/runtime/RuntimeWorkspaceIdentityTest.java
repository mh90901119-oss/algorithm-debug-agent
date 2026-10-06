package org.example.algorithmdebug.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeWorkspaceIdentityTest {
    @TempDir
    Path directory;

    @Test
    void derivesStableNonLeakingIdentityFromCanonicalWorkspace() {
        Path workspace = directory.resolve("workspace");

        String direct = RuntimeWorkspaceIdentity.derive(workspace);
        String equivalent = RuntimeWorkspaceIdentity.derive(
                workspace.resolve(".").toAbsolutePath());
        String another = RuntimeWorkspaceIdentity.derive(directory.resolve("another"));

        assertEquals(direct, equivalent);
        assertNotEquals(direct, another);
        assertFalse(direct.contains(directory.toString()));
        assertFalse(direct.contains(workspace.toAbsolutePath().normalize().toString()));
    }
}
