package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CollectorDebugPlanCompatibilityTest {

    @Test
    void lockedCollectorAcceptsGeneratedPlan() throws Exception {
        String configured = System.getProperty("jdwp.collector.jar", "");
        Assumptions.assumeTrue(!configured.isBlank(), "需要 -Djdwp.collector.jar 运行真实兼容测试");
        Path jar = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(jar), "锁定 Collector JAR 不存在");
        byte[] json = new CollectorDebugPlanWriter().write(plan(), 50_005);

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = Class.forName(
                    "one.edee.mcp.jdwp.collector.DebugPlan", true, loader);
            Object externalPlan = new ObjectMapper().readValue(json, type);
            assertDoesNotThrow(() -> type.getMethod("validate").invoke(externalPlan));
        }
    }

    private static JdwpCollectionPlan plan() {
        String hash = "a".repeat(64);
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20, hash);
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN,
                new PlanId("plan-1"), new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
                hash, List.of(new JdwpTracepointSpec(
                        "point-1", "fixture.Algorithm#schedule()V", anchor, 11, 3,
                        JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults(), "检查调度决策变量",
                Instant.parse("2026-08-18T00:00:00Z"));
    }
}
