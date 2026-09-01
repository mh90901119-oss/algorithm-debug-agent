package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 一个与静态源码身份绑定的 JDWP 采集点。
 *
 * @param tracepointId 计划内唯一的不透明 ID
 * @param methodKey 静态方法目录中的稳定方法键
 * @param sourceAnchor 方法源码锚点
 * @param line 1-based 断点源码行
 * @param maxObservedHits 断点最大观察次数，达到后禁用该采集点
 * @param maxCapturedHits 条件匹配后最多写入的完整快照数
 * @param captureOnMatchedHits 可选的匹配命中序号
 * @param condition 可选的顶层栈帧值路径条件
 * @param capture 快照参数
 */
public record JdwpTracepointSpec(
        String tracepointId,
        String methodKey,
        SourceAnchor sourceAnchor,
        int line,
        @JsonAlias("maxHits") int maxObservedHits,
        int maxCapturedHits,
        @JsonAlias("captureOnHits") List<Integer> captureOnMatchedHits,
        @JsonInclude(JsonInclude.Include.NON_NULL) JdwpValueCondition condition,
        JdwpCaptureSpec capture) {

    /** 兼容未配置稀疏命中选择的既有计划。 */
    public JdwpTracepointSpec(
            String tracepointId,
            String methodKey,
            SourceAnchor sourceAnchor,
            int line,
            int maxHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, sourceAnchor, line, maxHits, maxHits,
                List.of(), null, capture);
    }

    /** 兼容采用 maxHits/captureOnHits 的历史 v2 调用方。 */
    public JdwpTracepointSpec(
            String tracepointId,
            String methodKey,
            SourceAnchor sourceAnchor,
            int line,
            int maxHits,
            List<Integer> captureOnHits,
            JdwpCaptureSpec capture) {
        this(tracepointId, methodKey, sourceAnchor, line, maxHits,
                captureOnHits == null || captureOnHits.isEmpty()
                        ? Math.min(maxHits, 20) : captureOnHits.size(),
                captureOnHits, null, capture);
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
        captureOnMatchedHits = captureOnMatchedHits == null
                ? List.of() : List.copyOf(captureOnMatchedHits);
        if (maxObservedHits < 1 || maxObservedHits > 10_000) {
            throw new IllegalArgumentException("maxObservedHits must be between 1 and 10000");
        }
        if (maxCapturedHits == 0) {
            maxCapturedHits = captureOnMatchedHits.isEmpty()
                    ? Math.min(maxObservedHits, 20) : captureOnMatchedHits.size();
        }
        if (maxCapturedHits < 1 || maxCapturedHits > 20) {
            throw new IllegalArgumentException("maxCapturedHits must be between 1 and 20");
        }
        int previous = 0;
        for (Integer hit : captureOnMatchedHits) {
            if (hit == null || hit <= previous || hit > maxObservedHits) {
                throw new IllegalArgumentException(
                        "captureOnMatchedHits must be strictly increasing and within maxObservedHits");
            }
            previous = hit;
        }
        if (!captureOnMatchedHits.isEmpty()
                && captureOnMatchedHits.size() > maxCapturedHits) {
            throw new IllegalArgumentException(
                    "captureOnMatchedHits count must not exceed maxCapturedHits");
        }
        capture = ContractChecks.requireNonNull(capture, "capture");
    }
}
