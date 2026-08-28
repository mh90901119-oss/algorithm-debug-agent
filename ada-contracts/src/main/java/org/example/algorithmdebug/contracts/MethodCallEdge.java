package org.example.algorithmdebug.contracts;

/**
 * 静态方法目录中一次由调用方到被调用方的源码调用边。
 *
 * @param callerKey 调用方法稳定键
 * @param calleeKey 被调用方法稳定键
 * @param sourceLine 调用表达式所在源码行，1-based
 */
public record MethodCallEdge(
        String callerKey,
        String calleeKey,
        int sourceLine,
        CallResolutionKind resolutionKind) {

    /** 兼容旧调用方和旧 MethodCatalog，缺失解析类型时按直接调用处理。 */
    public MethodCallEdge(String callerKey, String calleeKey, int sourceLine) {
        this(callerKey, calleeKey, sourceLine, CallResolutionKind.DIRECT);
    }

    /** 校验方法键和源码行。 */
    public MethodCallEdge {
        callerKey = ContractChecks.requireBoundedText(callerKey, "callerKey", 1_024, false);
        calleeKey = ContractChecks.requireBoundedText(calleeKey, "calleeKey", 1_024, false);
        resolutionKind = resolutionKind == null ? CallResolutionKind.DIRECT : resolutionKind;
        if (sourceLine < 1) {
            throw new IllegalArgumentException("sourceLine 必须为正数");
        }
    }
}
