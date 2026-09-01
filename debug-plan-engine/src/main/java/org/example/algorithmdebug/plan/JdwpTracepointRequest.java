package org.example.algorithmdebug.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpValueCondition;

/**
 * 大模型针对静态目录中一个方法提出的 JDWP 采集点意图。
 *
 * <p>类名、方法名和 descriptor 不由调用方提供，而是由编译器从 MethodCatalog 解析。</p>
 */
public record JdwpTracepointRequest(
        String tracepointId,
        String methodKey,
        int line,
        @JsonAlias("maxHits") int maxObservedHits,
        int maxCapturedHits,
        @JsonAlias("captureOnHits") List<Integer> captureOnMatchedHits,
        JdwpValueCondition condition,
        JdwpCaptureSpec capture) {

    /** 兼容未请求稀疏命中采集的既有调用方。 */
    public JdwpTracepointRequest(
            String tracepointId,
            String methodKey,
            int line,
            int maxHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, line, maxHits, maxHits, List.of(), null, capture);
    }

    /** 兼容采用 maxHits/captureOnHits 的历史请求构造方式。 */
    public JdwpTracepointRequest(
            String tracepointId,
            String methodKey,
            int line,
            int maxHits,
            List<Integer> captureOnHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, line, maxHits,
                captureOnHits == null || captureOnHits.isEmpty()
                        ? Math.min(maxHits, 20) : captureOnHits.size(),
                captureOnHits, null, capture);
    }

    /** 只做空值防御；方法成员关系、行范围和预算由编译器确定性验证。 */
    public JdwpTracepointRequest {
        tracepointId = Objects.requireNonNull(tracepointId, "tracepointId");
        methodKey = Objects.requireNonNull(methodKey, "methodKey");
        captureOnMatchedHits = captureOnMatchedHits == null
                ? List.of() : List.copyOf(captureOnMatchedHits);
        if (maxCapturedHits == 0) {
            maxCapturedHits = captureOnMatchedHits.isEmpty()
                    ? Math.min(maxObservedHits, 20) : captureOnMatchedHits.size();
        }
        capture = Objects.requireNonNull(capture, "capture");
    }
}
