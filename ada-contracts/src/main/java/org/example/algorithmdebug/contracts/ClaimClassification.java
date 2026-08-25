package org.example.algorithmdebug.contracts;

/** 结论与事实的来源等级。 */
public enum ClaimClassification {
    CONFIRMED_FACT, VALIDATOR_CONCLUSION, SOURCE_INFERENCE, LLM_HYPOTHESIS, MISSING_EVIDENCE
}
