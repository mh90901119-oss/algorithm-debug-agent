package org.example.algorithmdebug.codepath.launcher;

import java.util.List;

/** 放入第三方 TraceEvent 的不可变快照，不保留目标算法对象引用。 */
record CapturedProjectionValues(String descriptor, List<ProjectionValue> projections) {
    CapturedProjectionValues {
        projections = List.copyOf(projections);
    }
}
