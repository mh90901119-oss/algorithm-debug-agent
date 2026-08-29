package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 在外部 Workspace 中原子创建并读取项目登记记录。 */
public final class ProjectRegistrationRepository {

    private static final String REGISTRATION_FILE_NAME = "project.json";

    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /**
     * 创建项目登记仓储。
     *
     * @param mapper 有界 JSON Mapper
     * @param writer create-new 原子写入器
     */
    public ProjectRegistrationRepository(BoundedDocumentMapper mapper, AtomicDocumentWriter writer) {
        if (mapper == null || writer == null) {
            throw new IllegalArgumentException("mapper and writer must not be null");
        }
        this.mapper = mapper;
        this.writer = writer;
    }

    /**
     * 按 ProjectId 查找登记记录。
     *
     * @param layout Workspace 布局
     * @param projectId 项目 ID
     * @return 未登记时为空
     */
    public Optional<ProjectRegistration> findById(WorkspaceLayout layout, ProjectId projectId) {
        Path projectRoot = requireLayout(layout).projectWorkspace(projectId);
        Path registrationPath = projectRoot.resolve(REGISTRATION_FILE_NAME);
        if (Files.notExists(registrationPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(readRegistration(registrationPath, projectId));
    }

    /**
     * 读取全部已完成登记的项目，不递归扫描目标算法仓库。
     *
     * @param layout Workspace 布局
     * @return 按 ProjectId 排序的不可变登记列表
     */
    public List<ProjectRegistration> findAll(WorkspaceLayout layout) {
        Path projectsRoot = requireLayout(layout).projectsRoot();
        if (Files.notExists(projectsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(projectsRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("WORKSPACE_PATH_INVALID", "The Workspace projects path is not a directory: " + projectsRoot);
        }
        List<ProjectRegistration> registrations = new ArrayList<>();
        try (var children = Files.list(projectsRoot)) {
            for (Path child : children.sorted().toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path registrationPath = child.resolve(REGISTRATION_FILE_NAME);
                if (Files.notExists(registrationPath, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                ProjectId expectedId = projectIdFromDirectory(child);
                registrations.add(readRegistration(registrationPath, expectedId));
            }
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "Failed to read the Workspace projects directory: " + projectsRoot, failure);
        }
        registrations.sort(Comparator.comparing(registration -> registration.projectId().value()));
        return List.copyOf(registrations);
    }

    /**
     * 原子创建项目登记记录，已有终态文档不得覆盖。
     *
     * @param layout Workspace 布局
     * @param registration 已校验登记信息
     */
    public void create(WorkspaceLayout layout, ProjectRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        Path projectRoot = requireLayout(layout).projectWorkspace(registration.projectId());
        Path path = projectRoot.resolve(REGISTRATION_FILE_NAME);
        boolean directoryCreated = Files.notExists(projectRoot, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.createDirectories(projectRoot);
            writer.writeNew(path, mapper.writeJson(registration));
        } catch (IOException | SecurityException | WorkspaceException failure) {
            cleanupEmptyDirectory(projectRoot, directoryCreated, failure);
            throw new WorkspaceException(
                    "WORKSPACE_WRITE_FAILED", "Unable to create project registration: " + path, failure);
        }
    }

    /** 原子替换同一 ProjectId 的已有注册配置。 */
    public void replace(WorkspaceLayout layout, ProjectRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        Path path = requireLayout(layout)
                .projectWorkspace(registration.projectId())
                .resolve(REGISTRATION_FILE_NAME);
        try {
            writer.replace(path, mapper.writeJson(registration));
        } catch (WorkspaceException failure) {
            throw new WorkspaceException("WORKSPACE_WRITE_FAILED", "Failed to update project registration: " + path, failure);
        }
    }

    private ProjectRegistration readRegistration(Path path, ProjectId expectedId) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("PROJECT_REGISTRATION_INVALID", "Project registration is not a regular file: " + path);
        }
        try {
            ProjectRegistration registration = mapper.readJson(path, ProjectRegistration.class);
            if (!expectedId.equals(registration.projectId())) {
                throw new WorkspaceException(
                        "PROJECT_REGISTRATION_INVALID",
                        "project.json projectId does not match the directory name: " + path);
            }
            return registration;
        } catch (WorkspaceException failure) {
            if ("PROJECT_REGISTRATION_INVALID".equals(failure.code())) {
                throw failure;
            }
            throw new WorkspaceException("PROJECT_REGISTRATION_INVALID", "Project registration is invalid: " + path, failure);
        }
    }

    private static ProjectId projectIdFromDirectory(Path projectDirectory) {
        try {
            return new ProjectId(projectDirectory.getFileName().toString());
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException(
                    "PROJECT_REGISTRATION_INVALID",
                    "Project registration directory name is not a valid ProjectId: " + projectDirectory,
                    failure);
        }
    }

    private static WorkspaceLayout requireLayout(WorkspaceLayout layout) {
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        return layout;
    }

    private static void cleanupEmptyDirectory(Path directory, boolean created, Throwable primaryFailure) {
        if (!created) {
            return;
        }
        try {
            Files.deleteIfExists(directory);
            Path projectsRoot = directory.getParent();
            if (projectsRoot != null) {
                Files.deleteIfExists(projectsRoot);
            }
        } catch (IOException | SecurityException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }}
