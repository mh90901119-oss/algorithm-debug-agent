package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseFingerprint;

final class TestFingerprints {

    private TestFingerprints() {
    }

    static CaseFingerprint sample(String selector, String inputHash) {
        return new CaseFingerprint(
                selector,
                "abc1234",
                "1".repeat(64),
                inputHash,
                "3".repeat(64),
                "21.0.8",
                "wafer-demo",
                "0.2.0");
    }
}
