package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.RunId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 管理一个本地 Case 的标准目录边界。 */
public final class CaseWorkspace {

    private final Path caseRoot;

    private CaseWorkspace(Path caseRoot) {
        this.caseRoot = caseRoot;
    }

    /** 创建或打开 Case 根目录及稳定子目录。 */
    public static CaseWorkspace create(Path casesRoot, CaseId caseId) throws IOException {
        if (casesRoot == null || caseId == null) {
            throw new IllegalArgumentException("casesRoot 和 caseId 不能为空");
        }
        String segment = safeSegment(caseId.value(), "caseId");
        Path root = casesRoot.toAbsolutePath().normalize().resolve(segment).normalize();
        Files.createDirectories(root.resolve("contexts"));
        Files.createDirectories(root.resolve("runs"));
        Files.createDirectories(root.resolve("analyses"));
        Files.createDirectories(root.resolve("evidence"));
        return new CaseWorkspace(root);
    }

    /** 为一次运行创建不可复用的独立目录。 */
    public Path createRun(RunId runId) throws IOException {
        if (runId == null) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        Path run = caseRoot.resolve("runs").resolve(safeSegment(runId.value(), "runId"));
        return Files.createDirectory(run);
    }

    /** @return Case 绝对根目录 */
    public Path caseRoot() {
        return caseRoot;
    }

    private static String safeSegment(String value, String field) {
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(field + " 不能包含路径分隔符");
        }
        return value;
    }
}
