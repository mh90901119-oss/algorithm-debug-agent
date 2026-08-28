package org.example.algorithmdebug.plan;

import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;

/**
 * 大模型针对静态目录中一个方法提出的 JDWP 采集点意图。
 *
 * <p>类名、方法名、descriptor 和源码 Hash 不由调用方提供，而是由编译器从 MethodCatalog 解析。</p>
 */
public record JdwpTracepointRequest(
        String tracepointId,
        String methodKey,
        int line,
        int maxHits,
        List<Integer> captureOnHits,
        JdwpCaptureSpec capture) {

    /** 兼容未请求稀疏命中采集的既有调用方。 */
    public JdwpTracepointRequest(
            String tracepointId,
            String methodKey,
            int line,
            int maxHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, line, maxHits, List.of(), capture);
    }

    /** 只做空值防御；方法成员关系、行范围和预算由编译器确定性验证。 */
    public JdwpTracepointRequest {
        tracepointId = Objects.requireNonNull(tracepointId, "tracepointId");
        methodKey = Objects.requireNonNull(methodKey, "methodKey");
        captureOnHits = captureOnHits == null ? List.of() : List.copyOf(captureOnHits);
        capture = Objects.requireNonNull(capture, "capture");
    }
}
