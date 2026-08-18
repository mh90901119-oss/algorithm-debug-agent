package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarOutputStream;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathProcessCollectorTest {

    @TempDir
    Path directory;

    @Test
    void startFailureDoesNotFabricateStartedProcessOrExitCode() throws Exception {
        CodePathToolConfiguration configuration = configuration(
                directory.resolve("missing-java"), createEmptyJar());

        MethodPathCollectionException failure = assertThrows(
                MethodPathCollectionException.class,
                () -> new CodePathProcessCollector(configuration).collect(request(1_000)));

        assertFalse(failure.processStarted());
        assertEquals(-1, failure.exitCode());
        assertTrue(Files.isRegularFile(directory.resolve("collection/logs/stdout.log")));
        assertTrue(Files.isRegularFile(directory.resolve("collection/logs/stderr.log")));
    }

    @Test
    void timeoutReturnsStructuredManifestAndLeavesNoRunningFixture() throws Exception {
        CodePathToolConfiguration configuration = configuration(javaExecutable(), createEmptyJar());

        var result = new CodePathProcessCollector(configuration).collect(request(200));

        assertEquals(CollectionCompletion.TIMED_OUT, result.manifest().completion());
        assertTrue(result.manifest().processStarted());
        assertTrue(result.manifest().timedOut());
        // Windows 可在强制终止后取得退出码 1，其他平台可能无法取得而保留 -1；两者都不能伪装成成功。
        assertNotEquals(0, result.manifest().exitCode());
        assertEquals("NONE", result.manifest().matchPrecision());
        assertTrue(Files.isRegularFile(result.rawTrace()));
    }

    private CodePathToolConfiguration configuration(Path java, Path jar) throws Exception {
        return new CodePathToolConfiguration(
                java, jar, CodePathToolConfiguration.sha256(jar), "test",
                CodePathProcessFixtureMain.class.getName());
    }

    private Path createEmptyJar() throws Exception {
        Path jar = directory.resolve("launcher.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // 空 JAR 只承担已锁定 Launcher 路径；测试入口来自显式目标 classpath。
        }
        return jar;
    }

    private MethodPathCollectionRequest request(long timeoutMillis) throws Exception {
        Path collection = Files.createDirectories(directory.resolve("collection"));
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.TargetTest", "case1"),
                "a".repeat(64), List.of(new MethodSelector(
                        "fixture.Service#solve()V", "fixture.Service", "solve", "()V",
                        "b".repeat(64))), List.of("fixture"), "PACKAGE_SUPERSET",
                new CollectionBudget(100, 1_024 * 1_024, timeoutMillis, 100),
                100, "进程监管测试", Instant.EPOCH);
        return new MethodPathCollectionRequest(
                plan.caseId(), plan.contextId(), plan.analysisId(), new RunId("run-1"), plan,
                new CollectionId("collection-1"), directory, collection, javaExecutable(),
                Arrays.stream(System.getProperty("java.class.path").split(
                        java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .filter(value -> !value.isBlank()).toList(),
                plan.targetTest().selector());
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }
}
