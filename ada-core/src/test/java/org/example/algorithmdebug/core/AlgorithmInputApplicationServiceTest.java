package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AlgorithmInputComparison;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.staticanalysis.JavaTestAlgorithmInputLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlgorithmInputApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final ProjectId PROJECT = new ProjectId("project-1");
    private static final CaseId CASE = new CaseId("case-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "runs");

    @TempDir Path temporaryDirectory;
    private Path workspace;
    private Path module;
    private Path input;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;
    private ProjectRegistrationRepository registrations;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path source = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; class TargetTest { void runs() {"
                + " String algorithmInput = \"input/caseinput.json\"; } }");
        input = module.resolve("input/caseinput.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{\"value\":1}");
        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        registrations = new ProjectRegistrationRepository(mapper, writer);
        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Files.createDirectories(layout.projectCases(PROJECT));
        registrations.create(layout, new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT, "fixture",
                portable(module), portable(module), portable(module), "pom.xml", "MAVEN", NOW));
        CaseArchiveRepository archive = archive();
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE, PROJECT, TARGET, "fixture", "why", NOW));
        createAnalysis("analysis-1", NOW);
    }

    @Test
    void copiesInputOncePerCaseAndRejectsAChangedSource() throws Exception {
        var first = service().capture(workspace, PROJECT, CASE, new AnalysisId("analysis-1"));
        createAnalysis("analysis-2", NOW.plusSeconds(1));
        var second = service().capture(workspace, PROJECT, CASE, new AnalysisId("analysis-2"));
        assertEquals(AlgorithmInputComparison.FIRST_CAPTURE, first.summary().comparison());
        assertEquals(AlgorithmInputComparison.UNCHANGED, second.summary().comparison());
        assertEquals(new AnalysisId("analysis-1"), second.summary().previousAnalysisId().orElseThrow());
        assertEquals(first.artifact(), second.artifact());
        assertEquals("ALGORITHM_INPUT", first.artifact().artifactType());
        Path caseRoot = WorkspaceLayout.of(workspace).projectCases(PROJECT).resolve(CASE.value());
        assertTrue(Files.isRegularFile(caseRoot.resolve(
                "analyses/analysis-2/input/input-analysis.json")));
        assertEquals("{\"value\":1}", Files.readString(caseRoot.resolve(
                "input/caseinput.json")));
        assertEquals(first.artifact(), archive().requireArtifactRegistration(
                CASE, first.artifact().artifactId()).artifact());

        Files.writeString(input, "{\"value\":2}");
        createAnalysis("analysis-3", NOW.plusSeconds(2));
        CaseRunException changed = assertThrows(CaseRunException.class, () ->
                service().capture(workspace, PROJECT, CASE, new AnalysisId("analysis-3")));
        assertEquals("ALGORITHM_INPUT_CHANGED", changed.code());
        assertEquals("{\"value\":1}", Files.readString(caseRoot.resolve(
                "input/caseinput.json")));
        assertTrue(Files.notExists(caseRoot.resolve("analyses/analysis-3/input")));
    }

    @Test
    void rejectsAMissingInputBeforeCreatingTheAnalysisInputDirectory() throws Exception {
        Files.delete(input);
        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service().capture(workspace, PROJECT, CASE, new AnalysisId("analysis-1")));
        assertEquals("ALGORITHM_INPUT_FILE_NOT_FOUND", failure.code());
        assertTrue(Files.notExists(WorkspaceLayout.of(workspace).projectCases(PROJECT)
                .resolve("case-1/analyses/analysis-1/input")));
    }

    private AlgorithmInputApplicationService service() {
        return new AlgorithmInputApplicationService(
                registrations, mapper, writer, new JavaTestAlgorithmInputLocator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CaseArchiveRepository archive() {
        return new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(PROJECT), mapper, writer);
    }

    private void createAnalysis(String id, Instant at) {
        archive().createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE, new AnalysisId(id), "continue", at));
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
