package org.example.algorithmdebug.adapter.maven;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 为常规 Maven/JUnit 项目创建 Surefire 测试方法启动规范。 */
public final class MavenJUnitAdapter implements TargetProjectAdapter {

    private static final AdapterDescriptor DESCRIPTOR = new AdapterDescriptor(
            "maven-junit", "1.0.0", "Maven JUnit",
            Set.of(AdapterCapability.BASELINE_EXECUTION,
                    AdapterCapability.CODE_PATH_COLLECTION,
                    AdapterCapability.JDWP_COLLECTION));

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
                    "ADAPTER_PROJECT_NOT_SUPPORTED", "目标路径不是项目目录: " + root);
        }
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new AdapterException(
                    "ADAPTER_BUILD_FILE_MISSING", "Maven pom.xml 不存在: " + pom);
        }
        return new ProjectDescriptor(
                projectId(root), root.getFileName().toString(), root,
                BuildTool.MAVEN, Path.of("pom.xml"));
    }

    @Override
    public TestLaunchSpec createLaunchSpec(
            ProjectDescriptor project,
            TargetTest targetTest,
            RunMode runMode) throws AdapterException {
        if (project == null || targetTest == null || runMode == null) {
            throw new AdapterException("ADAPTER_LAUNCH_SPEC_INVALID", "启动规范参数不能为空");
        }
        if (project.buildTool() != BuildTool.MAVEN) {
            throw new AdapterException("ADAPTER_PROJECT_NOT_SUPPORTED", "项目构建工具不是 Maven");
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        properties.put("test", targetTest.selector());
        properties.put("failIfNoTests", "true");
        return new TestLaunchSpec(
                project, targetTest, runMode, List.of("test"), properties,
                List.of(), Duration.ofMinutes(5));
    }

    private static ProjectId projectId(Path root) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    root.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            return new ProjectId("maven-junit-" + HexFormat.of().formatHex(digest, 0, 6));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", impossible);
        }
    }
}
