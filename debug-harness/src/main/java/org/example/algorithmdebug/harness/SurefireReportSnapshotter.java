package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.TargetTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 对目标 Surefire XML 做运行前后内容快照，只暴露本次新增或变化的报告。 */
public final class SurefireReportSnapshotter {

    private static final long DEFAULT_MAX_REPORT_BYTES = 10L * 1024 * 1024;
    private static final int DEFAULT_MAX_REPORTS = 32;

    private final long maximumReportBytes;
    private final int maximumReports;

    /** 使用 10 MiB 单报告与 32 个候选报告预算。 */
    public SurefireReportSnapshotter() {
        this(DEFAULT_MAX_REPORT_BYTES, DEFAULT_MAX_REPORTS);
    }

    /** @param maximumReportBytes 单报告内容 Hash 预算；@param maximumReports 目标报告数量预算 */
    public SurefireReportSnapshotter(long maximumReportBytes, int maximumReports) {
        if (maximumReportBytes <= 0 || maximumReports <= 0) {
            throw new IllegalArgumentException("Surefire 快照预算必须为正数");
        }
        this.maximumReportBytes = maximumReportBytes;
        this.maximumReports = maximumReports;
    }

    /**
     * 快照当前目标测试报告；目录不存在表示尚无报告，不视为错误。
     *
     * @throws SurefireDiagnosticException 枚举或读取报告失败、候选数量超预算
     */
    public SurefireReportSnapshot snapshot(Path reportsDirectory, TargetTest targetTest)
            throws SurefireDiagnosticException {
        if (reportsDirectory == null || targetTest == null) {
            throw new IllegalArgumentException("reportsDirectory 和 targetTest 不能为空");
        }
        Path root = reportsDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return new SurefireReportSnapshot(root, targetTest, Map.of());
        }
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(root)) {
            candidates = entries
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isTargetReport(path.getFileName().toString(), targetTest))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit((long) maximumReports + 1)
                    .toList();
        } catch (IOException | SecurityException failure) {
            throw new SurefireDiagnosticException("无法枚举 Surefire 报告目录", failure);
        }
        if (candidates.size() > maximumReports) {
            throw new SurefireDiagnosticException("目标 Surefire 报告数量超过预算");
        }
        LinkedHashMap<Path, SurefireReportSnapshot.ReportState> states = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            states.put(candidate, state(candidate));
        }
        return new SurefireReportSnapshot(root, targetTest, states);
    }

    /** 返回 after 中相对 before 新增或内容身份变化的目标报告，结果稳定排序。 */
    public List<Path> changedTargetReports(
            SurefireReportSnapshot before, SurefireReportSnapshot after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("before 和 after 不能为空");
        }
        if (!before.reportsDirectory().equals(after.reportsDirectory())
                || !before.targetTest().equals(after.targetTest())) {
            throw new IllegalArgumentException("Surefire 快照目录或目标 UT 不一致");
        }
        List<Path> changed = new ArrayList<>();
        after.reports().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> !entry.getValue().equals(before.reports().get(entry.getKey())))
                .map(Map.Entry::getKey)
                .forEach(changed::add);
        return List.copyOf(changed);
    }

    private SurefireReportSnapshot.ReportState state(Path report)
            throws SurefireDiagnosticException {
        try {
            long size = Files.size(report);
            if (size > maximumReportBytes) {
                return new SurefireReportSnapshot.ReportState(size, "", true);
            }
            return new SurefireReportSnapshot.ReportState(size, hash(report), false);
        } catch (IOException | SecurityException failure) {
            throw new SurefireDiagnosticException(
                    "无法读取 Surefire 报告状态: " + report.getFileName(), failure);
        }
    }

    private static String hash(Path report) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(report)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", failure);
        }
    }

    static boolean isTargetReport(String fileName, TargetTest targetTest) {
        String canonical = "TEST-" + targetTest.className() + ".xml";
        if (fileName.equals(canonical)) {
            return true;
        }
        String prefix = canonical.substring(0, canonical.length() - 4);
        return fileName.startsWith(prefix + "-") && fileName.endsWith(".xml");
    }
}
