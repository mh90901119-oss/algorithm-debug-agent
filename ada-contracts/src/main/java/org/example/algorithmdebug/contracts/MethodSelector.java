package org.example.algorithmdebug.contracts;

/** CodePath 计划中的精确方法选择器。 */
public record MethodSelector(
        String methodKey,
        String className,
        String methodName,
        String descriptor) {

    /** 校验 JVM 方法身份可以唯一组成稳定 methodKey。 */
    public MethodSelector {
        methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 1_024, false);
        className = ContractChecks.requireJavaQualifiedName(className, "className");
        methodName = ContractChecks.requireJavaExecutableName(methodName, "methodName");
        descriptor = ContractChecks.requireJvmMethodDescriptor(descriptor, "descriptor", methodName);
        if (!methodKey.equals(className + "#" + methodName + descriptor)) {
            throw new IllegalArgumentException("MethodSelector fields do not form a methodKey");
        }
    }
}
