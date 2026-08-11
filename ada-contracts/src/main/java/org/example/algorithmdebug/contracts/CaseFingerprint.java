package org.example.algorithmdebug.contracts;

/**
 * 运行目标 UT 前即可冻结的 Case 身份。
 *
 * @param testSelector 目标测试选择器
 * @param gitCommit 目标仓库提交或明确工作区标识
 * @param sourceHash 参与运行的源码 SHA-256
 * @param inputHash 算法输入 SHA-256
 * @param classpathHash 测试 classpath SHA-256
 * @param javaVersion 目标 JVM 版本
 * @param adapterId 目标项目 Adapter ID
 * @param adapterVersion Adapter 版本
 */
public record CaseFingerprint(
        String testSelector,
        String gitCommit,
        String sourceHash,
        String inputHash,
        String classpathHash,
        String javaVersion,
        String adapterId,
        String adapterVersion) {

    /** 校验运行前身份的全部字段。 */
    public CaseFingerprint {
        testSelector = ContractChecks.requireNonBlank(testSelector, "testSelector");
        gitCommit = ContractChecks.requireNonBlank(gitCommit, "gitCommit");
        sourceHash = ContractChecks.requireSha256(sourceHash, "sourceHash");
        inputHash = ContractChecks.requireSha256(inputHash, "inputHash");
        classpathHash = ContractChecks.requireSha256(classpathHash, "classpathHash");
        javaVersion = ContractChecks.requireNonBlank(javaVersion, "javaVersion");
        adapterId = ContractChecks.requireNonBlank(adapterId, "adapterId");
        adapterVersion = ContractChecks.requireNonBlank(adapterVersion, "adapterVersion");
    }
}
