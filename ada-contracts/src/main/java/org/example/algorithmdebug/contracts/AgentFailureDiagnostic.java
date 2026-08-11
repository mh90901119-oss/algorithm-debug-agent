package org.example.algorithmdebug.contracts;

/** Agent 自身采集、解析或持久化失败，与目标 UT 失败相互独立。 */
public record AgentFailureDiagnostic(String code, String message) {

    /** 校验稳定错误码和说明。 */
    public AgentFailureDiagnostic {
        code = ContractChecks.requireNonBlank(code, "code");
        message = ContractChecks.requireNonBlank(message, "message");
    }
}
