package org.example.algorithmdebug.contracts;

/**
 * 静态方法目录中的一个有源码声明的方法。
 *
 * @param methodKey 由类名、方法名和参数描述组成的稳定键
 * @param sourceAnchor 源码锚点
 * @param distanceFromTarget 从目标 UT 方法沿静态调用边的最短距离
 * @param targetMethod 是否为目标 UT 方法
 */
public record MethodCatalogEntry(
        String methodKey,
        SourceAnchor sourceAnchor,
        int distanceFromTarget,
        boolean targetMethod) {

    /** 校验稳定键、源码锚点和目标距离语义。 */
    public MethodCatalogEntry {
        methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 1_024, false);
        sourceAnchor = ContractChecks.requireNonNull(sourceAnchor, "sourceAnchor");
        if (distanceFromTarget < 0) {
            throw new IllegalArgumentException("distanceFromTarget 不能为负数");
        }
        if (targetMethod != (distanceFromTarget == 0)) {
            throw new IllegalArgumentException("只有目标方法的 distanceFromTarget 可以为 0");
        }
        String expected = sourceAnchor.className() + "#" + sourceAnchor.methodName()
                + sourceAnchor.descriptor();
        if (!methodKey.equals(expected)) {
            throw new IllegalArgumentException("methodKey 与 SourceAnchor 方法身份不一致");
        }
    }
}
