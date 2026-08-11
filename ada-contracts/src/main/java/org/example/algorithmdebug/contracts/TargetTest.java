package org.example.algorithmdebug.contracts;

/**
 * 需要被 Debug Harness 启动的单个 JUnit 测试方法。
 *
 * @param className 测试类全限定名
 * @param methodName 测试方法名，不包含参数签名
 */
public record TargetTest(String className, String methodName) {

    /** 校验测试类和方法能够形成明确的 JUnit 选择器。 */
    public TargetTest {
        className = ContractChecks.requireJavaQualifiedName(className, "className");
        methodName = ContractChecks.requireJavaMethodName(methodName, "methodName");
    }

    /**
     * 返回外部 JUnit Launcher 使用的标准选择器。
     *
     * @return `全限定类名#方法名`
     */
    public String selector() {
        return className + "#" + methodName;
    }
}

