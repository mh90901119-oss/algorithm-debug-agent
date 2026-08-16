package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceControlPlaneContractsTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void shouldKeepModuleAndRepositoryRootsDistinct() {
        ProjectRegistration registration = registration("A".repeat(64));

        assertNotEquals(registration.repositoryRoot(), registration.moduleRoot());
        assertEquals(registration.moduleRoot(), registration.mavenExecutionRoot());
        assertEquals("a".repeat(64), registration.pomSha256());
    }

    @Test
    void shouldRejectUnsupportedBuildToolAndNonPortablePomPath() {
        assertThrows(IllegalArgumentException.class,
                () -> registration("a".repeat(64), "GRADLE", "pom.xml"));
        assertThrows(IllegalArgumentException.class,
                () -> registration("a".repeat(64), "MAVEN", "..\\pom.xml"));
    }

    @Test
    void shouldDeriveFailedDoctorStatusFromChecksAtConstruction() {
        DoctorReport report = DoctorReport.fromChecks(List.of(
                new DoctorCheck("java", DoctorStatus.PASS, "JAVA_OK", "Java 21"),
                new DoctorCheck("maven", DoctorStatus.FAIL, "MAVEN_NOT_FOUND", "Maven not found")));

        assertEquals(SchemaVersions.DOCTOR_REPORT, report.schemaVersion());
        assertEquals(DoctorStatus.FAIL, report.overallStatus());
        assertThrows(UnsupportedOperationException.class,
                () -> report.checks().add(
                        new DoctorCheck("workspace", DoctorStatus.PASS, "WORKSPACE_OK", "Workspace exists")));
    }

    @Test
    void shouldDeriveWarnDoctorStatusAndEnforceCheckBudget() {
        DoctorReport report = DoctorReport.fromChecks(List.of(
                new DoctorCheck("java", DoctorStatus.PASS, "JAVA_OK", "Java 21"),
                new DoctorCheck("maven", DoctorStatus.WARN, "MAVEN_VERSION", "Maven version is unverified")));
        List<DoctorCheck> tooManyChecks = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            tooManyChecks.add(new DoctorCheck("check-" + index, DoctorStatus.PASS, "CHECK_OK", "ok"));
        }

        assertEquals(DoctorStatus.WARN, report.overallStatus());
        assertThrows(IllegalArgumentException.class, () -> DoctorReport.fromChecks(tooManyChecks));
    }

    @Test
    void shouldRejectInconsistentDoctorStatusAndInvalidWorkspaceKind() {
        List<DoctorCheck> failingChecks = List.of(
                new DoctorCheck("maven", DoctorStatus.FAIL, "MAVEN_NOT_FOUND", "Maven not found"));

        assertThrows(IllegalArgumentException.class,
                () -> new DoctorReport(SchemaVersions.DOCTOR_REPORT, DoctorStatus.PASS, failingChecks));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceManifest(
                        SchemaVersions.WORKSPACE_MANIFEST, "OTHER_WORKSPACE", REGISTERED_AT));
    }

    @Test
    void shouldRequireRegistrationResultPayload() {
        ProjectRegistration registration = registration("a".repeat(64));

        assertEquals(registration, new ProjectRegistrationResult(registration, true).registration());
        assertThrows(NullPointerException.class, () -> new ProjectRegistrationResult(null, false));
    }

    private static ProjectRegistration registration(String pomSha256) {
        return registration(pomSha256, "MAVEN", "pom.xml");
    }

    private static ProjectRegistration registration(String pomSha256, String buildTool, String pomPath) {
        return new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                new ProjectId("algorithm-scheduler-a1b2c3d4e5f6"),
                "algorithm-scheduler",
                "D:/large-system",
                "D:/large-system/algorithm-scheduler",
                "D:/large-system/algorithm-scheduler",
                pomPath,
                buildTool,
                pomSha256,
                REGISTERED_AT);
    }
}
