package org.example.algorithmdebug.jdwp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class LoopbackPortAllocatorTest {
    @Test
    void allocatesAndReleasesAPortOnIpv4Loopback() throws Exception {
        int port = new LoopbackPortAllocator().allocate();

        try (ServerSocket verification = new ServerSocket()) {
            verification.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            assertEquals("127.0.0.1", verification.getInetAddress().getHostAddress());
        }
    }
}
