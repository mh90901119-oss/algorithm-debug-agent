package org.example.algorithmdebug.methodpath;

import java.nio.file.Path;
import java.util.List;

/** 为目标 Maven/JUnit 模块解析测试运行 classpath 的可替换端口。 */
@FunctionalInterface
public interface TargetClasspathResolver {
    /**
     * @param mavenExecutable Maven 可执行文件
     * @param moduleRoot 独立 Maven 模块目录
     * @param collectionDirectory 当前追加式 Collection 目录
     * @return 有序、不可变 classpath 条目
     */
    List<String> resolve(Path mavenExecutable, Path moduleRoot, Path collectionDirectory)
            throws MethodPathCollectionException;
}
