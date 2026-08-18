package org.example.algorithmdebug.core;

import org.example.algorithmdebug.jdwp.JdwpAdapterException;

/** 为一次 Collector Plan 分配 loopback 端口的可测试端口。 */
@FunctionalInterface
public interface JdwpPortProvider {
    /** @return 1..65535 的本机 loopback 端口 */
    int allocate() throws JdwpAdapterException;
}
