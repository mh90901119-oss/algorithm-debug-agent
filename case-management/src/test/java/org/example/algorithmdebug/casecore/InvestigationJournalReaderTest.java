package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestigationJournalReaderTest {
    @TempDir Path temporaryDirectory;
    private Path casesRoot;
    private InvestigationEventArchive archive;
    private InvestigationJournalReader reader;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        archive = new InvestigationEventArchive(casesRoot, mapper, new AtomicDocumentWriter());
        reader = new InvestigationJournalReader(casesRoot, mapper);
    }

    @Test
    void readerSortsNumericallyAndReportsGapAsLimitation() throws Exception {
        archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(1, "event-1"));
        Path second = archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(2, "event-2"));
        archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(3, "event-3"));
        Files.delete(second);

        InvestigationJournalReader.Result result = reader.readValidatedEvents(
                InvestigationEventArchiveTest.IDENTITY);

        assertEquals(List.of(1L, 3L), result.events().stream().map(event -> event.sequence()).toList());
        assertEquals(List.of("INVESTIGATION_SEQUENCE_GAP:2"), result.limitations());
    }

    @Test
    void duplicateOrConflictingEntryFailsClosed() throws Exception {
        Path original = archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(1, "event-1"));
        Files.copy(original, original.resolveSibling("1-event-other.json"));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> reader.readValidatedEvents(InvestigationEventArchiveTest.IDENTITY));

        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", failure.code());
    }

    @Test
    void corruptedHashFailsClosed() throws Exception {
        Path original = archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(1, "event-1"));
        String content = Files.readString(original);
        Files.writeString(original, content.replaceFirst(
                "\\\"contentSha256\\\":\\\"[0-9a-f]{64}\\\"",
                "\\\"contentSha256\\\":\\\"" + "0".repeat(64) + "\\\""));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> reader.readValidatedEvents(InvestigationEventArchiveTest.IDENTITY));

        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", failure.code());
    }

    @Test
    void unknownMajorSchemaFailsClosed() throws Exception {
        Path original = archive.appendEvent(
                InvestigationEventArchiveTest.IDENTITY,
                InvestigationEventArchiveTest.event(1, "event-1"));
        String content = Files.readString(original);
        Files.writeString(original, content.replaceFirst(
                "\\\"schemaVersion\\\":\\\"1\\.0\\\"",
                "\\\"schemaVersion\\\":\\\"9.0\\\""));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> reader.readValidatedEvents(InvestigationEventArchiveTest.IDENTITY));

        assertEquals("INVESTIGATION_JOURNAL_CONFLICT", failure.code());
        assertTrue(failure.getCause() != null || failure.getMessage().contains("schema"));
    }
}
