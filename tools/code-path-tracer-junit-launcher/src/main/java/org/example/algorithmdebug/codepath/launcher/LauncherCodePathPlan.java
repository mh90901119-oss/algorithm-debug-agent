package org.example.algorithmdebug.codepath.launcher;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Agent CodePath Plan JSON 的 Java 17 本地最小投影。 */
public record LauncherCodePathPlan(
        String schemaVersion,
        String planId,
        String caseId,
        String contextId,
        String analysisId,
        TargetTest targetTest,
        List<MethodSelector> selectors,
        String scopeMethodKey,
        Budget budget,
        String rationale,
        Instant createdAt) {

    public LauncherCodePathPlan {
        if (!"2.0".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported plan schemaVersion");
        requireText(planId, "planId");
        requireText(caseId, "caseId");
        requireText(contextId, "contextId");
        requireText(analysisId, "analysisId");
        if (targetTest == null || budget == null || createdAt == null) {
            throw new IllegalArgumentException("Plan target, budget, and createdAt are required");
        }
        selectors = selectors == null ? List.of() : List.copyOf(selectors);
        if (selectors.isEmpty() || selectors.size() > 50) {
            throw new IllegalArgumentException("Plan selectors must contain 1 to 50 entries");
        }
        HashSet<String> keys = new HashSet<>();
        if (selectors.stream().anyMatch(selector -> !keys.add(selector.methodKey()))) {
            throw new IllegalArgumentException("Plan selectors must be unique");
        }
        if (scopeMethodKey != null) {
            requireText(scopeMethodKey, "scopeMethodKey");
            if (!keys.contains(scopeMethodKey)) {
                throw new IllegalArgumentException("scopeMethodKey must belong to selectors");
            }
        }
        requireText(rationale, "rationale");
    }

    static LauncherCodePathPlan fixture(
            TargetTest targetTest, List<MethodSelector> selectors, Budget budget) {
        return new LauncherCodePathPlan(
                "2.0", "plan-1", "case-1", "context-1", "analysis-1",
                targetTest, selectors, null, budget, "fixture", Instant.EPOCH);
    }

    public record TargetTest(String className, String methodName) {
        public TargetTest {
            requireText(className, "targetTest.className");
            requireText(methodName, "targetTest.methodName");
        }

        public String selector() {
            return className + "#" + methodName;
        }
    }

    public record MethodSelector(
            String methodKey, String className, String methodName, String descriptor) {
        public MethodSelector {
            requireText(methodKey, "selector.methodKey");
            requireText(className, "selector.className");
            requireText(methodName, "selector.methodName");
            requireText(descriptor, "selector.descriptor");
            if (!methodKey.equals(className + "#" + methodName + descriptor)) {
                throw new IllegalArgumentException("Selector fields do not form methodKey");
            }
        }
    }

    public record Budget(long maxEvents, long maxBytes, long timeoutMillis) {
        public Budget {
            if (maxEvents < 1 || maxEvents > 1_000_000
                    || maxBytes < 1 || maxBytes > 50L * 1024 * 1024
                    || timeoutMillis < 1 || timeoutMillis > 20 * 60_000L) {
                throw new IllegalArgumentException("Collection budget is outside safety limits");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
