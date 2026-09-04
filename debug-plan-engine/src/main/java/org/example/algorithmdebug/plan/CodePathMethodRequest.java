package org.example.algorithmdebug.plan;

import java.util.List;
import java.util.Objects;

/** 大模型为一个精确方法提交的投影请求。 */
public record CodePathMethodRequest(String methodKey, List<CodePathProjectionRequest> projections) {

    public CodePathMethodRequest {
        methodKey = Objects.requireNonNull(methodKey, "methodKey").strip();
        if (methodKey.isEmpty() || methodKey.length() > 2_048) {
            throw new IllegalArgumentException("methodKey must contain between 1 and 2048 characters");
        }
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        if (projections.size() > 32) {
            throw new IllegalArgumentException("A method may request at most 32 projections");
        }
    }
}
