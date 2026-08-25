package org.example.algorithmdebug.contracts;

/** Agent 自身采集、解析或持久化失败，与目标 UT 失败相互独立。 */
public record AgentFailureDiagnostic(String code, String message, String exceptionClass) {

    /** 创建没有底层异常类型的 Agent 诊断。 */
    public AgentFailureDiagnostic(String code, String message) {
        this(code, message, "");
    }

    /** 校验稳定错误码和说明。 */
    public AgentFailureDiagnostic {
        code = ContractChecks.requireNonBlank(code, "code");
        message = ContractChecks.requireNonBlank(message, "message");
        if (exceptionClass == null) {
            throw new IllegalArgumentException("exceptionClass must not be null");
        }
        exceptionClass = exceptionClass.strip();
        if (code.length() > 256) {
            throw new IllegalArgumentException("code must not exceed 256 characters");
        }
        if (message.length() > 8192) {
            throw new IllegalArgumentException("message must not exceed 8192 characters");
        }
        if (exceptionClass.length() > 1024) {
            throw new IllegalArgumentException("exceptionClass must not exceed 1024 characters");
        }
    }
}
