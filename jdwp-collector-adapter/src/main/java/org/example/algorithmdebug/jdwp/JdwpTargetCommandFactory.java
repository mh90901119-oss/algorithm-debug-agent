package org.example.algorithmdebug.jdwp;

import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.MavenCommandFactory;
import org.example.algorithmdebug.harness.MavenExecutionOptions;

/** 为一次目标 UT 运行注入仅绑定 loopback 且启动即挂起的 JDWP 参数。 */
public final class JdwpTargetCommandFactory {
    private final MavenCommandFactory mavenCommands = new MavenCommandFactory();

    /**
     * @param launch Adapter 提供的 JDWP 模式 UT 启动规格
     * @param options 本机 Maven 可执行文件、日志和进程预算
     * @param port 已写入 Collector Plan 的 loopback 端口
     * @return 可直接交给 ProcessBuilder 的 Maven argv
     * @throws HarnessException Maven argLine 来源冲突
     */
    public List<String> create(
            TestLaunchSpec launch, MavenExecutionOptions options, int port)
            throws HarnessException {
        if (launch == null || options == null) {
            throw new IllegalArgumentException("launch and options must not be null");
        }
        if (launch.runMode() != RunMode.JDWP) {
            throw new IllegalArgumentException("The JDWP target command only accepts RunMode.JDWP");
        }
        requirePort(port);
        if (launch.jvmArguments().stream().anyMatch(JdwpTargetCommandFactory::isJdwpArgument)) {
            throw new IllegalArgumentException("The target launch specification must not predeclare a JDWP Agent");
        }
        List<String> arguments = new ArrayList<>(launch.jvmArguments());
        arguments.add(jdwpArgument(port));
        TestLaunchSpec injected = new TestLaunchSpec(
                launch.project(), launch.targetTest(), launch.runMode(), launch.mavenGoals(),
                launch.mavenProperties(), arguments, launch.timeout());
        return mavenCommands.create(injected, options);
    }

    private static boolean isJdwpArgument(String value) {
        return value.startsWith("-agentlib:jdwp=") || value.startsWith("-Xrunjdwp:");
    }

    static void requirePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("JDWP port must be between 1 and 65535");
        }
    }

    static String jdwpArgument(int port) {
        requirePort(port);
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
    }
}
