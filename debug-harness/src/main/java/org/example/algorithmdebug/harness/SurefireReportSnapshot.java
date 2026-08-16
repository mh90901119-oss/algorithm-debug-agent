package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次目标 Surefire 报告目录的有界内容快照，用于排除上轮残留 XML。 */
public record SurefireReportSnapshot(
        Path reportsDirectory,
        TargetTest targetTest,
        Map<Path, ReportState> reports) {

    /** 校验目录、目标 UT 和不可变报告状态集合。 */
    public SurefireReportSnapshot {
        if (reportsDirectory == null || targetTest == null || reports == null) {
            throw new IllegalArgumentException("SurefireReportSnapshot 字段不能为空");
        }
        Path normalizedRoot = reportsDirectory.toAbsolutePath().normalize();
        LinkedHashMap<Path, ReportState> copied = new LinkedHashMap<>();
        for (Map.Entry<Path, ReportState> entry : reports.entrySet()) {
            Path path = entry.getKey();
            ReportState state = entry.getValue();
            if (path == null || state == null) {
                throw new IllegalArgumentException("报告路径和状态不能为空");
            }
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("报告路径必须位于 Surefire 目录内");
            }
            copied.put(normalized, state);
        }
        reportsDirectory = normalizedRoot;
        reports = Map.copyOf(copied);
    }

    /** 报告文件的内容身份；超预算文件不读取内容，使用 {@code overBudget} 显式表达。 */
    public record ReportState(long sizeBytes, String sha256, boolean overBudget) {
        /** 校验大小与 Hash/预算状态一致。 */
        public ReportState {
            if (sizeBytes < 0 || sha256 == null) {
                throw new IllegalArgumentException("报告状态非法");
            }
            if (overBudget == !sha256.isEmpty()) {
                throw new IllegalArgumentException("超预算状态与 Hash 不一致");
            }
            if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("报告 Hash 必须是小写 SHA-256");
            }
        }
    }
}
