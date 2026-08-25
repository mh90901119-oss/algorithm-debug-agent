package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.DoctorCheck;

/**
 * 外部采集工具的确定性 Doctor 检查端口。
 *
 * <p>具体 CodePath/JDWP 实现由 CLI 组合根注入，Core 不依赖 Collector 实现模块。</p>
 */
@FunctionalInterface
public interface ToolDoctorProbe {

    /** @return 不包含敏感绝对路径的有界工具检查结果 */
    DoctorCheck check();
}
