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
        if (javaFeatureSupplier == null || mavenLocator == null || manifestRepository == null) {
            throw new IllegalArgumentException("DoctorApplicationService 依赖不能为空");
        }
        this.javaFeatureSupplier = javaFeatureSupplier;
        this.mavenLocator = mavenLocator;
        this.manifestRepository = manifestRepository;
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
            throw new IllegalArgumentException("workspace、module 和 explicitMaven 不能为空");
        }
        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        List<DoctorCheck> checks = new ArrayList<>(5);
        checks.add(checkJava());
        checks.add(checkMaven(explicitMaven));
        checks.add(checkWorkspaceManifest(layout));
        checks.add(checkWorkspaceWrite(layout));
        checks.add(checkProject(module));
        return DoctorReport.fromChecks(checks);
    }

    private DoctorCheck checkJava() {
        try {
            int feature = javaFeatureSupplier.getAsInt();
            if (feature >= REQUIRED_JAVA_FEATURE) {
                return pass("java", "JAVA_OK", "Java feature " + feature);
            }
            return fail(
                    "java", "JAVA_VERSION_UNSUPPORTED",
                    "需要 Java " + REQUIRED_JAVA_FEATURE + "，当前 feature " + feature);
        } catch (RuntimeException failure) {
            return fail("java", "JAVA_VERSION_UNSUPPORTED", "无法读取 Java feature 版本");
        }
    }

    private DoctorCheck checkMaven(Optional<Path> explicitMaven) {
        try {
            Optional<Path> located = mavenLocator.locate(explicitMaven);
            return located.isPresent()
                    ? pass("maven", "MAVEN_OK", "已找到 Maven 可执行文件")
                    : fail("maven", "MAVEN_NOT_FOUND", "未找到 Maven 可执行文件");
        } catch (RuntimeException failure) {
            return fail("maven", "MAVEN_NOT_FOUND", "Maven 可执行文件检查失败");
        }
    }

    private DoctorCheck checkWorkspaceManifest(WorkspaceLayout layout) {
        try {
            manifestRepository.require(layout);
            return pass("workspace", "WORKSPACE_OK", "Workspace Manifest 有效");
        } catch (RuntimeException failure) {
            return fail("workspace", "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 缺失或无效");
        }
    }

    private DoctorCheck checkWorkspaceWrite(WorkspaceLayout layout) {
        Path probe = null;
        try {
            Path systemRoot = layout.systemRoot();
            if (!Files.isDirectory(systemRoot, LinkOption.NOFOLLOW_LINKS)) {
                return fail("workspace-write", "WORKSPACE_WRITE_FAILED", "Workspace system 目录不可用");
            }
            probe = Files.createTempFile(systemRoot, "doctor-", ".tmp");
            Files.delete(probe);
            probe = null;
            return pass("workspace-write", "WORKSPACE_WRITE_OK", "Workspace 写入探针通过");
        } catch (IOException | SecurityException failure) {
            return fail("workspace-write", "WORKSPACE_WRITE_FAILED", "Workspace 写入探针失败");
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
            return pass("project", "PROJECT_NOT_REQUESTED", "未请求项目检查");
        }
        try {
            Path canonical = module.orElseThrow().toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(canonical.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
                return fail("project", "PROJECT_NOT_MAVEN", "项目不是可独立运行的 Maven 模块");
            }
            return pass("project", "PROJECT_OK", "项目 Maven 模块检查通过");
        } catch (IOException | SecurityException failure) {
            return fail("project", "PROJECT_NOT_MAVEN", "项目 Maven 模块检查失败");
        }
    }

    private static DoctorCheck pass(String name, String code, String message) {
        return new DoctorCheck(name, DoctorStatus.PASS, code, message);
    }

    private static DoctorCheck fail(String name, String code, String message) {
        return new DoctorCheck(name, DoctorStatus.FAIL, code, message);
    }
}
