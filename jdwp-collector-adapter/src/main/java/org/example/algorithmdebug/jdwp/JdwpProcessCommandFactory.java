package org.example.algorithmdebug.jdwp;

import java.util.List;
import org.example.algorithmdebug.harness.HarnessException;

/** Coordinator 的可替换 argv 编译边界，用于隔离真实工具和故障测试。 */
@FunctionalInterface
interface JdwpProcessCommandFactory {
    List<String> create(JdwpExecutionRequest request, int port) throws HarnessException;
}
