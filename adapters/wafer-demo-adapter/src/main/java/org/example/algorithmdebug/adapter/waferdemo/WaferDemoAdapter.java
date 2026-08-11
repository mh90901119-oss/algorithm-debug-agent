package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.SemanticHashStrategy;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 当前 Wafer Scheduling Demo 的无状态目标项目 Adapter。 */
public final class WaferDemoAdapter implements TargetProjectAdapter<WaferScheduleSnapshot> {

    private static final Path TEST_SOURCE = Path.of(
            "src", "test", "java", "org", "example", "scheduler", "wafer",
            "WaferSchedulingReproductionTest.java");

    private static final AdapterDescriptor DESCRIPTOR = new AdapterDescriptor(
            "wafer-demo",
            "0.2.0",
            "Wafer Scheduling Demo",
            Set.of(
                    AdapterCapability.BASELINE_EXECUTION,
                    AdapterCapability.INPUT_LOCATION,
                    AdapterCapability.SCHEDULE_RESULT,
                    AdapterCapability.SEMANTIC_HASH));

    private final InputLocator inputLocator = new WaferInputLocator();
    private final ScheduleResultParser<WaferScheduleSnapshot> resultParser =
            new WaferScheduleResultParser();
    private final SemanticHashStrategy<WaferScheduleSnapshot> hashStrategy =
            new WaferSemanticHashStrategy();

    @Override
    public AdapterDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProjectDescriptor inspect(Path projectRoot) throws AdapterException {
        if (projectRoot == null) {
            throw new AdapterException("ADAPTER_PROJECT_NOT_SUPPORTED", "目标项目路径不能为空");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new AdapterException(
                    "ADAPTER_PROJECT_NOT_SUPPORTED",
                    "目标路径不是项目目录: " + root);
        }
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new AdapterException(
                    "ADAPTER_BUILD_FILE_MISSING",
                    "Wafer Demo pom.xml 不存在: " + pom);
        }
        if (!Files.isRegularFile(root.resolve(TEST_SOURCE))) {
            throw new AdapterException(
                    "ADAPTER_PROJECT_NOT_SUPPORTED",
                    "未找到 Wafer Demo 目标测试源码: " + root.resolve(TEST_SOURCE));
        }
        Path complexInput = root.resolve(WaferDemoCaseCatalog.complexInputRelativePath());
        if (!Files.isRegularFile(complexInput)) {
            throw new AdapterException(
                    "ADAPTER_INPUT_NOT_FOUND",
                    "未找到 Wafer Demo 复杂 Case 输入: " + complexInput);
        }
        return new ProjectDescriptor(
                new ProjectId(WaferDemoChecks.PROJECT_ID),
                "Wafer Scheduling Demo",
                root,
                BuildTool.MAVEN,
                Path.of("pom.xml"));
    }

    @Override
    public TestLaunchSpec createLaunchSpec(
            ProjectDescriptor project,
            TargetTest targetTest,
            RunMode runMode) throws AdapterException {
        WaferDemoChecks.requireWaferDemoProject(project);
        WaferDemoCaseCatalog.requireCase(targetTest);
        WaferDemoChecks.requireNonNull(runMode, "runMode");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("test", targetTest.selector());
        properties.put("failIfNoTests", "true");
        return new TestLaunchSpec(
                project,
                targetTest,
                runMode,
                List.of("test"),
                properties,
                List.of(),
                Duration.ofMinutes(5));
    }

    @Override
    public InputLocator inputLocator() {
        return inputLocator;
    }

    @Override
    public ScheduleResultSource scheduleResultSource(
            ProjectDescriptor project,
            TargetTest targetTest) throws AdapterException {
        WaferDemoChecks.requireWaferDemoProject(project);
        Path outputDirectory = project.projectRoot()
                .resolve(WaferDemoCaseCatalog.requireCase(targetTest).resultDirectoryRelativePath())
                .normalize();
        if (!outputDirectory.startsWith(project.projectRoot())) {
            throw new AdapterException(
                    "ADAPTER_RESULT_SOURCE_INVALID",
                    "Wafer Demo 结果目录逃逸项目目录: " + outputDirectory);
        }
        return new ScheduleResultSource(outputDirectory, false);
    }

    @Override
    public ScheduleResultParser<WaferScheduleSnapshot> scheduleResultParser() {
        return resultParser;
    }

    @Override
    public SemanticHashStrategy<WaferScheduleSnapshot> semanticHashStrategy() {
        return hashStrategy;
    }
}
