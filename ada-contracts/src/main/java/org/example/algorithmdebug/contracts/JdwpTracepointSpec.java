package org.example.algorithmdebug.contracts;

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
        JdwpCaptureSpec capture) {

    /** 校验方法身份、行范围、命中预算和捕获规格。 */
    public JdwpTracepointSpec {
        tracepointId = ContractChecks.requireOpaqueId(tracepointId, "tracepointId");
        methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 1_024, false);
        sourceAnchor = ContractChecks.requireNonNull(sourceAnchor, "sourceAnchor");
        String expectedKey = sourceAnchor.className() + "#" + sourceAnchor.methodName()
                + sourceAnchor.descriptor();
        if (!expectedKey.equals(methodKey)) {
            throw new IllegalArgumentException("methodKey 与 SourceAnchor 方法身份不一致");
        }
        if (line < sourceAnchor.startLine() || line > sourceAnchor.endLine()) {
            throw new IllegalArgumentException("JDWP 断点行不在方法源码范围内");
        }
        if (maxHits < 1 || maxHits > 20) {
            throw new IllegalArgumentException("maxHits 必须在 1 到 20 之间");
        }
        capture = ContractChecks.requireNonNull(capture, "capture");
    }
}
