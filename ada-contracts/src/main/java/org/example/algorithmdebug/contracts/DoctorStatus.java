package org.example.algorithmdebug.contracts;

/** 环境诊断检查及报告的确定性严重程度。 */
public enum DoctorStatus {
    /** 检查通过。 */
    PASS,
    /** 存在风险，但不一定阻断全部能力。 */
    WARN,
    /** 检查失败，会阻断相关能力。 */
    FAIL
}
