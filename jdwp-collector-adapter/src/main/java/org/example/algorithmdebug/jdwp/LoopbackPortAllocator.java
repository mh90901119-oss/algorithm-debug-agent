package org.example.algorithmdebug.jdwp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** 仅在 IPv4 loopback 上申请并立即释放一个临时端口。 */
public final class LoopbackPortAllocator implements JdwpPortAllocator {
    /**
     * @return 当前可绑定到 127.0.0.1 的临时端口
     * @throws JdwpAdapterException 操作系统无法分配本地端口
     */
    @Override
    public int allocate() throws JdwpAdapterException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new JdwpAdapterException(
                    "JDWP_LOOPBACK_PORT_ALLOCATION_FAILED", "无法分配本地 JDWP 端口", failure);
        }
    }
}
