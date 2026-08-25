package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;
import org.example.algorithmdebug.contracts.WorkspaceManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;

/** 幂等初始化或验证外部 Algorithm Debug Workspace；不预建未使用目录。 */
public final class WorkspaceInitializer {
    private final WorkspaceManifestRepository manifestRepository;
    private final Clock clock;

    /** 创建仅负责 Workspace Manifest 的初始化器。 */
    public WorkspaceInitializer(WorkspaceManifestRepository manifestRepository, Clock clock) {
        if (manifestRepository == null || clock == null) {
            throw new IllegalArgumentException("WorkspaceInitializer dependencies must not be null");
        }
        this.manifestRepository = manifestRepository;
        this.clock = clock;
    }

    /** 创建缺失 Manifest；具体目录只由首次写入该目录的生产者创建。 */
    public WorkspaceInitializationResult initialize(Path root) {
        WorkspaceLayout layout = WorkspaceLayout.of(root);
        ensureWorkspaceRoot(layout.root());
        boolean created = manifestRepository.find(layout).isEmpty();
        if (created) {
            manifestRepository.create(layout, new WorkspaceManifest(
                    SchemaVersions.WORKSPACE_MANIFEST,
                    WorkspaceManifest.KIND,
                    clock.instant()));
        }
        return new WorkspaceInitializationResult(
                layout.root().toString(), created, SchemaVersions.WORKSPACE_MANIFEST);
    }

    private static void ensureWorkspaceRoot(Path root) {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException(
                        "WORKSPACE_PATH_INVALID", "Workspace root is not a directory: " + root);
            }
            Files.createDirectories(root);
        } catch (IOException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "Unable to create Workspace root: " + root, failure);
        }
    }
}
