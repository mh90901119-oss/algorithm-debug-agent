package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseFingerprint;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaseResolutionServiceTest {

    private final CaseResolutionService service = new CaseResolutionService();

    @Test
    void reusesCaseForSameFingerprintAndNewInquiry() {
        CaseFingerprint fingerprint = TestFingerprints.sample("org.example.Test#case1", "2".repeat(64));
        ManagedCase current = new ManagedCase(new CaseId("CASE-001"), fingerprint, Optional.empty());

        CaseResolution resolution = service.resolve(current, fingerprint, CaseIntent.AUTO);

        assertEquals(CaseResolutionAction.REUSE_CASE, resolution.action());
    }

    @Test
    void createsNewCaseWhenTargetTestChanges() {
        ManagedCase current = new ManagedCase(
                new CaseId("CASE-001"),
                TestFingerprints.sample("org.example.Test#case1", "2".repeat(64)),
                Optional.empty());

        CaseResolution resolution = service.resolve(
                current,
                TestFingerprints.sample("org.example.Test#case2", "2".repeat(64)),
                CaseIntent.AUTO);

        assertEquals(CaseResolutionAction.NEW_CASE, resolution.action());
    }

    @Test
    void createsRevisionWhenInputOrSourceIdentityChanges() {
        ManagedCase current = new ManagedCase(
                new CaseId("CASE-001"),
                TestFingerprints.sample("org.example.Test#case1", "2".repeat(64)),
                Optional.empty());

        CaseResolution resolution = service.resolve(
                current,
                TestFingerprints.sample("org.example.Test#case1", "9".repeat(64)),
                CaseIntent.AUTO);

        assertEquals(CaseResolutionAction.NEW_REVISION, resolution.action());
        assertEquals(new CaseId("CASE-001"), resolution.parentCaseId().orElseThrow());
    }

    @Test
    void refusesForcedReuseWhenFingerprintChanged() {
        ManagedCase current = new ManagedCase(
                new CaseId("CASE-001"),
                TestFingerprints.sample("org.example.Test#case1", "2".repeat(64)),
                Optional.empty());

        CaseResolution resolution = service.resolve(
                current,
                TestFingerprints.sample("org.example.Test#case1", "9".repeat(64)),
                CaseIntent.FORCE_REUSE);

        assertEquals(CaseResolutionAction.CONFIRMATION_REQUIRED, resolution.action());
    }
}
