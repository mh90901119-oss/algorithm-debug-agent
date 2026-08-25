package org.example.algorithmdebug.jdwp;

/** 为一次本地 JDWP 运行分配端口的可替换边界。 */
@FunctionalInterface
interface JdwpPortAllocator {
    int allocate() throws JdwpAdapterException;
}
