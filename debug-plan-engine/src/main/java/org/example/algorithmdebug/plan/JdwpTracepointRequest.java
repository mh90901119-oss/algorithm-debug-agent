package org.example.algorithmdebug.plan;

import java.util.Objects;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpValueCondition;

/** 大模型针对当前 MethodCatalog 方法提出的 JDWP 采集点意图。 */
public record JdwpTracepointRequest(
        String tracepointId,
        String methodKey,
        int line,
        int maxObservedHits,
        int maxCapturedHits,
        int captureFirstMatchedHits,
        int captureEveryMatchedHits,
        JdwpValueCondition condition,
        JdwpCaptureSpec capture) {

    public JdwpTracepointRequest {
        tracepointId = Objects.requireNonNull(tracepointId, "tracepointId");
        methodKey = Objects.requireNonNull(methodKey, "methodKey");
        capture = Objects.requireNonNull(capture, "capture");
    }
}
