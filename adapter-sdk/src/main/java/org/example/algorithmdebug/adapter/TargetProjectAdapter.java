package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;

/**
 * 通用 Agent 适配一个具体算法仓库的组合 SPI。
 *
 * <p>实现应保持无状态：不得把最近一次 inspect 的项目保存为可变字段，项目上下文由
 * {@link ProjectDescriptor} 显式传递。</p>
 *
 * @param <T> 该 Adapter 的类型化调度结果快照
 */
public interface TargetProjectAdapter<T extends ScheduleResultSnapshot> {

    /** @return Adapter 身份和能力描述 */
    AdapterDescriptor descriptor();

    /**
     * 检查目标仓库并生成项目描述。
     *
     * @param projectRoot 目标项目根目录
     * @return 已检查的项目描述
     * @throws AdapterException 项目不受支持或构建文件缺失
     */
    ProjectDescriptor inspect(Path projectRoot) throws AdapterException;

    /**
     * 为指定项目、UT 和运行模式创建结构化启动规格。
     *
     * @param project 已检查的项目
     * @param targetTest 目标 UT
     * @param runMode 运行模式
     * @return Harness 可执行的启动规格
     * @throws AdapterException 当前模式不受支持或规格无法创建
     */
    TestLaunchSpec createLaunchSpec(
            ProjectDescriptor project,
            TargetTest targetTest,
            RunMode runMode) throws AdapterException;

    /** @return 输入定位策略 */
    InputLocator inputLocator();

    /**
     * 描述目标 UT 的动态调度结果输出目录。
     *
     * @param project 已检查的目标项目
     * @param targetTest 目标 UT
     * @return 输出目录及扫描范围
     * @throws AdapterException 当前测试没有可识别的结果源
     */
    ScheduleResultSource scheduleResultSource(
            ProjectDescriptor project,
            TargetTest targetTest) throws AdapterException;

    /** @return 与泛型 T 一致的结果解析器 */
    ScheduleResultParser<T> scheduleResultParser();
}
