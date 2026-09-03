package org.example.algorithmdebug.codepath.launcher;

/** 一次方法事件上的单个确定性标量读取结果。 */
record ProjectionValue(
        String name,
        String path,
        boolean required,
        ProjectionStatus status,
        Object value,
        String failureCode) {
}
