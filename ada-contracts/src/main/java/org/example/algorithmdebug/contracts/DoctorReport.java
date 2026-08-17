package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * Agent 环境诊断报告。
 *
 * <p>总状态由检查项确定性归约，优先级固定为 {@code FAIL > WARN > PASS}。</p>
 *
 * @param schemaVersion 报告 Schema 版本
 * @param overallStatus 全部检查项归约后的状态
 * @param checks 有界且不可变的检查项
 */
public record DoctorReport(
        String schemaVersion,
        DoctorStatus overallStatus,
        List<DoctorCheck> checks) {

    /** 单份报告允许的最大检查项数，防止工具响应无界增长。 */
    public static final int MAX_CHECKS = 32;

    /** 校验版本、检查预算以及总状态与检查项的一致性。 */
    public DoctorReport {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.DOCTOR_REPORT.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 DoctorReport schemaVersion: " + schemaVersion);
        }
        overallStatus = ContractChecks.requireNonNull(overallStatus, "overallStatus");
        checks = ContractChecks.immutableList(checks, "checks");
        if (checks.size() > MAX_CHECKS) {
            throw new IllegalArgumentException("checks 数量不能超过 " + MAX_CHECKS);
        }
        DoctorStatus derivedStatus = deriveStatus(checks);
        if (overallStatus != derivedStatus) {
            throw new IllegalArgumentException(
                    "overallStatus 与 checks 不一致，期望 " + derivedStatus + "，实际 " + overallStatus);
        }
    }

    /**
     * 从检查项创建当前版本报告，并按固定优先级计算总状态。
     *
     * @param checks 环境检查项
     * @return 当前版本的不可变诊断报告
     */
    public static DoctorReport fromChecks(List<DoctorCheck> checks) {
        List<DoctorCheck> copied = ContractChecks.immutableList(checks, "checks");
        return new DoctorReport(SchemaVersions.DOCTOR_REPORT, deriveStatus(copied), copied);
    }

    private static DoctorStatus deriveStatus(List<DoctorCheck> checks) {
        if (checks.stream().anyMatch(check -> check.status() == DoctorStatus.FAIL)) {
            return DoctorStatus.FAIL;
        }
        if (checks.stream().anyMatch(check -> check.status() == DoctorStatus.WARN)) {
            return DoctorStatus.WARN;
        }
        return DoctorStatus.PASS;
    }
}
