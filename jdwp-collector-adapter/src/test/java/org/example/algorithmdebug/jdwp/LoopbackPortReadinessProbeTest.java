package org.example.algorithmdebug.jdwp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.harness.ManagedProcess;
import org.example.algorithmdebug.harness.ManagedProcessRunner;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.ProcessOutputWaitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopbackPortReadinessProbeTest {
    @TempDir
    Path directory;

    @Test
    void observesPortOccupiedBySuspendedChildWithoutConnecting() throws Exception {
        int port = freePort();
        try (ManagedProcess target = start(
                "ready-sleep", JdwpTargetCommandFactory.jdwpArgument(port))) {
            assertEquals(ProcessOutputWaitResult.OBSERVED,
                    new LoopbackPortReadinessProbe().await(target, port, Duration.ofSeconds(5)));
        }
    }

    @Test
    void distinguishesFreePortTimeoutFromTargetExit() throws Exception {
        int port = freePort();
        try (ManagedProcess target = start("sleep")) {
            assertEquals(ProcessOutputWaitResult.TIMED_OUT,
                    new LoopbackPortReadinessProbe().await(target, port, Duration.ofMillis(80)));
        }
        try (ManagedProcess target = start("exit", "2")) {
            assertEquals(ProcessOutputWaitResult.PROCESS_EXITED,
                    new LoopbackPortReadinessProbe().await(target, freePort(), Duration.ofSeconds(5)));
        }
    }

    private ManagedProcess start(String... arguments) throws Exception {
        return new ManagedProcessRunner().start(
                fixture(arguments), directory,
                directory.resolve("stdout-" + System.nanoTime() + ".log"),
                directory.resolve("stderr-" + System.nanoTime() + ".log"),
                ProcessLimits.defaults(), List.of());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private static List<String> fixture(String... arguments) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CoordinatorFixtureMain.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }
}
