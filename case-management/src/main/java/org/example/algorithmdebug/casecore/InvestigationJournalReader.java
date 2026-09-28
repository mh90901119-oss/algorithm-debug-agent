package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;

/** 有界读取并校验 Investigation Journal 的版本、哈希、身份、文件名和序号。 */
public final class InvestigationJournalReader {
    private static final Pattern EVENT_FILE_PATTERN = Pattern.compile("([1-9][0-9]*)-(.+)\\.json");
    private static final String GAP_LIMITATION_PREFIX = "INVESTIGATION_SEQUENCE_GAP:";

    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;

    /**
     * @param events 按数值 sequence 排序的已校验事件
     * @param limitations 可恢复的缺号说明
     */
    public record Result(List<InvestigationEvent> events, List<String> limitations) {
        public Result {
            events = List.copyOf(ControlArchiveSupport.notNull(events, "events"));
            limitations = List.copyOf(ControlArchiveSupport.notNull(limitations, "limitations"));
        }
    }

    /**
     * @param casesRoot 已注册项目的 Case 根目录
     * @param mapper 有界 JSON Mapper
     */
    public InvestigationJournalReader(Path casesRoot, BoundedDocumentMapper mapper) {
        Path root = ControlArchiveSupport.notNull(casesRoot, "casesRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("casesRoot must be an existing regular directory");
        }
        this.casesRoot = root;
        this.mapper = ControlArchiveSupport.notNull(mapper, "mapper");
    }

    /**
     * 读取一个 Analysis 的全部可见事件。缺号作为 limitation 返回，其余矛盾全部 fail closed。
     */
    public Result readValidatedEvents(AnalysisIdentity identity) {
        AnalysisIdentity checkedIdentity = ControlArchiveSupport.notNull(identity, "identity");
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, checkedIdentity.caseId());
        Path eventsRoot = layout.investigationEventsRoot(checkedIdentity.analysisId());
        if (!Files.exists(eventsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(List.of(), List.of());
        }
        if (!Files.isDirectory(eventsRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(eventsRoot)) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation events path is not a regular directory");
        }

        List<Path> paths = listBoundedEventFiles(eventsRoot);
        List<InvestigationEvent> events = new ArrayList<>(paths.size());
        Set<Long> sequences = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (Path path : paths) {
            InvestigationEvent event = readEvent(path);
            validateIdentity(checkedIdentity, event);
            validateFileName(layout, checkedIdentity, path, event);
            if (event.sequence() > ControlArchiveSupport.MAX_INVESTIGATION_EVENTS) {
                throw new WorkspaceException(
                        ControlArchiveSupport.INVESTIGATION_LIMIT_EXCEEDED,
                        "Investigation sequence exceeds the bounded journal capacity");
            }
            if (!sequences.add(event.sequence()) || !eventIds.add(event.eventId())) {
                throw ControlArchiveSupport.investigationConflict(
                        "Duplicate Investigation sequence or eventId");
            }
            events.add(event);
        }
        events.sort(Comparator.comparingLong(InvestigationEvent::sequence));
        return new Result(events, sequenceLimitations(events));
    }

    private List<Path> listBoundedEventFiles(Path eventsRoot) {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(eventsRoot)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(path)) {
                    throw ControlArchiveSupport.investigationConflict(
                            "Investigation journal contains a non-regular entry");
                }
                if (!path.getFileName().toString().endsWith(".json")) {
                    throw ControlArchiveSupport.investigationConflict(
                            "Investigation journal contains an unknown visible document");
                }
                paths.add(path.toAbsolutePath().normalize());
                if (paths.size() > ControlArchiveSupport.MAX_INVESTIGATION_EVENTS) {
                    throw new WorkspaceException(
                            ControlArchiveSupport.INVESTIGATION_LIMIT_EXCEEDED,
                            "Investigation journal exceeds "
                                    + ControlArchiveSupport.MAX_INVESTIGATION_EVENTS + " events");
                }
            }
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation journal could not be enumerated", failure);
        }
        return paths;
    }

    private InvestigationEvent readEvent(Path path) {
        try {
            InvestigationEventArchive.ArchivedEvent document = mapper.readJson(
                    path, InvestigationEventArchive.ArchivedEvent.class);
            String actualHash = ControlArchiveSupport.digest(mapper.writeJson(document.event()));
            if (!document.contentSha256().equals(actualHash)) {
                throw ControlArchiveSupport.investigationConflict(
                        "Investigation event content hash mismatch");
            }
            return document.event();
        } catch (WorkspaceException failure) {
            if (ControlArchiveSupport.INVESTIGATION_CONFLICT.equals(failure.code())) {
                throw failure;
            }
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event schema or JSON is invalid", failure);
        } catch (RuntimeException failure) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event schema is invalid", failure);
        }
    }

    private void validateIdentity(AnalysisIdentity identity, InvestigationEvent event) {
        if (!identity.caseId().equals(event.caseId())
                || !identity.analysisId().equals(event.analysisId())) {
            throw new WorkspaceException(
                    ControlArchiveSupport.INVESTIGATION_IDENTITY_MISMATCH,
                    "Investigation event crosses Case or Analysis identity");
        }
    }

    private void validateFileName(
            CaseArchiveLayout layout,
            AnalysisIdentity identity,
            Path path,
            InvestigationEvent event) {
        String fileName = path.getFileName().toString();
        Matcher matcher = EVENT_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event file name is invalid: " + fileName);
        }
        long fileSequence;
        try {
            fileSequence = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException failure) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation sequence in file name is invalid", failure);
        }
        if (fileSequence != event.sequence() || !matcher.group(2).equals(event.eventId())) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event file name conflicts with its content");
        }
        Path expected = layout.investigationEvent(
                identity.analysisId(), event.sequence(), event.eventId());
        if (!expected.equals(path)) {
            throw ControlArchiveSupport.investigationConflict(
                    "Investigation event path conflicts with its identity");
        }
    }

    private List<String> sequenceLimitations(List<InvestigationEvent> events) {
        List<String> limitations = new ArrayList<>();
        long expected = 1;
        for (InvestigationEvent event : events) {
            while (expected < event.sequence()) {
                limitations.add(GAP_LIMITATION_PREFIX + expected);
                expected++;
            }
            expected = event.sequence() + 1;
        }
        return List.copyOf(limitations);
    }
}
