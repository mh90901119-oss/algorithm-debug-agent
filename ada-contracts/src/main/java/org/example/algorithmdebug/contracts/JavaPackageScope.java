package org.example.algorithmdebug.contracts;

/** Java package 边界树的确定性匹配规则。 */
public final class JavaPackageScope {

    private JavaPackageScope() {
    }

    /**
     * 判断候选 package 是否为根 package 本身或其点分隔子 package。
     *
     * @param rootPackage 根 package
     * @param candidatePackage 候选 package
     * @return 候选 package 是否位于根 package 边界树内
     */
    public static boolean contains(String rootPackage, String candidatePackage) {
        String root = ContractChecks.requireJavaPackageName(rootPackage, "rootPackage");
        String candidate = ContractChecks.requireJavaPackageName(candidatePackage, "candidatePackage");
        return candidate.equals(root) || candidate.startsWith(root + ".");
    }
}
