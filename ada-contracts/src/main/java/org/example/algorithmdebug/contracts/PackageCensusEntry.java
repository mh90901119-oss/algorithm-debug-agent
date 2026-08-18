package org.example.algorithmdebug.contracts;

/**
 * 静态分析实际扫描到的一个精确 Java package 及其方法声明数。
 *
 * @param packageName 精确 package 名，不表示前缀
 * @param methodCount 已扫描到的方法与构造器声明数
 */
public record PackageCensusEntry(String packageName, int methodCount) {

    /** 校验 package 身份和正方法数。 */
    public PackageCensusEntry {
        packageName = ContractChecks.requireJavaPackageName(packageName, "packageName");
        if (methodCount < 1) {
            throw new IllegalArgumentException("methodCount 必须为正数");
        }
    }
}
