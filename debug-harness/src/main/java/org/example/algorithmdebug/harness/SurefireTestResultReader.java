package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 仅从本次新增或内容变化的报告中读取精确目标 testcase 结果。 */
public final class SurefireTestResultReader {

    private final SurefireDiagnosticReader diagnosticReader;

    /** 使用默认 10 MiB 安全 XML Reader。 */
    public SurefireTestResultReader() {
        this(new SurefireDiagnosticReader());
    }

    /** @param diagnosticReader 复用的安全 XML 与失败诊断 Reader */
    public SurefireTestResultReader(SurefireDiagnosticReader diagnosticReader) {
        if (diagnosticReader == null) {
            throw new IllegalArgumentException("diagnosticReader must not be null");
        }
        this.diagnosticReader = diagnosticReader;
    }

    /**
     * @param changedReports 运行前后快照确认的新建或变化报告
     * @param targetTest 精确目标 UT
     * @return 目标 testcase 事实；报告不含目标 testcase 时为空
     */
    public Optional<SurefireTestResult> read(List<Path> changedReports, TargetTest targetTest)
            throws SurefireDiagnosticException {
        if (changedReports == null || targetTest == null) {
            throw new IllegalArgumentException("changedReports and targetTest must not be null");
        }
        List<Path> ordered = changedReports.stream().map(path -> {
            if (path == null) {
                throw new IllegalArgumentException("changedReports must not contain null");
            }
            return path.toAbsolutePath().normalize();
        }).filter(path -> SurefireReportSnapshotter.isTargetReport(
                path.getFileName().toString(), targetTest))
                .sorted(Comparator.comparing(
                        (Path path) -> !path.getFileName().toString().equals(
                                "TEST-" + targetTest.className() + ".xml"))
                        .thenComparing(Path::toString))
                .toList();
        for (Path report : ordered) {
            Optional<SurefireTestResult> result = diagnosticReader.readResult(report, targetTest);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
