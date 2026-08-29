package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * 一个与静态源码身份绑定的 JDWP 采集点。
 *
 * @param tracepointId 计划内唯一的不透明 ID
 * @param methodKey 静态方法目录中的稳定方法键
 * @param sourceAnchor 方法源码锚点
 * @param line 1-based 断点源码行
 * @param maxHits 该点最大捕获命中数
 * @param capture 快照参数
 */
public record JdwpTracepointSpec(
        String tracepointId,
        String methodKey,
        SourceAnchor sourceAnchor,
        int line,
        int maxHits,
        List<Integer> captureOnHits,
        JdwpCaptureSpec capture) {

    /** 兼容未配置稀疏命中选择的既有计划。 */
    public JdwpTracepointSpec(
            String tracepointId,
            String methodKey,
            SourceAnchor sourceAnchor,
            int line,
            int maxHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, sourceAnchor, line, maxHits, List.of(), capture);
    }

    /** 校验方法身份、行范围、命中预算和捕获规格。 */
    public JdwpTracepointSpec {
        tracepointId = ContractChecks.requireOpaqueId(tracepointId, "tracepointId");
        methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 1_024, false);
        sourceAnchor = ContractChecks.requireNonNull(sourceAnchor, "sourceAnchor");
        String expectedKey = sourceAnchor.className() + "#" + sourceAnchor.methodName()
                + sourceAnchor.descriptor();
        if (!expectedKey.equals(methodKey)) {
            throw new IllegalArgumentException("methodKey does not match the SourceAnchor method identity");
        }
        if (line < sourceAnchor.startLine() || line > sourceAnchor.endLine()) {
            throw new IllegalArgumentException("JDWP breakpoint line is outside the method source range");
        }
        if (maxHits < 1 || maxHits > 20) {
            throw new IllegalArgumentException("maxHits must be between 1 and 20");
        }
        captureOnHits = captureOnHits == null ? List.of() : List.copyOf(captureOnHits);
        int previous = 0;
        for (Integer hit : captureOnHits) {
            if (hit == null || hit <= previous || hit > maxHits) {
                throw new IllegalArgumentException(
                        "captureOnHits must be strictly increasing and between 1 to maxHits ");
            }
            previous = hit;
        }
        capture = ContractChecks.requireNonNull(capture, "capture");
    }
}
