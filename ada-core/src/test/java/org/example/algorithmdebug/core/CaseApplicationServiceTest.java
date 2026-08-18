package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ContextMode;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseApplicationServiceTest {

    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET = new TargetTest("a.b.TargetTest", "runs");

    @TempDir
    Path temporaryDirectory;

    private Path workspace;
    private Path module;
    private ProjectRegistrationRepository registrations;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path source = module.resolve("src/test/java/a/b/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a.b; class TargetTest {}");
        Files.createDirectories(workspace.resolve("projects/project-1/cases"));
        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        registrations = new ProjectRegistrationRepository(mapper, writer);
        registrations.create(WorkspaceLayout.of(workspace), registration());
    }

    @Test
    void openingCaseCreatesMinimalContextWithoutLocatingInputOrRunningUt() {
        ArrayDeque<String> ids = new ArrayDeque<>(List.of("1", "1", "1"));
        CaseApplicationService service = new CaseApplicationService(
                registrations, mapper, writer,
                new AdapterCatalog(List.of(new MissingInputAdapter())),
                new OpaqueIdGenerator(ids::removeFirst), Clock.fixed(TIME, ZoneOffset.UTC));

        CaseOpenResult result = service.open(
                workspace, PROJECT_ID, TARGET, "输入为什么找不到？",
                Optional.empty(), Optional.of("missing-input"), ContextMode.REUSE_LATEST);

        assertTrue(result.caseCreated());
        assertEquals(0, result.digest().runCount());
        Path context = workspace.resolve(
                "projects/project-1/cases/case-1/contexts/context-1/context.json");
        com.fasterxml.jackson.databind.JsonNode json = mapper.readJson(
                context, com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(4, json.size());
        assertTrue(!json.has("inputSnapshot"));
    }

    @Test
    void inspectReturnsPersistedDigestWithoutRunningUt() {
        ArrayDeque<String> ids = new ArrayDeque<>(List.of("1", "1", "1"));
        CaseApplicationService service = new CaseApplicationService(
                registrations, mapper, writer,
                new AdapterCatalog(List.of(new MissingInputAdapter())),
                new OpaqueIdGenerator(ids::removeFirst), Clock.fixed(TIME, ZoneOffset.UTC));
        CaseOpenResult opened = service.open(
                workspace, PROJECT_ID, TARGET, "问题", Optional.empty(), Optional.empty(),
                ContextMode.REUSE_LATEST);

        assertEquals(opened.digest(), service.inspect(workspace, PROJECT_ID, opened.caseId()));
    }

    private ProjectRegistration registration() {
        String path = module.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "test", path, path, path,
                "pom.xml", "MAVEN", "a".repeat(64), TIME);
    }

    private record Snapshot(String schemaVersion) implements ScheduleResultSnapshot {
    }

    private final class MissingInputAdapter implements TargetProjectAdapter<Snapshot> {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "missing-input", "1.0", "missing-input",
                    Set.of(AdapterCapability.BASELINE_EXECUTION, AdapterCapability.INPUT_LOCATION));
        }

        @Override
        public ProjectDescriptor inspect(Path root) {
            return new ProjectDescriptor(
                    PROJECT_ID, "test", root.toAbsolutePath(), BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            throw new AssertionError("case open 不得创建运行规格");
        }

        @Override
        public InputLocator inputLocator() {
            throw new AssertionError("case open 不得定位输入");
        }

        @Override
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest targetTest) {
            throw new AssertionError("case open 不得定位运行输出");
        }

        @Override
        public ScheduleResultParser<Snapshot> scheduleResultParser() {
            throw new AssertionError("case open 不得解析运行输出");
        }

    }
}
