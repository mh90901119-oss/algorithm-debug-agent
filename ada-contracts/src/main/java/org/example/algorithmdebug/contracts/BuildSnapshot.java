package org.example.algorithmdebug.contracts;

/**
 * Context 创建时可确定的构建声明和 Adapter 身份。
 *
 * @param pomSha256 模块 POM 内容 SHA-256
 * @param javaVersion Agent 用于启动目标构建的 Java 版本
 * @param adapterId Adapter ID
 * @param adapterVersion Adapter 版本
 */
public record BuildSnapshot(
        String pomSha256,
        String javaVersion,
        String adapterId,
        String adapterVersion) {

    /** 校验构建声明与 Adapter 身份。 */
    public BuildSnapshot {
        pomSha256 = ContractChecks.requireSha256(pomSha256, "pomSha256");
        javaVersion = ContractChecks.requireBoundedText(javaVersion, "javaVersion", 256, false);
        adapterId = ContractChecks.requireOpaqueId(adapterId, "adapterId");
        adapterVersion = ContractChecks.requireBoundedText(adapterVersion, "adapterVersion", 256, false);
    }
}
