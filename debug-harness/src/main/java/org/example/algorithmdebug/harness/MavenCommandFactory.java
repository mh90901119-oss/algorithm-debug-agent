package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.TestLaunchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将结构化启动规格编译为不经过 Shell 的 Maven 参数数组。 */
public final class MavenCommandFactory {

    /**
     * @param spec Adapter 提供的目标测试启动规格
     * @param options 机器相关 Maven 执行选项
     * @return 可直接传给 {@link ProcessBuilder} 的不可变参数数组
     * @throws HarnessException `argLine` 来源冲突
     */
    public List<String> create(TestLaunchSpec spec, MavenExecutionOptions options)
            throws HarnessException {
        if (spec == null || options == null) {
            throw new IllegalArgumentException("spec 与 options 不能为空");
        }
        if (!spec.jvmArguments().isEmpty() && spec.mavenProperties().containsKey("argLine")) {
            throw new HarnessException(
                    "HARNESS_LAUNCH_SPEC_CONFLICT",
                    "mavenProperties[argLine] 与 jvmArguments 不能同时声明");
        }
        List<String> command = new ArrayList<>();
        command.add(options.mavenExecutable().toString());
        for (Map.Entry<String, String> property : spec.mavenProperties().entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }
        if (!spec.jvmArguments().isEmpty()) {
            command.add("-DargLine=" + String.join(" ", spec.jvmArguments()));
        }
        command.addAll(spec.mavenGoals());
        return List.copyOf(command);
    }
}
