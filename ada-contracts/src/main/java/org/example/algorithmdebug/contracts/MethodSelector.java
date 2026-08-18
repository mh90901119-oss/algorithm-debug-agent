package org.example.algorithmdebug.contracts;

/**
 * 动态调用链采集计划中的一个精确方法选择器。
 *
 * @param methodKey 静态目录中的稳定方法键
 * @param className 方法所属类
 * @param methodName 方法名
 * @param descriptor JVM 方法描述符
 * @param sourceSha256 生成计划时对应源码文件的 Hash
 */
public record MethodSelector(
        String methodKey,
        String className,
        String methodName,
        String descriptor,
        String sourceSha256) {

    /** 校验方法身份与源码版本。 */
    public MethodSelector {
        methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 1_024, false);
        className = ContractChecks.requireJavaQualifiedName(className, "className");
        methodName = ContractChecks.requireJavaExecutableName(methodName, "methodName");
        descriptor = ContractChecks.requireJvmMethodDescriptor(
                descriptor, "descriptor", methodName);
        sourceSha256 = ContractChecks.requireSha256(sourceSha256, "sourceSha256");
        if (!methodKey.equals(className + "#" + methodName + descriptor)) {
            throw new IllegalArgumentException("MethodSelector 字段无法组成 methodKey");
        }
    }
}
