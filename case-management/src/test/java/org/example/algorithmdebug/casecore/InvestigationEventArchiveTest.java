package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestigationEventArchiveTest {
    static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @TempDir Path temporaryDirectory;
    private Path casesRoot;
    private InvestigationEventArchive archive;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        archive = new InvestigationEventArchive(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    @Test
    void eventSequenceIsStrictlyIncreasingWithinAnalysis() {
        Path first = archive.appendEvent(IDENTITY, event(1, "event-1"));

        WorkspaceException skipped = assertThrows(
                WorkspaceException.class, () -> archive.appendEvent(IDENTITY, event(3, "event-3")));
        Path second = archive.appendEvent(IDENTITY, event(2, "event-2"));

        assertEquals("1-event-1.json", first.getFileName().toString());
        assertEquals("2-event-2.json", second.getFileName().toString());
        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", skipped.code());
    }

    @Test
    void duplicateEventIdOrConflictingSequenceIsRejected() {
        archive.appendEvent(IDENTITY, event(1, "event-1"));
        archive.appendEvent(IDENTITY, event(2, "event-2"));

        WorkspaceException duplicateId = assertThrows(
                WorkspaceException.class, () -> archive.appendEvent(IDENTITY, event(3, "event-1")));
        WorkspaceException duplicateSequence = assertThrows(
                WorkspaceException.class, () -> archive.appendEvent(IDENTITY, event(2, "event-other")));

        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", duplicateId.code());
        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", duplicateSequence.code());
    }

    @Test
    void crossAnalysisEventIsRejected() {
        AnalysisIdentity other = new AnalysisIdentity(
                IDENTITY.projectId(), IDENTITY.caseId(), new AnalysisId("analysis-other"));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> archive.appendEvent(other, event(1, "event-1")));

        assertEquals("INVESTIGATION_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void interruptedAtomicWriteLeavesNoVisibleHalfDocument() throws Exception {
        AtomicDocumentWriter failingWriter = new AtomicDocumentWriter((source, target) -> {
            throw new IOException("simulated interruption");
        });
        InvestigationEventArchive failingArchive = new InvestigationEventArchive(
                casesRoot, new BoundedDocumentMapper(), failingWriter);

        assertThrows(WorkspaceException.class, () -> failingArchive.appendEvent(
                IDENTITY, event(1, "event-1")));

        Path eventsRoot = CaseArchiveLayout.of(casesRoot, IDENTITY.caseId())
                .investigationEventsRoot(IDENTITY.analysisId());
        assertFalse(Files.exists(eventsRoot.resolve("1-event-1.json")));
        if (Files.isDirectory(eventsRoot)) {
            try (var entries = Files.list(eventsRoot)) {
                assertTrue(entries.findAny().isEmpty());
            }
        }
    }

    static InvestigationEvent event(long sequence, String eventId) {
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME,
                new ProblemFrameId("frame-1"),
                IDENTITY.caseId(),
                IDENTITY.analysisId(),
                "scheduler selected the wrong candidate",
                "candidate A is selected",
                "candidate B is selected",
                new TargetTest("fixture.SchedulerTest", "selectsCandidate"),
                List.of(new SourceAnchor(
                        "fixture.Scheduler", "select", "()V", "src/main/java/fixture/Scheduler.java", 10, 20)),
                List.of("run:run-1"),
                List.of("which candidate filter removed A"),
                NOW);
        return new InvestigationEvent.ProblemFrameDefined(
                SchemaVersions.INVESTIGATION_EVENT,
                eventId,
                IDENTITY.caseId(),
                IDENTITY.analysisId(),
                sequence,
                NOW.plusSeconds(sequence),
                frame);
    }
}
