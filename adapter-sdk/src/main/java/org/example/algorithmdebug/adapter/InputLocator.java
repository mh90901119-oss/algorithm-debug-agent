package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.util.Optional;

/** 定位某个目标 UT 使用的算法输入。 */
@FunctionalInterface
public interface InputLocator {

    /**
     * 定位输入文件；测试不依赖独立输入文件时返回空。
     *
     * @param project 目标项目
     * @param targetTest 目标 UT
     * @return 输入路径或空
     * @throws AdapterException 定位规则执行失败
     */
    Optional<Path> locate(ProjectDescriptor project, TargetTest targetTest) throws AdapterException;
}

