package org.example.algorithmdebug.contracts;

/**
 * 一项可机器判断的环境诊断结果。
 *
 * @param name 稳定的检查名称
 * @param status 检查严重程度
 * @param code 稳定的机器可读结果码
 * @param message 面向用户和大模型的简短说明
 */
public record DoctorCheck(String name, DoctorStatus status, String code, String message) {

    /** 校验诊断项的必填字段。 */
    public DoctorCheck {
        name = ContractChecks.requireNonBlank(name, "name");
        status = ContractChecks.requireNonNull(status, "status");
        code = ContractChecks.requireNonBlank(code, "code");
        message = ContractChecks.requireNonBlank(message, "message");
    }
}
