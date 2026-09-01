package org.example.algorithmdebug.contracts;

/**
 * 将一个 Java 方法稳定关联到某一版源码的位置。
 *
 * @param className Java 全限定类名
 * @param methodName 方法名
 * @param descriptor 稳定的 JVM 方法描述符，例如 {@code (Ljava/lang/String;I)V}
 * @param sourceRelativePath 相对 Maven 模块根的可移植源码路径
 * @param startLine 方法声明起始行，1-based
 * @param endLine 方法声明结束行，1-based
 */
public record SourceAnchor(
        String className,
        String methodName,
        String descriptor,
        String sourceRelativePath,
        int startLine,
        int endLine) {

    /** 校验 Java 身份、路径、行范围和源码 Hash。 */
    public SourceAnchor {
        className = ContractChecks.requireJavaQualifiedName(className, "className");
        methodName = ContractChecks.requireJavaExecutableName(methodName, "methodName");
        descriptor = ContractChecks.requireJvmMethodDescriptor(
                descriptor, "descriptor", methodName);
        sourceRelativePath = ContractChecks.requirePortableRelativePath(
                sourceRelativePath, "sourceRelativePath");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("SourceAnchor line range is invalid");
        }
    }
}
