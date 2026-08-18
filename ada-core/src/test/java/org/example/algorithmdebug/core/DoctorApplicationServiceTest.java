package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ClasspathWorkspaceTemplateProvider;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorReport;
import org.example.algorithmdebug.contracts.DoctorStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorApplicationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldKeepAllChecksWhenJavaMavenWorkspaceAndProjectFail() throws Exception {
        Path workspace = temporaryDirectory.resolve("missing-workspace");
        Path invalidModule = Files.createDirectories(temporaryDirectory.resolve("invalid-module"));
        DoctorApplicationService doctor = new DoctorApplicationService(
                () -> 17,
                new MavenExecutableLocator(Map.of(), ";", true),
                manifestRepository());

        DoctorReport report = doctor.diagnose(workspace, Optional.of(invalidModule), Optional.empty());

        assertEquals(DoctorStatus.FAIL, report.overallStatus());
        assertEquals(5, report.checks().size());
        assertEquals(
                Set.of(
                        "JAVA_VERSION_UNSUPPORTED",
                        "MAVEN_NOT_FOUND",
                        "WORKSPACE_MANIFEST_INVALID",
                        "WORKSPACE_WRITE_FAILED",
                        "PROJECT_NOT_MAVEN"),
                codes(report));
    }

    @Test
    void shouldPassValidEnvironmentAndRemoveWriteProbeWithoutTouchingModule() throws Exception {
        Path workspace = initializeWorkspace();
        Path maven = Files.writeString(temporaryDirectory.resolve("mvn.cmd"), "maven", StandardCharsets.UTF_8);
        Path module = Files.createDirectories(temporaryDirectory.resolve("algorithm-module"));
        Path pom = Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        String pomBefore = Files.readString(pom, StandardCharsets.UTF_8);
        DoctorApplicationService doctor = new DoctorApplicationService(
                () -> 21,
                new MavenExecutableLocator(Map.of(), ";", true),
                manifestRepository());

        DoctorReport report = doctor.diagnose(workspace, Optional.of(module), Optional.of(maven));

        assertEquals(DoctorStatus.PASS, report.overallStatus());
        assertEquals(5, report.checks().size());
        assertTrue(report.checks().stream().allMatch(check -> check.status() == DoctorStatus.PASS));
        assertEquals(pomBefore, Files.readString(pom, StandardCharsets.UTF_8));
        try (var systemEntries = Files.list(workspace.resolve("system"))) {
            assertFalse(systemEntries.anyMatch(path -> path.getFileName().toString().startsWith("doctor-")));
        }
    }

    @Test
    void shouldTreatMissingOptionalProjectAsNonBlockingCheck() {
        Path workspace = initializeWorkspace();
        Path maven;
        try {
            maven = Files.writeString(temporaryDirectory.resolve("mvn.cmd"), "maven");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        DoctorApplicationService doctor = new DoctorApplicationService(
                () -> 21,
                new MavenExecutableLocator(Map.of(), ";", true),
                manifestRepository());

        DoctorReport report = doctor.diagnose(workspace, Optional.empty(), Optional.of(maven));

        assertEquals(DoctorStatus.PASS, report.overallStatus());
        DoctorCheck projectCheck = report.checks().stream()
                .filter(check -> check.name().equals("project"))
                .findFirst()
                .orElseThrow();
        assertEquals("PROJECT_NOT_REQUESTED", projectCheck.code());
    }

    @Test
    void shouldReportAllChecksWithoutProbingRejectedWorkspaceRoot() {
        DoctorApplicationService doctor = new DoctorApplicationService(
                () -> 21,
                new MavenExecutableLocator(Map.of(), ";", true),
                manifestRepository());

        DoctorReport report = doctor.diagnose(
                temporaryDirectory.getRoot(), Optional.empty(), Optional.empty());

        assertEquals(5, report.checks().size());
        assertEquals(DoctorStatus.FAIL, report.overallStatus());
        assertTrue(codes(report).contains("WORKSPACE_PATH_INVALID"));
        assertTrue(codes(report).contains("WORKSPACE_WRITE_FAILED"));
        assertTrue(codes(report).contains("PROJECT_NOT_REQUESTED"));
    }

    @Test
    void shouldIncludeInjectedCodePathToolDiagnostic() {
        Path workspace = initializeWorkspace();
        DoctorApplicationService doctor = new DoctorApplicationService(
                () -> 21,
                new MavenExecutableLocator(Map.of(), ";", true),
                manifestRepository(),
                List.of(() -> new DoctorCheck(
                        "codepath", DoctorStatus.FAIL,
                        "CODEPATH_TOOL_HASH_MISMATCH", "Launcher Hash 不匹配")));

        DoctorReport report = doctor.diagnose(
                workspace, Optional.empty(), Optional.empty());

        assertEquals(6, report.checks().size());
        assertTrue(codes(report).contains("CODEPATH_TOOL_HASH_MISMATCH"));
        assertEquals(DoctorStatus.FAIL, report.overallStatus());
    }

    private Path initializeWorkspace() {
        Path workspace = temporaryDirectory.resolve("workspace");
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        new WorkspaceInitializer(
                new WorkspaceManifestRepository(new BoundedDocumentMapper(), writer),
                writer,
                new ClasspathWorkspaceTemplateProvider(),
                FIXED_CLOCK)
                .initialize(workspace);
        return workspace;
    }

    private static WorkspaceManifestRepository manifestRepository() {
        return new WorkspaceManifestRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static Set<String> codes(DoctorReport report) {
        return report.checks().stream().map(DoctorCheck::code).collect(Collectors.toSet());
    }
}
