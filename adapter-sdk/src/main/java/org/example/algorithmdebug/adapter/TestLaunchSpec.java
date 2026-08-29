package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.TargetTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Debug Harness 可以安全编译为参数数组的目标 UT 启动规格。
 *
 * <p>本类型不接受拼接后的 Shell 命令，避免 Adapter 绕过 Harness 的转义、超时和审计。</p>
 *
 * @param project 目标项目
 * @param targetTest 目标测试方法
 * @param runMode 运行模式
 * @param mavenGoals Maven goal 列表
 * @param mavenProperties Maven property 键值
 * @param jvmArguments 目标测试 JVM 参数列表
 * @param timeout 整个测试运行超时
 */
public record TestLaunchSpec(
        ProjectDescriptor project,
        TargetTest targetTest,
        RunMode runMode,
        List<String> mavenGoals,
        Map<String, String> mavenProperties,
        List<String> jvmArguments,
        Duration timeout) {

    /** 校验启动规格并防御性复制参数集合。 */
    public TestLaunchSpec {
        project = AdapterChecks.requireNonNull(project, "project");
        targetTest = AdapterChecks.requireNonNull(targetTest, "targetTest");
        runMode = AdapterChecks.requireNonNull(runMode, "runMode");
        mavenGoals = AdapterChecks.immutableTokens(mavenGoals, "mavenGoals", true);
        if (mavenGoals.isEmpty()) {
            throw new IllegalArgumentException("mavenGoals must not be an empty collection");
        }
        mavenProperties = AdapterChecks.immutableMavenProperties(mavenProperties);
        jvmArguments = AdapterChecks.immutableTokens(jvmArguments, "jvmArguments", true);
        timeout = AdapterChecks.requirePositiveDuration(timeout, "timeout");
    }
}
