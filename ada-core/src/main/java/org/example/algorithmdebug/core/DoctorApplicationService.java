package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorReport;
import org.example.algorithmdebug.contracts.DoctorStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

/** 聚合 Java、Maven、Workspace 写入能力和可选项目的固定环境诊断。 */
public final class DoctorApplicationService {

    private static final int REQUIRED_JAVA_FEATURE = 21;

    private final IntSupplier javaFeatureSupplier;
    private final MavenExecutableLocator mavenLocator;
    private final WorkspaceManifestRepository manifestRepository;
    private final List<ToolDoctorProbe> toolProbes;

    /**
     * 创建环境诊断应用服务。
     *
     * @param javaFeatureSupplier Java feature 版本提供器
     * @param mavenLocator Maven 可执行文件定位器
     * @param manifestRepository Workspace Manifest 仓储
     */
    public DoctorApplicationService(
            IntSupplier javaFeatureSupplier,
            MavenExecutableLocator mavenLocator,
            WorkspaceManifestRepository manifestRepository) {
        this(javaFeatureSupplier, mavenLocator, manifestRepository, List.of());
    }

    /**
     * 创建带外部采集工具检查的环境诊断服务。
     *
     * @param javaFeatureSupplier Java feature 版本提供器
     * @param mavenLocator Maven 可执行文件定位器
     * @param manifestRepository Workspace Manifest 仓储
     * @param toolProbes 由组合根提供的工具诊断端口
     */
    public DoctorApplicationService(
            IntSupplier javaFeatureSupplier,
            MavenExecutableLocator mavenLocator,
            WorkspaceManifestRepository manifestRepository,
            List<ToolDoctorProbe> toolProbes) {
        if (javaFeatureSupplier == null || mavenLocator == null || manifestRepository == null) {
            throw new IllegalArgumentException("DoctorApplicationService dependencies must not be null");
        }
        this.javaFeatureSupplier = javaFeatureSupplier;
        this.mavenLocator = mavenLocator;
        this.manifestRepository = manifestRepository;
        this.toolProbes = List.copyOf(java.util.Objects.requireNonNull(toolProbes, "toolProbes"));
        if (this.toolProbes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("toolProbes must not contain null");
        }
    }

    /**
     * 顺序执行固定五项诊断；单项失败不会阻断后续只读检查。
     *
     * @param workspace Workspace 根目录
     * @param module 可选算法模块目录
     * @param explicitMaven 可选显式 Maven 可执行文件
     * @return 有界、不可变 Doctor 报告
     */
    public DoctorReport diagnose(
            Path workspace,
            Optional<Path> module,
            Optional<Path> explicitMaven) {
        if (workspace == null || module == null || explicitMaven == null) {
            throw new IllegalArgumentException("workspace, module and explicitMaven must not be null");
        }
        Optional<WorkspaceLayout> layout = safeWorkspaceLayout(workspace);
        List<DoctorCheck> checks = new ArrayList<>(5 + toolProbes.size());
        checks.add(checkJava());
        checks.add(checkMaven(explicitMaven));
        checks.add(checkWorkspaceManifest(layout));
        checks.add(checkWorkspaceWrite(layout));
        checks.add(checkProject(module));
        for (ToolDoctorProbe probe : toolProbes) {
            checks.add(checkTool(probe));
        }
        return DoctorReport.fromChecks(checks);
    }

    private DoctorCheck checkTool(ToolDoctorProbe probe) {
        try {
            DoctorCheck check = probe.check();
            return check != null
                    ? check
                    : fail("tool", "TOOL_DIAGNOSTIC_FAILED", "Tool diagnostic returned no result");
        } catch (RuntimeException failure) {
            return fail("tool", "TOOL_DIAGNOSTIC_FAILED", "Tool diagnostic execution failed");
        }
    }

    private DoctorCheck checkJava() {
        try {
            int feature = javaFeatureSupplier.getAsInt();
            if (feature >= REQUIRED_JAVA_FEATURE) {
                return pass("java", "JAVA_OK", "Java feature " + feature);
            }
            return fail(
                    "java", "JAVA_VERSION_UNSUPPORTED",
                    "Requires Java " + REQUIRED_JAVA_FEATURE + ", current feature " + feature);
        } catch (RuntimeException failure) {
            return fail("java", "JAVA_VERSION_UNSUPPORTED", "Failed to read the Java feature version");
        }
    }

    private DoctorCheck checkMaven(Optional<Path> explicitMaven) {
        try {
            Optional<Path> located = mavenLocator.locate(explicitMaven);
            return located.isPresent()
                    ? pass("maven", "MAVEN_OK", "Maven executable was found")
                    : fail("maven", "MAVEN_NOT_FOUND", "Maven executable was not found");
        } catch (RuntimeException failure) {
            return fail("maven", "MAVEN_NOT_FOUND", "Maven executable check failed");
        }
    }

    private DoctorCheck checkWorkspaceManifest(Optional<WorkspaceLayout> layout) {
        if (layout.isEmpty()) {
            return fail("workspace", "WORKSPACE_PATH_INVALID", "Workspace path is invalid");
        }
        try {
            manifestRepository.require(layout.orElseThrow());
            return pass("workspace", "WORKSPACE_OK", "Workspace Manifest is valid");
        } catch (RuntimeException failure) {
            return fail("workspace", "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest is missing or invalid");
        }
    }

    private DoctorCheck checkWorkspaceWrite(Optional<WorkspaceLayout> layout) {
        if (layout.isEmpty()) {
            return fail("workspace-write", "WORKSPACE_WRITE_FAILED", "Workspace path is not writable");
        }
        Path probe = null;
        try {
            Path workspaceRoot = layout.orElseThrow().root();
            if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
                return fail("workspace-write", "WORKSPACE_WRITE_FAILED", "Workspace root is not writable");
            }
            probe = Files.createTempFile(workspaceRoot, ".doctor-", ".tmp");
            Files.delete(probe);
            probe = null;
            return pass("workspace-write", "WORKSPACE_WRITE_OK", "Workspace write probe passed");
        } catch (IOException | SecurityException failure) {
            return fail("workspace-write", "WORKSPACE_WRITE_FAILED", "Workspace write probe failed");
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // 报告已标记失败；不得因清理异常阻断其余 Doctor 检查。
                }
            }
        }
    }

    private DoctorCheck checkProject(Optional<Path> module) {
        if (module.isEmpty()) {
            return pass("project", "PROJECT_NOT_REQUESTED", "Project check was not requested");
        }
        try {
            Path canonical = module.orElseThrow().toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(canonical.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
                return fail("project", "PROJECT_NOT_MAVEN", "Project is not a standalone Maven module");
            }
            return pass("project", "PROJECT_OK", "Project Maven module check passed");
        } catch (IOException | SecurityException failure) {
            return fail("project", "PROJECT_NOT_MAVEN", "Project Maven module check failed");
        }
    }

    private static Optional<WorkspaceLayout> safeWorkspaceLayout(Path workspace) {
        try {
            return Optional.of(WorkspaceLayout.of(workspace));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static DoctorCheck pass(String name, String code, String message) {
        return new DoctorCheck(name, DoctorStatus.PASS, code, message);
    }

    private static DoctorCheck fail(String name, String code, String message) {
        return new DoctorCheck(name, DoctorStatus.FAIL, code, message);
    }
}
