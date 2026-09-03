package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * 与当前源码位置绑定的 JDWP 采集点。
 *
 * @param maxObservedHits 条件判断前最多观察的断点命中数
 * @param maxCapturedHits 最多写入的完整快照数
 * @param captureFirstMatchedHits 连续捕获最前面的匹配命中数
 * @param captureEveryMatchedHits 首批之后每第 N 个匹配命中捕获一次；0 表示不做周期采样
 */
public record JdwpTracepointSpec(
        String tracepointId,
        String methodKey,
        SourceAnchor sourceAnchor,
        int line,
        int maxObservedHits,
        int maxCapturedHits,
        int captureFirstMatchedHits,
        int captureEveryMatchedHits,
        List<JdwpValueCondition> conditions,
        JdwpCaptureSpec capture) {

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
        if (maxObservedHits < 1 || maxObservedHits > 100_000) {
            throw new IllegalArgumentException("maxObservedHits must be between 1 and 100000");
        }
        if (maxCapturedHits < 1 || maxCapturedHits > 200) {
            throw new IllegalArgumentException("maxCapturedHits must be between 1 and 200");
        }
        if (captureFirstMatchedHits < 0 || captureFirstMatchedHits > maxCapturedHits) {
            throw new IllegalArgumentException("captureFirstMatchedHits must be between 0 and maxCapturedHits");
        }
        if (captureEveryMatchedHits < 0 || captureEveryMatchedHits > maxObservedHits
                || (captureFirstMatchedHits == 0 && captureEveryMatchedHits == 0)) {
            throw new IllegalArgumentException("The matched-hit sampling policy is invalid");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > 4) {
            throw new IllegalArgumentException("JDWP tracepoint supports at most 4 conditions");
        }
        capture = ContractChecks.requireNonNull(capture, "capture");
    }
}
