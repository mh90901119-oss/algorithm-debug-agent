package org.example.algorithmdebug.core;

import java.nio.file.Path;
import org.example.algorithmdebug.contracts.SourceSnapshot;

/** 在静态分析边界重新读取当前模块源码摘要的确定性端口。 */
@FunctionalInterface
public interface SourceSnapshotReader {

    /**
     * @param moduleRoot 已登记 Maven 模块根目录
     * @return 当前源码摘要
     */
    SourceSnapshot capture(Path moduleRoot);
}
