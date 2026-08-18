package org.example.algorithmdebug.methodpath;

/** 外部方法调用路径采集器的稳定 SPI。 */
@FunctionalInterface
public interface MethodPathCollector {
    /** 执行一次已经有计划的采集。 */
    MethodPathCollectionResult collect(MethodPathCollectionRequest request)
            throws MethodPathCollectionException;
}
