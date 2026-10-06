package org.example.algorithmdebug.runtime;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.function.IntSupplier;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;

/** 唯一共享 Runtime 的受信任、可测试 bootstrap 输入。 */
public record RuntimeBootstrapRequest(
        String workspaceId,
        Path workspaceRoot,
        Map<String, String> environment,
        Clock clock,
        IntSupplier javaFeatureSupplier,
        Path agentJavaExecutable,
        String pathSeparator,
        boolean windows,
        AgentExecutionLog executionLog) {

    /** 防御性冻结环境，并规范化但不创建 Workspace。 */
    public RuntimeBootstrapRequest {
        if (workspaceId == null || workspaceId.isBlank()
                || !workspaceId.equals(workspaceId.strip()) || workspaceId.length() > 256
                || workspaceRoot == null || environment == null || clock == null
                || javaFeatureSupplier == null || agentJavaExecutable == null
                || pathSeparator == null || pathSeparator.isEmpty() || executionLog == null
                || environment.entrySet().stream().anyMatch(value ->
                value.getKey() == null || value.getValue() == null)) {
            throw new IllegalArgumentException("RuntimeBootstrapRequest fields are invalid");
        }
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        if (workspaceRoot.getParent() == null) {
            throw new IllegalArgumentException("workspaceRoot must not be a filesystem root");
        }
        environment = Map.copyOf(environment);
        agentJavaExecutable = agentJavaExecutable.toAbsolutePath().normalize();
    }
}
