package org.example.algorithmdebug.contracts;

/**
 * 项目注册命令的结果。
 *
 * @param registration 当前持久化的注册信息
 * @param created 本次调用是否创建了新注册记录
 */
public record ProjectRegistrationResult(ProjectRegistration registration, boolean created) {

    /** 确保成功结果始终携带注册信息。 */
    public ProjectRegistrationResult {
        registration = ContractChecks.requireNonNull(registration, "registration");
    }
}
