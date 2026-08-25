package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;

/**
 * 面向目标构建系统的最小无状态 SPI。
 * Adapter 只检查项目并创建 UT 启动规范，不定位输入、结果目录或解析业务结果。
 */
public interface TargetProjectAdapter {

    /** @return Adapter 身份和能力描述。 */
    AdapterDescriptor descriptor();

    /**
     * 检查目标项目。
     *
     * @param projectRoot 项目模块根目录
     * @return 已检查的项目描述
     * @throws AdapterException 项目不受支持或构建文件缺失
     */
    ProjectDescriptor inspect(Path projectRoot) throws AdapterException;

    /**
     * 为任意合法目标 UT 创建结构化启动规范。
     *
     * @param project 已检查项目
     * @param targetTest 目标测试方法
     * @param runMode 运行模式
     * @return Harness 可执行规范
     * @throws AdapterException 规范无法创建
     */
    TestLaunchSpec createLaunchSpec(
            ProjectDescriptor project,
            TargetTest targetTest,
            RunMode runMode) throws AdapterException;
}
