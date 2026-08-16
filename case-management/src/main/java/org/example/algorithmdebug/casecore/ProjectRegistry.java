package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;
import org.example.algorithmdebug.contracts.SchemaVersions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 将独立 Maven 算法模块只读登记到外部 Agent Workspace。 */
public final class ProjectRegistry {

    private static final String POM_FILE_NAME = "pom.xml";

    private final WorkspaceManifestRepository manifestRepository;
    private final ProjectRegistrationRepository registrationRepository;
    private final RepositoryRootLocator repositoryRootLocator;
    private final ProjectIdGenerator projectIdGenerator;
    private final Clock clock;

    /**
     * 创建项目注册器。
     *
     * @param manifestRepository Workspace Manifest 仓储
     * @param registrationRepository 项目登记仓储
     * @param repositoryRootLocator Git 仓库根定位器
     * @param projectIdGenerator 默认 ProjectId 生成器
     * @param clock 登记时间时钟
     */
    public ProjectRegistry(
            WorkspaceManifestRepository manifestRepository,
            ProjectRegistrationRepository registrationRepository,
            RepositoryRootLocator repositoryRootLocator,
            ProjectIdGenerator projectIdGenerator,
            Clock clock) {
        if (manifestRepository == null || registrationRepository == null
                || repositoryRootLocator == null || projectIdGenerator == null || clock == null) {
            throw new IllegalArgumentException("ProjectRegistry 依赖不能为空");
        }
        this.manifestRepository = manifestRepository;
        this.registrationRepository = registrationRepository;
        this.repositoryRootLocator = repositoryRootLocator;
        this.projectIdGenerator = projectIdGenerator;
        this.clock = clock;
    }

    /**
     * 登记 Maven 算法模块；相同 ID 与模块路径的重复调用为幂等成功。
     *
     * @param workspaceRoot 已初始化的外部 Workspace 根目录
     * @param moduleRoot 含独立 pom.xml 的算法模块目录
     * @param requestedId 可选显式 ProjectId
     * @return 当前登记信息及本次是否创建
     */
    public ProjectRegistrationResult register(
            Path workspaceRoot,
            Path moduleRoot,
            Optional<ProjectId> requestedId) {
        if (moduleRoot == null || requestedId == null) {
            throw new IllegalArgumentException("moduleRoot 和 requestedId 不能为空");
        }
        WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
        manifestRepository.require(layout);
        Path canonicalModule = canonicalMavenModule(moduleRoot);
        Path pom = canonicalModule.resolve(POM_FILE_NAME);
        Path canonicalRepository = repositoryRootLocator.locate(canonicalModule);
        requireExternalWorkspace(layout, canonicalRepository);
        String modulePortable = portable(canonicalModule);
        ProjectId projectId = requestedId.orElseGet(() -> projectIdGenerator.generate(canonicalModule));
        layout.projectWorkspace(projectId);

        List<ProjectRegistration> registrations = registrationRepository.findAll(layout);
        Optional<ProjectRegistration> sameId = registrations.stream()
                .filter(registration -> registration.projectId().equals(projectId))
                .findFirst();
        if (sameId.isPresent()) {
            ProjectRegistration existing = sameId.orElseThrow();
            if (existing.moduleRoot().equals(modulePortable)) {
                return new ProjectRegistrationResult(existing, false);
            }
            throw new WorkspaceException(
                    "PROJECT_ID_CONFLICT", "ProjectId 已指向另一个算法模块: " + projectId.value());
        }
        if (registrations.stream().anyMatch(registration -> registration.moduleRoot().equals(modulePortable))) {
            throw new WorkspaceException(
                    "PROJECT_PATH_CONFLICT", "算法模块路径已使用另一个 ProjectId 登记: " + modulePortable);
        }

        ProjectRegistration registration = new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                projectId,
                canonicalModule.getFileName().toString(),
                portable(canonicalRepository),
                modulePortable,
                modulePortable,
                POM_FILE_NAME,
                "MAVEN",
                sha256(pom),
                clock.instant());
        createProjectDirectories(layout, projectId);
        registrationRepository.create(layout, registration);
        return new ProjectRegistrationResult(registration, true);
    }

    private static Path canonicalMavenModule(Path moduleRoot) {
        try {
            Path canonical = moduleRoot.toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("PROJECT_NOT_MAVEN", "算法模块路径不是目录: " + canonical);
            }
            Path pom = canonical.resolve(POM_FILE_NAME);
            if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("PROJECT_NOT_MAVEN", "算法模块缺少普通文件 pom.xml: " + canonical);
            }
            return canonical;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("PROJECT_NOT_MAVEN", "无法读取算法模块: " + moduleRoot, failure);
        }
    }

    private static void createProjectDirectories(WorkspaceLayout layout, ProjectId projectId) {
        Path projectRoot = layout.projectWorkspace(projectId);
        List<Path> directories = List.of(
                projectRoot,
                projectRoot.resolve("knowledge"),
                projectRoot.resolve("knowledge").resolve("sources"),
                projectRoot.resolve("knowledge").resolve("manifests"),
                projectRoot.resolve("knowledge").resolve("indexes"),
                layout.projectCases(projectId));
        for (Path directory : directories) {
            if (!directory.normalize().startsWith(projectRoot)) {
                throw new WorkspaceException("WORKSPACE_PATH_INVALID", "项目目录越过 Workspace 边界: " + directory);
            }
            try {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkspaceException("WORKSPACE_PATH_INVALID", "项目路径不是目录: " + directory);
                }
            } catch (IOException | SecurityException failure) {
                throw new WorkspaceException("WORKSPACE_WRITE_FAILED", "创建项目 Workspace 目录失败: " + directory, failure);
            }
        }
    }

    private static void requireExternalWorkspace(WorkspaceLayout layout, Path repositoryRoot) {
        try {
            Path workspaceRoot = layout.root().toRealPath();
            Path canonicalRepository = repositoryRoot.toRealPath();
            if (workspaceRoot.startsWith(canonicalRepository)
                    || canonicalRepository.startsWith(workspaceRoot)) {
                throw new WorkspaceException(
                        "WORKSPACE_PATH_INVALID", "Workspace 必须位于目标算法仓库之外");
            }
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "无法验证 Workspace 与目标算法仓库的路径边界", failure);
        }
    }

    private static String sha256(Path pom) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(pom)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("PROJECT_NOT_MAVEN", "读取算法模块 pom.xml 失败: " + pom, failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行时不支持 SHA-256", impossible);
        }
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
