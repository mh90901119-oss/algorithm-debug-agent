package org.example.algorithmdebug.harness;

/** 在进程输出被持续排空时观察原始字节块，不取得流或文件所有权。 */
@FunctionalInterface
interface OutputChunkObserver {
    void accept(byte[] bytes, int offset, int length);
}
