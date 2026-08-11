package org.example.algorithmdebug.contracts;

/** Case 的 Baseline 与分析生命周期，不等同于 Agent 细粒度工作流阶段。 */
public enum CaseLifecycleState {
    CREATED,
    BASELINE_RUNNING,
    BASELINE_CANDIDATE,
    BASELINE_STABLE,
    BASELINE_UNSTABLE,
    ANALYZING,
    EXPERIMENTING,
    REVISION_CREATED,
    COMPLETED,
    FAILED
}
