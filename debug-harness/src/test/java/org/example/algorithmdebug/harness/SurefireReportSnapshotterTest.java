package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportSnapshotterTest {

    @TempDir
    Path temporaryDirectory;

    private final TargetTest target = new TargetTest("a.b.TargetTest", "runs");
    private final SurefireReportSnapshotter snapshotter = new SurefireReportSnapshotter();

    @Test
    void unchangedOldReportCannotDescribeCurrentCompileFailure() throws Exception {
        Path report = temporaryDirectory.resolve("TEST-a.b.TargetTest.xml");
        Files.writeString(report, passReport());
        SurefireReportSnapshot before = snapshotter.snapshot(temporaryDirectory, target);

        SurefireReportSnapshot after = snapshotter.snapshot(temporaryDirectory, target);

        assertTrue(snapshotter.changedTargetReports(before, after).isEmpty());
    }

    @Test
    void contentChangeIsDetectedEvenWhenFileSizeIsUnchanged() throws Exception {
        Path report = temporaryDirectory.resolve("TEST-a.b.TargetTest.xml");
        Files.writeString(report, "<testsuite marker='a'/>");
        SurefireReportSnapshot before = snapshotter.snapshot(temporaryDirectory, target);
        Files.writeString(report, "<testsuite marker='b'/>");

        SurefireReportSnapshot after = snapshotter.snapshot(temporaryDirectory, target);

        assertEquals(java.util.List.of(report.toAbsolutePath().normalize()),
                snapshotter.changedTargetReports(before, after));
    }

    @Test
    void unrelatedReportsAreNeverCandidates() throws Exception {
        Files.writeString(temporaryDirectory.resolve("TEST-a.b.OtherTest.xml"), passReport());

        SurefireReportSnapshot snapshot = snapshotter.snapshot(temporaryDirectory, target);

        assertTrue(snapshot.reports().isEmpty());
    }

    private static String passReport() {
        return "<testsuite><testcase classname='a.b.TargetTest' name='runs'/></testsuite>";
    }
}
