package org.example.algorithmdebug.casecore.logging;

import java.nio.file.Path;
import java.time.LocalDate;
import org.example.algorithmdebug.casecore.CaseArchiveLayout;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;

/** 集中解析 Case 与 bootstrap 日志路径。 */
public final class CaseLogPathResolver {

    /** @return 受限于对应 Case 根目录的日期日志路径。 */
    public Path caseLog(AgentLogContext context, LocalDate date) {
        if (context == null || date == null || !context.hasCaseIdentity()) {
            throw new IllegalArgumentException("Case log context is incomplete");
        }
        Path cases = WorkspaceLayout.of(context.workspaceRoot())
                .projectCases(new ProjectId(context.projectId()));
        Path root = CaseArchiveLayout.of(cases, new CaseId(context.caseId())).caseRoot();
        Path result = root.resolve("logs")
                .resolve("agent-" + date + ".log").toAbsolutePath().normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("Case log path escapes the Case root");
        }
        return result;
    }

    /** @return 受限于 DFX 根目录的 bootstrap 日期日志路径。 */
    public Path bootstrapLog(Path dfxDirectory, LocalDate date) {
        if (dfxDirectory == null || date == null) {
            throw new IllegalArgumentException("Bootstrap log path inputs must not be null");
        }
        Path root = dfxDirectory.toAbsolutePath().normalize();
        Path result = root.resolve("java")
                .resolve("agent-bootstrap-" + date + ".log").normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("Bootstrap log path escapes the DFX root");
        }
        return result;
    }
}
