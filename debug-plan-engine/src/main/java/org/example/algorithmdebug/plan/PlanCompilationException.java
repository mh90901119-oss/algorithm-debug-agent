package org.example.algorithmdebug.plan;

/** 采集计划请求不能安全映射到当前静态目录时抛出的异常。 */
public final class PlanCompilationException extends RuntimeException {
    /** 创建计划编译错误。 */
    public PlanCompilationException(String message) {
        super(message);
    }
}
