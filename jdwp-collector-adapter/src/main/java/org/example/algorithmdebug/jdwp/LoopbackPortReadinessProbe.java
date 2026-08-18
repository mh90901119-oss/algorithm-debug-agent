package org.example.algorithmdebug.jdwp;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.ManagedProcess;
import org.example.algorithmdebug.harness.ProcessOutputWaitResult;

/** 通过 loopback 端口是否仍可绑定判断 suspended JDWP 目标是否开始监听，不建立任何目标连接。 */
final class LoopbackPortReadinessProbe {
    private static final long POLL_MILLIS = 20;

    /**
     * @param target 仍由 Coordinator 拥有的目标进程
     * @param port 本次执行已写入 Collector Plan 的 loopback 端口
     * @param timeout 正等待预算
     * @return 端口已占用、目标提前退出或等待超时
     * @throws HarnessException 非端口占用类 I/O 失败或等待被中断
     */
    ProcessOutputWaitResult await(ManagedProcess target, int port, Duration timeout)
            throws HarnessException {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为空");
        }
        JdwpTargetCommandFactory.requirePort(port);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("JDWP 就绪等待 timeout 必须为正数");
        }
        long timeoutNanos = timeout.toNanos();
        long now = System.nanoTime();
        long deadline = Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
        while (target.isAlive()) {
            if (isOccupied(port)) {
                return ProcessOutputWaitResult.OBSERVED;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return ProcessOutputWaitResult.TIMED_OUT;
            }
            try {
                Thread.sleep(Math.min(
                        POLL_MILLIS,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new HarnessException(
                        "HARNESS_RUN_INTERRUPTED", "等待 JDWP loopback 端口就绪时被中断", failure);
            }
        }
        return ProcessOutputWaitResult.PROCESS_EXITED;
    }

    private static boolean isOccupied(int port) throws HarnessException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return false;
        } catch (BindException occupied) {
            return true;
        } catch (IOException failure) {
            throw new HarnessException(
                    "HARNESS_LOOPBACK_PROBE_FAILED", "无法探测 JDWP loopback 端口是否就绪", failure);
        }
    }
}
