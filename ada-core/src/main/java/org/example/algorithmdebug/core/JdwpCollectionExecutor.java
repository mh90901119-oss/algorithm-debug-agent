package org.example.algorithmdebug.core;

import org.example.algorithmdebug.jdwp.JdwpAdapterException;
import org.example.algorithmdebug.jdwp.JdwpExecutionRequest;
import org.example.algorithmdebug.jdwp.JdwpExecutionResult;

/** Core 对 JDWP 双进程协调器依赖的可替换端口。 */
@FunctionalInterface
public interface JdwpCollectionExecutor {
    /** 执行一次已归档计划绑定的 JDWP 采集。 */
    JdwpExecutionResult execute(JdwpExecutionRequest request) throws JdwpAdapterException;
}
